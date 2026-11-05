package com.tradeanalysis.strategy.trading;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last traded prices for the UI, close to real time without breaking Kite's quote limit (1 request per second for
 * the whole account). Every screen polls about once a second; all symbols anyone asked for in the last few seconds
 * are fetched together in at most one Kite call per second, and everyone is served from that.
 */
@Component
public class QuoteCache {

    public record Quotes(Map<String, Double> prices, Instant asOf) {}

    private record Quote(Double price, Instant at) {}

    static final int MAX_SYMBOLS = 500;

    private final KiteApi kite;
    private final Clock clock;
    private final Duration minInterval;
    private final Duration watchFor;
    private final Map<String, Quote> quotes = new ConcurrentHashMap<>();
    private final Map<String, Instant> watched = new ConcurrentHashMap<>();
    private volatile Instant lastCall = Instant.EPOCH;

    public QuoteCache(KiteApi kite, Clock clock,
                      @Value("${trading.quotes.min-interval-ms:1000}") long minIntervalMs,
                      @Value("${trading.quotes.watch-seconds:10}") long watchSeconds) {
        this.kite = kite; this.clock = clock;
        this.minInterval = Duration.ofMillis(minIntervalMs);
        this.watchFor = Duration.ofSeconds(watchSeconds);
    }

    public Quotes get(List<String> symbols) {
        Instant asked = clock.instant();
        symbols.forEach(s -> watched.put(s, asked));
        if (needsFetch(symbols, asked)) {
            synchronized (this) {
                Instant now = clock.instant();
                if (needsFetch(symbols, now)) fetch(now);
            }
        }
        Map<String, Double> out = new LinkedHashMap<>();
        Instant oldest = null;
        for (String s : symbols) {
            Quote q = quotes.get(s);
            if (q == null || q.price() == null) continue;
            out.put(s, q.price());
            if (oldest == null || q.at().isBefore(oldest)) oldest = q.at();
        }
        return new Quotes(out, oldest);
    }

    /** Fetch when a symbol has no price yet, or prices are older than the interval and Kite may be called again. */
    private boolean needsFetch(List<String> symbols, Instant now) {
        boolean canCall = !now.isBefore(lastCall.plus(minInterval));
        for (String s : symbols) {
            Quote q = quotes.get(s);
            if (q == null) return true;                                // never seen: fetch (waiting out the interval)
            if (canCall && q.at().plus(minInterval).isBefore(now.plusMillis(1))) return true;
        }
        return false;
    }

    private void fetch(Instant requested) {
        long waitMs = Duration.between(requested, lastCall.plus(minInterval)).toMillis();
        if (waitMs > 0) {                                              // a new symbol right after a call: keep to 1/second
            try { Thread.sleep(waitMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        Instant now = waitMs > 0 ? clock.instant() : requested;
        watched.entrySet().removeIf(e -> e.getValue().isBefore(now.minus(watchFor)));
        quotes.keySet().removeIf(s -> !watched.containsKey(s));
        List<String> batch = watched.keySet().stream().sorted().limit(MAX_SYMBOLS).toList();
        lastCall = now;
        Map<String, Double> prices = kite.ltp(batch);
        for (String s : batch) quotes.put(s, new Quote(prices.get(s), now));
    }
}
