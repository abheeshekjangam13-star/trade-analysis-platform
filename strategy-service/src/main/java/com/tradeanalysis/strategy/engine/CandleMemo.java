package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Candles read once and shared by many backtests: a portfolio report runs every strategy for every window, and they
 * all read the same index candles. Inside {@link #with}, each (symbol, interval) is fetched from market-data once
 * (widened when a later read reaches further) and every read is cut from it; outside, reads go straight to market-data.
 *
 * <p>A range that ran up to "now" when it was fetched counts as reaching any later "now" in the same memo: the whole
 * report sees one snapshot of the market. Safe to share across threads.
 */
public final class CandleMemo {

    private static final ThreadLocal<CandleMemo> CURRENT = new ThreadLocal<>();

    private record Entry(Instant from, Instant to, List<Candle> candles) {}

    private final Instant opened;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger fetches = new java.util.concurrent.atomic.AtomicInteger();

    public CandleMemo() { this(Instant.now()); }
    /** A memo for a run that started at {@code opened} (ranges fetched up to then count as "up to now"). */
    public CandleMemo(Instant opened) { this.opened = opened; }

    /** Runs the task with this memo serving its candle reads (on the calling thread). */
    public <T> T with(Supplier<T> task) {
        CandleMemo before = CURRENT.get();
        CURRENT.set(this);
        try { return task.get(); }
        finally { if (before == null) CURRENT.remove(); else CURRENT.set(before); }
    }

    /** The candles in [from, to]: from the current memo when there is one, else from market-data. */
    public static PriceSeries getRange(MarketDataClient md, String symbol, Interval interval, Instant from, Instant to) {
        CandleMemo memo = CURRENT.get();
        return memo == null ? md.getRange(symbol, interval, from, to) : memo.read(md, symbol, interval, from, to);
    }

    /** How many times this memo went to market-data. */
    public int fetches() { return fetches.get(); }

    PriceSeries read(MarketDataClient md, String symbol, Interval interval, Instant from, Instant to) {
        String key = symbol.toUpperCase() + "|" + interval;
        Entry e;
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            e = entries.get(key);
            if (e == null || !covers(e, from, to)) {
                Instant f = e == null || from.isBefore(e.from()) ? from : e.from();
                Instant t = e == null || to.isAfter(e.to()) ? to : e.to();
                fetches.incrementAndGet();
                e = new Entry(f, t, md.getRange(symbol, interval, f, t).candles());
                entries.put(key, e);
            }
        }
        List<Candle> in = e.candles().stream()
                .filter(c -> !c.timestamp().isBefore(from) && !c.timestamp().isAfter(to)).toList();
        // As market-data answers a range without candles.
        if (in.isEmpty()) throw new IllegalStateException("No data in range for symbol: " + symbol);
        return new PriceSeries(symbol, interval, in);
    }

    private boolean covers(Entry e, Instant from, Instant to) {
        return !from.isBefore(e.from()) && (!to.isAfter(e.to()) || !e.to().isBefore(opened));
    }
}
