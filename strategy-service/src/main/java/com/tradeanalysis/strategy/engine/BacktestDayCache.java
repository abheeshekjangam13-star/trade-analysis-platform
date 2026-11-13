package com.tradeanalysis.strategy.engine;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tradeanalysis.common.dto.Signal;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.sql.Types;
import java.util.*;

/**
 * Simulated backtest days, so running a backtest again only simulates the days it hasn't seen.
 *
 * <p>A past trading day's outcome never changes: the engines (except the EMA crossover, see {@link BacktestEngine})
 * decide a day from that day's candles and a fixed few days before it. So each simulated day is stored under a
 * fingerprint of everything that decides it (the strategy, its exact parameters, the index and {@link #ENGINE_VERSION})
 * and reused by later runs. Only days before today, and only days the engine could judge (not NO_DATA), are stored.
 *
 * <p>Bump {@link #ENGINE_VERSION} whenever engine logic, charges or lot sizes change in a way that changes past
 * results: every stored day is then simulated again. Rows unused for {@link #KEEP_UNUSED} are deleted.
 */
@Component
public class BacktestDayCache {

    private static final Logger log = LoggerFactory.getLogger(BacktestDayCache.class);

    /** Part of every fingerprint: bump to make every backtest simulate all its days again. */
    static final int ENGINE_VERSION = 1;
    static final Duration KEEP_UNUSED = Duration.ofDays(30);
    private static final Set<String> STORABLE = Set.of("TRADED", "NO_TRADE", "SKIPPED");

    /** One simulated day: its report, its trades before the capital limit, and its signals. */
    public record Day(LocalDate date, NiftyOpeningBreakoutEngine.DayReport report, List<NiftyTradeLog> trades, List<Signal> signals) {}

    /** Stored trade fields only: no ids (each run saves its own rows) and nothing the capital limit adds later. */
    @JsonIgnoreProperties(value = {"id", "backtestResultId", "createdAt", "skipReason", "estEntryPremium", "capitalUsed",
            "capitalUsedPct", "estIv", "ivAssumed", "backtestCapital"}, ignoreUnknown = true)
    private abstract static class TradeFields {}

    private final BacktestDayCacheRepository repo;
    private final Clock clock;
    private final JdbcTemplate jdbc;   // null in unit tests: stores through the repository
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY)   // records (Day, DayReport, Signal) are read through their constructors
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .addMixIn(NiftyTradeLog.class, TradeFields.class);

    @Autowired
    public BacktestDayCache(BacktestDayCacheRepository repo, Clock clock, JdbcTemplate jdbc) { this.repo = repo; this.clock = clock; this.jdbc = jdbc; }
    BacktestDayCache(BacktestDayCacheRepository repo, Clock clock) { this(repo, clock, null); }

    /** The fingerprint of everything that decides a day's outcome. */
    public String key(Long strategyId, String symbol, Map<String, String> parameters) {
        try {
            String params = json.writeValueAsString(new TreeMap<>(parameters == null ? Map.of() : parameters));
            String text = "v" + ENGINE_VERSION + "|" + strategyId + "|" + symbol.toUpperCase() + "|" + params;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The stored days between from and to (inclusive); a row that can't be read back is left out (simulated again). */
    public Map<LocalDate, Day> load(String key, LocalDate from, LocalDate to) {
        Map<LocalDate, Day> days = new TreeMap<>();
        if (to.isBefore(from)) return days;
        for (BacktestDayCacheEntry e : repo.findByCacheKeyAndTradeDateBetween(key, from, to)) {
            try { days.put(e.getTradeDate(), json.readValue(e.getPayload(), Day.class)); }
            catch (Exception ex) { log.warn("BACKTEST_CACHE unreadable day {} {}: {}", key.substring(0, 8), e.getTradeDate(), ex.getMessage()); }
        }
        if (!days.isEmpty()) repo.touch(key, clock.instant());
        return days;
    }

    /** Whether a simulated day may be stored: a past day the engine could judge, with every trade closed. */
    static boolean storable(Day d, LocalDate today) {
        return d.date().isBefore(today) && d.report() != null && STORABLE.contains(d.report().status())
                && d.trades().stream().allMatch(t -> t.getExitTimestamp() != null);
    }

    /** Stores the storable days. Call before the capital limit resizes the trades. Never fails the backtest. */
    public int store(String key, Collection<Day> simulated, LocalDate today) {
        Instant now = clock.instant();
        List<BacktestDayCacheEntry> rows = new ArrayList<>();
        for (Day d : simulated) {
            if (!storable(d, today)) continue;
            try { rows.add(new BacktestDayCacheEntry(key, d.date(), json.writeValueAsString(d), now)); }
            catch (Exception e) { log.warn("BACKTEST_CACHE could not store {}: {}", d.date(), e.getMessage()); }
        }
        if (rows.isEmpty()) return 0;
        if (jdbc != null) {
            // One batched insert. A day another run stored meanwhile (e.g. the same strategy in two reports at once) is
            // skipped by the database: a failed insert would otherwise spoil the whole backtest's transaction.
            List<Object[]> args = rows.stream().map(r -> new Object[]{r.getCacheKey(), r.getTradeDate(), r.getPayload(),
                    r.getLastUsedAt().atOffset(ZoneOffset.UTC), r.getLastUsedAt().atOffset(ZoneOffset.UTC)}).toList();
            jdbc.batchUpdate("INSERT INTO backtest_day_cache (cache_key, trade_date, payload, created_at, last_used_at) "
                    + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING", args,
                    new int[]{Types.VARCHAR, Types.DATE, Types.VARCHAR, Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE});
            return rows.size();
        }
        try {
            repo.saveAll(rows);
            return rows.size();
        } catch (RuntimeException e) {
            // Another run stored some of these days at the same moment: keep whatever doesn't clash.
            int saved = 0;
            for (BacktestDayCacheEntry r : rows) {
                try { repo.save(r); saved++; } catch (RuntimeException ignored) { /* already stored */ }
            }
            return saved;
        }
    }

    /** Copies of trades for a new backtest: fresh rows with the same fields. */
    List<NiftyTradeLog> copies(List<NiftyTradeLog> trades) {
        try { return Arrays.asList(json.readValue(json.writeValueAsString(trades), NiftyTradeLog[].class)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
    public void deleteUnused() {
        int n = repo.deleteUnusedSince(clock.instant().minus(KEEP_UNUSED));
        if (n > 0) log.info("BACKTEST_CACHE deleted {} days unused for {} days", n, KEEP_UNUSED.toDays());
    }
}
