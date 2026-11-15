package com.tradeanalysis.strategy.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Exchange-issued Algo IDs (table algo_id). An admin enters the ID NSE gives each registered strategy type, a DEFAULT
 * one (e.g. the exchange's generic ID while it applies) and one for MANUAL order tickets. Each live order carries the
 * Algo ID of its strategy type, else DEFAULT; manual orders carry MANUAL, else DEFAULT. Also snapshots the strategy's
 * settings for the order's audit record.
 */
@Service
public class AlgoIds {

    public static final String DEFAULT = "DEFAULT";
    public static final String MANUAL = "MANUAL";
    /** Strategy types the builder offers (the scopes an Algo ID can be set for, besides DEFAULT and MANUAL). */
    public static final List<String> STRATEGY_TYPES = List.of("NIFTY_OPENING_BREAKOUT", "SMA_CROSSOVER", "RSI_THRESHOLD", "MACD_SIGNAL");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /** What an order goes out with: its Algo ID (or null) and the strategy snapshot (JSON, or null for manual orders). */
    public record Resolved(String algoId, String strategySnapshot) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public AlgoIds(JdbcTemplate jdbc, ObjectMapper json, Clock clock) { this.jdbc = jdbc; this.json = json; this.clock = clock; }

    /** Every scope with its Algo ID (null when not set), DEFAULT and MANUAL first. */
    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(DEFAULT, null); out.put(MANUAL, null);
        STRATEGY_TYPES.forEach(t -> out.put(t, null));
        jdbc.query("SELECT scope, algo_id FROM algo_id", rs -> { out.put(rs.getString(1), rs.getString(2)); });
        return out;
    }

    /** Sets (or with a blank id, removes) the Algo ID of one scope. */
    public void set(String scope, String algoId, Long byUserId) {
        String s = scope == null ? "" : scope.trim().toUpperCase(Locale.ROOT);
        if (!s.equals(DEFAULT) && !s.equals(MANUAL) && !STRATEGY_TYPES.contains(s))
            throw new IllegalArgumentException("Unknown Algo ID scope " + scope + ": use DEFAULT, MANUAL or a strategy type");
        String id = algoId == null ? "" : algoId.trim();
        if (!id.isEmpty() && !ID.matcher(id).matches()) throw new IllegalArgumentException("An Algo ID has up to 32 letters, digits, - or _");
        jdbc.update("DELETE FROM algo_id WHERE scope = ?", s);
        if (id.isEmpty()) return;
        jdbc.update("INSERT INTO algo_id (scope, algo_id, updated_at, updated_by) VALUES (?, ?, ?, ?)", s, id, Timestamp.from(clock.instant()), byUserId);
    }

    /** The Algo ID and strategy snapshot for an order of this strategy (null = a manual order). */
    public Resolved resolve(Long strategyId) {
        if (strategyId == null) return new Resolved(lookup(MANUAL).orElseGet(() -> lookup(DEFAULT).orElse(null)), null);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT name, type FROM strategy WHERE id = ?", strategyId);
        if (rows.isEmpty()) return new Resolved(lookup(DEFAULT).orElse(null), null);
        String type = String.valueOf(rows.get(0).get("type"));
        Map<String, String> params = new TreeMap<>();
        jdbc.query("SELECT param_key, param_value FROM strategy_param WHERE strategy_id = ?",
                rs -> { params.put(rs.getString(1), rs.getString(2)); }, strategyId);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("strategyId", strategyId);
        snap.put("name", rows.get(0).get("name"));
        snap.put("type", type);
        snap.put("parameters", params);
        String snapshot;
        try { snapshot = json.writeValueAsString(snap); } catch (Exception e) { snapshot = null; }
        if (snapshot != null && snapshot.length() > 4000) snapshot = snapshot.substring(0, 4000);
        return new Resolved(lookup(type).orElseGet(() -> lookup(DEFAULT).orElse(null)), snapshot);
    }

    private Optional<String> lookup(String scope) {
        return jdbc.query("SELECT algo_id FROM algo_id WHERE scope = ?", rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.<String>empty(), scope);
    }
}
