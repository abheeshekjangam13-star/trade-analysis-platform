package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.strategy.model.NiftyTradeLog;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * The same-strike wait in a backtest, applied the way live trading applies it (LiveStrategyRunner#sameStrikeWait): when
 * another strategy already bought the same option (index, CE/PE and strike) that day, a second buy waits {@code minutes}
 * after its signal candle closes, then buys at the index price of that moment (its option premium then, with real
 * prices). The trade keeps its own exit; one whose exit came during the wait is dropped (skip reason {@link #REASON}).
 * Trades on one minute are taken in strategy id order, so the lowest id buys first. Give it the trades after their
 * option prices are applied and before the capital fit.
 */
public final class SameStrikeWait {

    public static final String REASON = "SAME_STRIKE_WAIT";

    private SameStrikeWait() {}

    /** The trades dropped by the wait (marked skipped); delayed trades are changed in place. */
    public static List<NiftyTradeLog> apply(List<NiftyTradeLog> trades, Integer minutes, Map<String, PriceSeries> oneMinute) {
        if (minutes == null || minutes < 1 || trades.isEmpty()) return List.of();
        Map<String, NavigableMap<Instant, Double>> closes = new HashMap<>();
        if (oneMinute != null) oneMinute.forEach((sym, s) -> {
            NavigableMap<Instant, Double> m = new TreeMap<>();
            if (s != null) for (Candle c : s.candles()) m.put(c.timestamp(), c.close().doubleValue());
            closes.put(sym, m);
        });
        List<NiftyTradeLog> ordered = new ArrayList<>();
        for (NiftyTradeLog t : trades) if (t.getSkipReason() == null && t.getEntryTimestamp() != null && t.getExitTimestamp() != null) ordered.add(t);
        ordered.sort(Comparator.comparing(NiftyTradeLog::getEntryTimestamp)
                .thenComparing(t -> t.getStrategyId() == null ? Long.MAX_VALUE : t.getStrategyId()));
        Set<Key> bought = new HashSet<>();
        List<NiftyTradeLog> dropped = new ArrayList<>();
        for (NiftyTradeLog t : ordered) {
            Key key = Key.of(t);
            if (!bought.contains(key)) { bought.add(key); continue; }
            // Bought as the signal candle closes (5 minutes after its stamp), plus the wait.
            Instant buyAt = t.getEntryTimestamp().plusSeconds((5L + minutes) * 60);
            if (!t.getExitTimestamp().isAfter(buyAt.minusSeconds(60))) {   // stopped or at target during the wait
                t.markSkipped(REASON);
                dropped.add(t);
                continue;
            }
            Double spot = closes.getOrDefault(t.getSymbol(), new TreeMap<>()).get(buyAt.minusSeconds(60));   // the minute ending at buyAt
            if (spot == null) continue;   // no minute prices: kept as it was
            t.delayEntry(minutes, spot, buyAt);
        }
        return dropped;
    }

    /** One option on one day. */
    record Key(LocalDate date, String symbol, String optionType, int strike) {
        static Key of(NiftyTradeLog t) { return new Key(t.getTradeDate(), t.getSymbol(), t.getOptionType(), t.getStrikePrice()); }
    }
}
