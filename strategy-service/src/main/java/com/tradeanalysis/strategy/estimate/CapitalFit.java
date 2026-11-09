package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Keeps a backtest within the capital it is run with. Trades are taken in entry order; each one gets only the lots the
 * money free at its entry can buy (premium × lot size per lot, the same estimate the reports show), and a trade is
 * skipped when not even one lot is free. Money is freed when a trade exits, and an exit at the same instant as an
 * entry frees it first.
 */
@Component
public class CapitalFit {

    private final OptionEstimates estimates;

    public CapitalFit(OptionEstimates estimates) { this.estimates = estimates; }

    /** The trades that fit (resized to the free capital at each entry) and how many were skipped for lack of capital. */
    public record Fit(List<NiftyTradeLog> kept, int skipped) {}

    public Fit fit(List<NiftyTradeLog> trades, double capital) {
        if (trades.isEmpty() || capital <= 0) return new Fit(trades, 0);
        estimates.estimate(trades, Map.of());   // premiums per unit (the lot count does not change them)
        List<NiftyTradeLog> ordered = new ArrayList<>(trades);
        ordered.sort(Comparator.comparing(NiftyTradeLog::getEntryTimestamp));

        PriorityQueue<Held> open = new PriorityQueue<>(Comparator.comparing(Held::exit));
        double committed = 0;
        List<NiftyTradeLog> kept = new ArrayList<>();
        int skipped = 0;
        for (NiftyTradeLog t : ordered) {
            Instant entry = t.getEntryTimestamp();
            while (!open.isEmpty() && !open.peek().exit().isAfter(entry)) committed -= open.poll().amount();
            double perLot = t.getEstEntryPremium() * t.getLotSize();
            int fits = perLot > 0 ? (int) Math.floor((capital - committed) / perLot + 1e-9) : t.getRequestedLots();
            int lots = Math.min(t.getRequestedLots(), fits);   // from what was asked for, so a trade can be fitted again
            if (lots < 1) { skipped++; continue; }   // not even one lot is free at this entry: skip the trade
            t.resize(lots);
            double amount = perLot * lots;
            committed += amount;
            open.add(new Held(exitOf(t), amount));
            kept.add(t);
        }
        return new Fit(kept, skipped);
    }

    private static Instant exitOf(NiftyTradeLog t) {
        return t.getExitTimestamp() != null ? t.getExitTimestamp() : t.getEntryTimestamp().plusSeconds(60);
    }

    private record Held(Instant exit, double amount) {}
}
