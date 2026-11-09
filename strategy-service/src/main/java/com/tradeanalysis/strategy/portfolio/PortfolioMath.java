package com.tradeanalysis.strategy.portfolio;

import com.tradeanalysis.strategy.model.NiftyTradeLog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.List;

/** Totals over a set of trades taken in time order (they may come from several strategies). */
final class PortfolioMath {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Capital the trades used, from their estimated premium × quantity: the average and largest per trade, and the most
     * in use at once (with the day it happened). Null fields when no trade has an estimate.
     */
    record CapitalUse(Double avg, Double max, Double peak, LocalDate peakOn, boolean ivAssumed) {}

    /** The capital in use at once is found by walking entries (+) and exits (−); an exit at the same instant frees capital first. */
    static CapitalUse capitalUse(List<NiftyTradeLog> trades) {
        List<NiftyTradeLog> used = trades.stream().filter(t -> t.getCapitalUsed() != null).toList();
        if (used.isEmpty()) return new CapitalUse(null, null, null, null, false);
        DoubleSummaryStatistics s = used.stream().mapToDouble(NiftyTradeLog::getCapitalUsed).summaryStatistics();

        record Event(Instant at, double delta, LocalDate day) {}
        List<Event> events = new ArrayList<>();
        for (NiftyTradeLog t : used) {
            Instant in = t.getEntryTimestamp() != null ? t.getEntryTimestamp()
                    : t.getTradeDate().atTime(9, 20).atZone(IST).toInstant();
            Instant out = t.getExitTimestamp() != null ? t.getExitTimestamp() : in.plusSeconds(60);
            events.add(new Event(in, t.getCapitalUsed(), t.getTradeDate()));
            events.add(new Event(out, -t.getCapitalUsed(), t.getTradeDate()));
        }
        events.sort(Comparator.comparing(Event::at).thenComparingDouble(Event::delta));
        double open = 0, peak = 0;
        LocalDate peakOn = null;
        for (Event e : events) {
            open += e.delta();
            if (open > peak + 1e-9) { peak = open; peakOn = e.day(); }
        }
        boolean iv = used.stream().anyMatch(t -> Boolean.TRUE.equals(t.getIvAssumed()));
        return new CapitalUse(Math.round(s.getAverage()) * 1.0, s.getMax(), (double) Math.round(peak), peakOn, iv);
    }

    /** profitFactor is null when there are no losing trades; maxDrawdown is the worst peak-to-trough fall (≤ 0). */
    record Summary(int trades, int wins, double grossPnl, double charges, double netPnl,
                   Double profitFactor, double maxDrawdown) {}

    private PortfolioMath() {}

    static Summary summarize(List<NiftyTradeLog> trades) {
        List<NiftyTradeLog> ordered = trades.stream()
                .sorted(Comparator.comparing(NiftyTradeLog::getEntryTimestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int wins = 0;
        double net = 0, charges = 0, won = 0, lost = 0, equity = 0, peak = 0, dd = 0;
        for (NiftyTradeLog t : ordered) {
            double p = t.getEstimatedPnl();
            net += p;
            charges += t.getCharges() == null ? 0 : t.getCharges();
            if (p > 0) { wins++; won += p; } else lost -= p;
            equity += p;
            peak = Math.max(peak, equity);
            dd = Math.min(dd, equity - peak);
        }
        return new Summary(ordered.size(), wins, net + charges, charges, net, lost > 0 ? won / lost : null, dd);
    }
}
