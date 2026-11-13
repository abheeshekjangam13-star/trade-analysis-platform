package com.tradeanalysis.marketdata.service;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.exception.NotFoundException;
import com.tradeanalysis.marketdata.client.KiteConnectClient;
import com.tradeanalysis.marketdata.model.CandleEntity;
import com.tradeanalysis.marketdata.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final CandleRepository repo;
    private final KiteConnectClient client;

    // Symbols that have been requested at least once — auto-refreshed.
    private final Set<String> watchedSymbols = ConcurrentHashMap.newKeySet();

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** How often the latest intraday candles may be re-fetched from Kite per symbol and interval. */
    static final Duration TOP_UP_MARKET = Duration.ofSeconds(10);
    static final Duration TOP_UP_CLOSED = Duration.ofMinutes(10);

    /** How long after a candle closes Kite is given to finish it before it is fetched again as final. */
    static final Duration SETTLE = Duration.ofSeconds(2);

    private final Clock clock;
    private final Map<String, Instant> lastTopUp = new ConcurrentHashMap<>();
    /** Per symbol and interval: the latest close whose candle was fetched again once it had settled. */
    private final Map<String, Instant> settled = new ConcurrentHashMap<>();
    private final Map<String, Object> writeLocks = new ConcurrentHashMap<>();

    /**
     * One lock per symbol and interval. Two requests (say, two Bank Nifty backtests) that fetch the same candles
     * would otherwise insert them twice and hit the unique key.
     */
    private Object lockFor(String symbol, Interval interval) {
        return writeLocks.computeIfAbsent(symbol.toUpperCase() + "|" + interval, k -> new Object());
    }

    @Autowired
    public MarketDataService(CandleRepository repo, KiteConnectClient client) {
        this(repo, client, Clock.systemUTC());
    }

    MarketDataService(CandleRepository repo, KiteConnectClient client, Clock clock) {
        this.repo = repo;
        this.client = client;
        this.clock = clock;
    }

    /** Returns the latest candles, fetching from upstream if the DB is empty and topping up intraday candles to now. */
    public PriceSeries getSeries(String symbol, Interval interval, int limit) {
        synchronized (lockFor(symbol, interval)) { return readSeries(symbol, interval, limit); }
    }

    // Not one transaction: each write commits before the lock is released, so the next request sees those candles.
    private PriceSeries readSeries(String symbol, Interval interval, int limit) {
        watchedSymbols.add(symbol.toUpperCase());
        String sym = symbol.toUpperCase();
        List<CandleEntity> entities = repo.findBySymbolAndIntervalCodeOrderByTimestampAsc(sym, interval.name());

        if (entities.isEmpty()) {
            // cold-start: fetch now
            List<Candle> fetched = client.fetch(sym, interval, limit);
            if (fetched.isEmpty()) throw new NotFoundException("No data found for symbol: " + sym);
            persist(sym, interval, fetched);
            lastTopUp.put(sym + "|" + interval, clock.instant());
            entities = repo.findBySymbolAndIntervalCodeOrderByTimestampAsc(sym, interval.name());
        } else if (topUp(sym, interval, entities.get(entities.size() - 1).getTimestamp())) {
            entities = repo.findBySymbolAndIntervalCodeOrderByTimestampAsc(sym, interval.name());
        }

        // Most recent `limit` candles, still oldest-first.
        List<Candle> candles = entities.stream().skip(Math.max(0, entities.size() - limit))
                .map(this::toDto).toList();
        return new PriceSeries(sym, interval, candles);
    }

    /** Returns candles in [from, to], backfilling from Kite any part of the range not yet stored. */
    public PriceSeries getRange(String symbol, Interval interval, Instant from, Instant to) {
        synchronized (lockFor(symbol, interval)) { return readRange(symbol, interval, from, to); }
    }

    private PriceSeries readRange(String symbol, Interval interval, Instant from, Instant to) {
        String sym = symbol.toUpperCase();
        List<CandleEntity> unchanged = backfill(sym, interval, from, to);
        // Read the range again only when backfill changed it; otherwise its read is the answer (a 360-day 1-minute
        // range is ~135k rows, so the second read was half the database work of every backtest).
        List<Candle> candles = (unchanged != null ? unchanged : repo.findInRange(sym, interval.name(), from, to))
                .stream().map(this::toDto).toList();
        if (candles.isEmpty()) throw new NotFoundException("No data in range for symbol: " + sym);
        return new PriceSeries(sym, interval, candles);
    }

    @Transactional(readOnly = true)
    public List<String> listSymbols() {
        return repo.findDistinctSymbols();
    }

    /** Refresh all watched symbols every 5 minutes. */
    @Scheduled(fixedDelayString = "${market-data.refresh-ms:300000}")
    @Transactional
    public void refresh() {
        for (String sym : watchedSymbols) {
            List<Candle> fetched = client.fetch(sym, Interval.DAY_1, 252);
            if (!fetched.isEmpty()) persist(sym, Interval.DAY_1, fetched);
        }
    }

    /**
     * Fetches from Kite whatever part of [from, to] is missing or may be stale. Returns the stored range as read when
     * nothing was fetched or deleted (so the caller needn't read it again), else null.
     */
    private List<CandleEntity> backfill(String symbol, Interval interval, Instant from, Instant to) {
        List<CandleEntity> stored = repo.findInRange(symbol, interval.name(), from, to);
        boolean changed = false;

        // Intraday bars not on an interval boundary (e.g. 11:15:58) came from an unaligned Kite
        // request; they never match the 09:15 reference bar and must be replaced.
        if (isIntraday(interval)) {
            long step = interval.duration().getSeconds();
            List<CandleEntity> misaligned = stored.stream()
                    .filter(e -> e.getTimestamp().getEpochSecond() % step != 0).toList();
            if (!misaligned.isEmpty()) {
                log.warn("Deleting {} misaligned {} candles for {}", misaligned.size(), interval, symbol);
                repo.deleteAll(misaligned);
                stored = repo.findInRange(symbol, interval.name(), from, to);
                changed = true;
            }
        }

        if (stored.isEmpty()) {
            persist(symbol, interval, client.fetchRange(symbol, interval, from, to));
            return null;
        }
        Instant first = stored.get(0).getTimestamp();
        Instant last  = stored.get(stored.size() - 1).getTimestamp();
        if (first.isAfter(from.plus(Duration.ofDays(4)))) { // tolerate weekends/holidays at range start
            persist(symbol, interval, client.fetchRange(symbol, interval, from, first));
            changed = true;
        }
        Instant refetchFrom = null;
        // Re-fetch the last stored bar too: it may have been saved while still forming.
        if (last.plus(interval.duration()).isBefore(to)) refetchFrom = last;
        Instant justClosed = unsettledBar(symbol, interval, to);
        if (justClosed != null && !justClosed.isAfter(last) && (refetchFrom == null || justClosed.isBefore(refetchFrom))) refetchFrom = justClosed;
        if (refetchFrom != null) {
            persist(symbol, interval, client.fetchRange(symbol, interval, refetchFrom, to));
            markSettled(symbol, interval, refetchFrom);
            changed = true;
        }
        if (fillGaps(symbol, interval, stored)) changed = true;
        return changed ? null : stored;
    }

    /** At most this many missing or partial days are fetched per request, so one request never stalls on Kite. */
    static final int MAX_GAP_DAYS_PER_REQUEST = 10;
    /** symbol|interval|date already re-fetched in this process: a day Kite can't complete (e.g. a short special session) is tried once. */
    private final Set<String> gapTried = ConcurrentHashMap.newKeySet();

    /**
     * Fills holes in the middle of the stored intraday candles, which the start/end checks above never see: a whole
     * trading day missing (the index has a daily candle for it but there are no intraday candles), or a past day with
     * fewer candles than a full 09:15–15:30 session. Only days strictly between the first and last stored day and
     * before today. Returns whether it fetched.
     */
    private boolean fillGaps(String symbol, Interval interval, List<CandleEntity> stored) {
        if (!isIntraday(interval) || stored.isEmpty()) return false;
        LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
        LocalDate first = stored.get(0).getTimestamp().atZone(IST).toLocalDate();
        LocalDate last = stored.get(stored.size() - 1).getTimestamp().atZone(IST).toLocalDate();
        if (!first.plusDays(1).isBefore(last)) return false;
        Map<LocalDate, Integer> perDay = new HashMap<>();
        for (CandleEntity e : stored) perDay.merge(e.getTimestamp().atZone(IST).toLocalDate(), 1, Integer::sum);
        int fullDay = fullSessionCandles(interval);
        Set<LocalDate> sessions = null;   // the index's trading days, from its daily candles (read only if needed)

        List<LocalDate> gaps = new ArrayList<>();
        for (LocalDate d = first.plusDays(1); d.isBefore(last) && d.isBefore(today); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            int n = perDay.getOrDefault(d, 0);
            if (n >= fullDay) continue;
            if (n == 0) {   // missing day or holiday: only a day the index traded (has a daily candle) is a gap
                if (sessions == null) sessions = dailySessions(symbol, first, last);
                if (!sessions.contains(d)) continue;
            }
            if (gapTried.add(symbol + "|" + interval + "|" + d)) gaps.add(d);
            if (gaps.size() >= MAX_GAP_DAYS_PER_REQUEST) break;
        }
        boolean fetched = false;
        for (LocalDate d : gaps) {
            try {
                List<Candle> got = client.fetchRange(symbol, interval, d.atTime(9, 15).atZone(IST).toInstant(), d.atTime(15, 30).atZone(IST).toInstant());
                log.info("GAP_FILL {} {} {}: had {} of {} candles, fetched {}", symbol, interval, d, perDay.getOrDefault(d, 0), fullDay, got.size());
                persist(symbol, interval, got);
                fetched = true;
            } catch (RuntimeException e) {
                log.warn("GAP_FILL {} {} {} failed: {}", symbol, interval, d, e.getMessage());
            }
        }
        return fetched;
    }

    /** Candles in a full 09:15–15:30 session (375 minutes): 375 one-minute, 75 five-minute, 7 hourly. */
    static int fullSessionCandles(Interval interval) {
        return (int) Math.ceil(375 * 60.0 / interval.duration().getSeconds());
    }

    private Set<LocalDate> dailySessions(String symbol, LocalDate from, LocalDate to) {
        Set<LocalDate> days = new HashSet<>();
        repo.findInRange(symbol, Interval.DAY_1.name(), from.atStartOfDay(IST).toInstant(), to.plusDays(1).atStartOfDay(IST).toInstant())
                .forEach(e -> days.add(e.getTimestamp().atZone(IST).toLocalDate()));
        return days;
    }

    /**
     * In market hours, the start of the candle that closed last, when it has not been fetched since it settled
     * ({@link #SETTLE} after its close): a request a moment after the close can store it before Kite has its last
     * ticks, and nothing else would fetch it again. Null when there is nothing to do.
     */
    private Instant unsettledBar(String symbol, Interval interval, Instant to) {
        if (!isIntraday(interval)) return null;
        Instant now = clock.instant();
        if (!marketOpen(now)) return null;
        long step = interval.duration().getSeconds();
        Instant close = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), step) * step);
        if (to.isBefore(close) || now.isBefore(close.plus(SETTLE)) || close.equals(settled.get(symbol + "|" + interval))) return null;
        return close.minus(interval.duration());
    }

    /** A fetch from {@code from} made now covers the last closed candle as final once it has settled. */
    private void markSettled(String symbol, Interval interval, Instant from) {
        if (!isIntraday(interval)) return;
        Instant now = clock.instant();
        long step = interval.duration().getSeconds();
        Instant close = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), step) * step);
        if (!now.isBefore(close.plus(SETTLE)) && !from.isAfter(close.minus(interval.duration())))
            settled.put(symbol + "|" + interval, close);
    }

    /**
     * Intraday candles after the last stored one (and the last one again: it may have been saved while still forming)
     * are fetched from Kite, at most every {@link #TOP_UP_MARKET} in market hours and {@link #TOP_UP_CLOSED} outside
     * them. A Kite failure keeps the stored candles. Returns whether it fetched.
     */
    private boolean topUp(String symbol, Interval interval, Instant lastStored) {
        if (!isIntraday(interval)) return false;
        Instant now = clock.instant();
        String key = symbol + "|" + interval;
        Instant prev = lastTopUp.get(key);
        if (prev != null && now.isBefore(prev.plus(marketOpen(now) ? TOP_UP_MARKET : TOP_UP_CLOSED))) return false;
        lastTopUp.put(key, now);
        try {
            persist(symbol, interval, client.fetchRange(symbol, interval, lastStored, now));
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not top up {} {} from Kite: {}", symbol, interval, e.getMessage());
            return false;
        }
    }

    /** Weekdays 09:15–15:31 IST (holidays aside). */
    static boolean marketOpen(Instant t) {
        if (com.tradeanalysis.common.market.SpecialSessions.open(t)) return true;   // e.g. Muhurat trading on a Sunday
        ZonedDateTime z = t.atZone(IST);
        if (z.getDayOfWeek() == DayOfWeek.SATURDAY || z.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
        LocalTime lt = z.toLocalTime();
        return !lt.isBefore(LocalTime.of(9, 15)) && lt.isBefore(LocalTime.of(15, 31));
    }

    private boolean isIntraday(Interval interval) {
        return interval != Interval.DAY_1 && interval != Interval.WEEK_1;
    }

    private void persist(String symbol, Interval interval, List<Candle> candles) {
        if (candles.isEmpty()) return;
        Instant min = candles.stream().map(Candle::timestamp).min(Instant::compareTo).get();
        Instant max = candles.stream().map(Candle::timestamp).max(Instant::compareTo).get();
        Map<Instant, CandleEntity> existing = new HashMap<>();
        repo.findInRange(symbol, interval.name(), min, max)
                .forEach(e -> existing.put(e.getTimestamp(), e));

        List<CandleEntity> toSave = new ArrayList<>();
        for (Candle c : candles) {
            CandleEntity e = existing.get(c.timestamp());
            if (e == null) {
                e = new CandleEntity(symbol, interval.name(),
                        c.timestamp(), c.open(), c.high(), c.low(), c.close(), c.volume());
                existing.put(c.timestamp(), e);
                toSave.add(e);
            } else if (e.update(c.open(), c.high(), c.low(), c.close(), c.volume())) {
                toSave.add(e);
            }
        }
        repo.saveAll(toSave);
    }

    private Candle toDto(CandleEntity e) {
        return new Candle(e.getTimestamp(), e.getOpen(), e.getHigh(), e.getLow(), e.getClose(), e.getVolume());
    }
}
