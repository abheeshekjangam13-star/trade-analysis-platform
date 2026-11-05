package com.tradeanalysis.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * One-time copy of the old H2 data files into PostgreSQL, keeping all ids.
 *
 * Run it after the services have started once against PostgreSQL (so Flyway has created the tables) and before
 * using them. It refuses to copy into a table that already has rows. Settings come from environment variables:
 * H2_DIR (default ./data), DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD (same as the services).
 */
public final class H2ToPostgres {

    /** H2 file (without .mv.db) → tables in foreign-key order. */
    static final Map<String, List<String>> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("market-data", List.of("candle"));
        TABLES.put("strategy", List.of("strategy", "strategy_param", "backtest_result", "nifty_trade_log",
                "nifty_position_state"));
    }
    private static final int BATCH = 1000;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(env("H2_DIR", "./data")).toAbsolutePath().normalize();
        String pgUrl = "jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432") + "/"
                + env("DB_NAME", "trade_analysis");
        System.out.println("Source H2 directory: " + dir);
        System.out.println("Target PostgreSQL:   " + pgUrl);

        try (Connection pg = DriverManager.getConnection(pgUrl, env("DB_USER", "trade"), env("DB_PASSWORD", ""))) {
            for (Map.Entry<String, List<String>> db : TABLES.entrySet()) {
                Path file = dir.resolve(db.getKey() + ".mv.db");
                if (!Files.exists(file)) {
                    System.out.println("Skipping " + file + " (not found)");
                    continue;
                }
                String h2Url = "jdbc:h2:file:" + dir.resolve(db.getKey()) + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE";
                try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "")) {
                    for (String table : db.getValue()) {
                        long n = copyTable(h2, pg, table);
                        System.out.printf("  %-22s %,10d rows copied%n", table, n);
                    }
                }
            }
        }
        System.out.println("Done. All row counts match.");
    }

    /** Copies one table (columns present on both sides), resets its id sequence and checks the row counts. */
    static long copyTable(Connection src, Connection dst, String table) throws SQLException {
        if (!tableExists(src, table)) {
            System.out.println("  " + table + ": not in source, skipped");
            return 0;
        }
        if (count(dst, table) > 0)
            throw new IllegalStateException("Target table " + table + " already has rows; refusing to copy "
                    + "(run this once, on an empty database)");

        List<String> targetCols = columns(dst, table);
        List<String> cols;
        try (Statement s = src.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
            ResultSetMetaData md = rs.getMetaData();
            cols = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String c = md.getColumnName(i).toLowerCase(Locale.ROOT);
                if (targetCols.contains(c)) cols.add(c);
                else System.out.println("  " + table + "." + c + ": no such column in target, skipped");
            }
        }
        String list = String.join(", ", cols);
        String insert = "INSERT INTO " + table + " (" + list + ") VALUES (" + "?, ".repeat(cols.size() - 1) + "?)";

        boolean autoCommit = dst.getAutoCommit();
        dst.setAutoCommit(false);
        long copied = 0;
        try (Statement s = src.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + list + " FROM " + table + (cols.contains("id") ? " ORDER BY id" : ""));
             PreparedStatement ps = dst.prepareStatement(insert)) {
            while (rs.next()) {
                for (int i = 1; i <= cols.size(); i++) ps.setObject(i, portable(rs.getObject(i)));
                ps.addBatch();
                if (++copied % BATCH == 0) ps.executeBatch();
            }
            ps.executeBatch();
            if (cols.contains("id")) resetIdentity(dst, table);
            dst.commit();
        } catch (SQLException | RuntimeException e) {
            dst.rollback();
            throw e;
        } finally {
            dst.setAutoCommit(autoCommit);
        }

        long target = count(dst, table);
        if (target != copied)
            throw new IllegalStateException(table + ": copied " + copied + " rows but target has " + target);
        return copied;
    }

    /** H2-specific value classes (e.g. ENUM) are passed on as text. */
    static Object portable(Object v) {
        return v != null && v.getClass().getName().startsWith("org.h2.") ? v.toString() : v;
    }

    /** Next generated id continues after the copied ones (PostgreSQL identity columns). */
    private static void resetIdentity(Connection dst, String table) throws SQLException {
        if (!dst.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")) return;
        try (Statement s = dst.createStatement()) {
            s.execute("SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), "
                    + "(SELECT COALESCE(MAX(id), 0) + 1 FROM " + table + "), false)");
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        for (String name : List.of(table, table.toUpperCase(Locale.ROOT))) {
            try (ResultSet rs = c.getMetaData().getTables(null, null, name, new String[]{"TABLE"})) {
                if (rs.next()) return true;
            }
        }
        return false;
    }

    private static List<String> columns(Connection c, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        for (String name : List.of(table, table.toUpperCase(Locale.ROOT))) {
            try (ResultSet rs = c.getMetaData().getColumns(null, null, name, null)) {
                while (rs.next()) cols.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            if (!cols.isEmpty()) break;
        }
        if (cols.isEmpty()) throw new IllegalStateException("Target table " + table + " does not exist; "
                + "start the services once against PostgreSQL so Flyway creates the schema");
        return cols;
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
