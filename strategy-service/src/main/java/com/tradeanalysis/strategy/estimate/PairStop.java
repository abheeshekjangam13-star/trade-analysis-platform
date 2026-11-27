package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.strategy.model.NiftyTradeLog;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * The pair stop in a backtest, applied the way live trading applies it (LiveStrategyRunner#pairStop). Two strategies
 * that bought the same option (same index, CE/PE and strike) on the same signal minute win or lose together, so when
 * one of them is stopped out at a loss (SL_HIT), the others still holding it are closed too (exit reason
 * {@link #REASON}):
 * <ul>
 *   <li>{@code points} 0: at once, at the same moment and price as the stopped trade;</li>
 *   <li>more: once the index goes that many more points against them past the stopped trade's exit (lower for calls,
 *   higher for puts), read from the 1-minute candles. A trade whose own exit comes first keeps it.</li>
 * </ul>
 * A stop by the max day loss is not a pair stop: that closes every trade anyway. Give it the trades that were taken
 * (after the capital fit), before the max day loss, which then sees the pair-stopped exits.
 */
public final class PairStop {

    public static final String REASON = "PAIR_STOP";

    private PairStop() {}

    /** The trades closed by the pair stop (changed in place), in the order they were closed. */
    public static List<NiftyTradeLog> apply(List<NiftyTradeLog> trades, Integer points, Map<String, PriceSeries> minutes) {
        if (points == null || points < 0 || trades.isEmpty()) return List.of();
        Map<String, List<Candle>> bySymbol = new HashMap<>();
        if (minutes != null) minutes.forEach((sym, s) -> {
            List<Candle> c = new ArrayList<>(s == null ? List.of() : s.candles());
            c.sort(Comparator.comparing(Candle::timestamp));
            bySymbol.put(sym, c);
        });
        Map<Key, List<NiftyTradeLog>> groups = new LinkedHashMap<>();
        for (NiftyTradeLog t : trades)
            if (t.getSkipReason() == null && t.getExitTimestamp() != null)
                groups.computeIfAbsent(Key.of(t), k -> new ArrayList<>()).add(t);
        List<NiftyTradeLog> closed = new ArrayList<>();
        for (List<NiftyTradeLog> group : groups.values()) {
            if (group.size() < 2) continue;
            List<NiftyTradeLog> byExit = new ArrayList<>(group);
            byExit.sort(Comparator.comparing(NiftyTradeLog::getExitTimestamp));
            Set<NiftyTradeLog> done = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int i = 0; i < byExit.size(); i++) {
                NiftyTradeLog stopped = byExit.get(i);
                if (done.contains(stopped) || !losingStop(stopped)) continue;
                for (NiftyTradeLog other : byExit.subList(i + 1, byExit.size())) {
                    if (done.contains(other) || !other.getExitTimestamp().isAfter(stopped.getExitTimestamp())) continue;
                    if (close(other, stopped, points, bySymbol.getOrDefault(other.getSymbol(), List.of()))) {
                        done.add(other);
                        closed.add(other);
                    }
                }
                byExit.sort(Comparator.comparing(NiftyTradeLog::getExitTimestamp));   // closed ones moved earlier
                done.add(stopped);
            }
        }
        return closed;
    }

    /** A stop-out at a loss: the only exit that closes the partner. */
    static boolean losingStop(NiftyTradeLog t) {
        return "SL_HIT".equals(t.getExitReason()) && t.getEstimatedPnl() < 0;
    }

    /** Closes {@code other} after {@code stopped}'s exit as the pair stop says; true when it did. */
    private static boolean close(NiftyTradeLog other, NiftyTradeLog stopped, int points, List<Candle> candles) {
        double level = stopped.getExitSpotPrice();
        if (points == 0) {
            other.close(stopped.getExitTimestamp(), level, REASON, level);
            return true;
        }
        boolean call = "CE".equals(other.getOptionType());
        double trigger = call ? level - points : level + points;
        // Candles stamped from the minute after the stop up to (not including) the trade's own exit minute.
        Instant from = stopped.getExitTimestamp(), until = other.getExitTimestamp();
        for (Candle c : candles) {
            Instant at = c.timestamp();
            if (!at.isAfter(from)) continue;
            if (!at.isBefore(until)) break;
            boolean reached = call ? c.low().doubleValue() <= trigger : c.high().doubleValue() >= trigger;
            if (reached) {
                other.closeInMinute(at, at, c.open().doubleValue(), trigger, REASON, trigger);
                return true;
            }
        }
        return false;
    }

    /** Trades holding the same option from the same signal minute. */
    record Key(LocalDate date, String symbol, String optionType, int strike, Instant entry) {
        static Key of(NiftyTradeLog t) {
            return new Key(t.getTradeDate(), t.getSymbol(), t.getOptionType(), t.getStrikePrice(), t.getEntryTimestamp());
        }
    }
}
