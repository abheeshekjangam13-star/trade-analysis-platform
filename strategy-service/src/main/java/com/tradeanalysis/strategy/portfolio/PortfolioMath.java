package com.tradeanalysis.strategy.portfolio;

import com.tradeanalysis.strategy.model.NiftyTradeLog;

import java.util.Comparator;
import java.util.List;

/** Totals over a set of trades taken in time order (they may come from several strategies). */
final class PortfolioMath {

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
