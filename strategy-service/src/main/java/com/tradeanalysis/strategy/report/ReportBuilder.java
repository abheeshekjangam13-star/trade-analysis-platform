package com.tradeanalysis.strategy.report;

import com.tradeanalysis.strategy.engine.NiftyOpeningBreakoutEngine.DayReport;
import com.tradeanalysis.strategy.model.NiftyTradeLog;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Builds the stored daily and monthly report rows of a backtest from its day outcomes and trades. */
public final class ReportBuilder {

    private ReportBuilder() {}

    public static List<BacktestDailyReport> daily(Long backtestId, List<DayReport> days, List<NiftyTradeLog> trades) {
        Map<LocalDate, List<NiftyTradeLog>> byDate = new HashMap<>();
        trades.forEach(t -> byDate.computeIfAbsent(t.getTradeDate(), d -> new ArrayList<>()).add(t));
        List<BacktestDailyReport> rows = new ArrayList<>();
        for (DayReport d : days) {
            List<NiftyTradeLog> dayTrades = byDate.getOrDefault(d.date(), List.of());
            double pnl = dayTrades.stream().mapToDouble(NiftyTradeLog::getEstimatedPnl).sum();
            rows.add(new BacktestDailyReport(backtestId, d.date(), d.gapPercent(), d.status(), d.reason(),
                    dayTrades.size(), pnl));
        }
        return rows;
    }

    /** Months covered by the day outcomes or the trades, in order; months without trades have zeros. */
    public static List<BacktestMonthlyStat> monthly(Long backtestId, List<DayReport> days, List<NiftyTradeLog> trades) {
        TreeMap<YearMonth, List<NiftyTradeLog>> byMonth = new TreeMap<>();
        days.forEach(d -> byMonth.computeIfAbsent(YearMonth.from(d.date()), m -> new ArrayList<>()));
        trades.stream()
                .sorted(Comparator.comparing(NiftyTradeLog::getEntryTimestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(t -> byMonth.computeIfAbsent(YearMonth.from(t.getTradeDate()), m -> new ArrayList<>()).add(t));

        List<BacktestMonthlyStat> rows = new ArrayList<>();
        double cumulative = 0;
        for (Map.Entry<YearMonth, List<NiftyTradeLog>> e : byMonth.entrySet()) {
            List<NiftyTradeLog> ts = e.getValue();
            int wins = 0, target = 0, stop = 0, failed = 0, eod = 0;
            double net = 0, charges = 0, winSum = 0, lossSum = 0, best = 0, worst = 0, equity = 0, peak = 0, dd = 0;
            boolean first = true;
            for (NiftyTradeLog t : ts) {
                double p = t.getEstimatedPnl();
                net += p;
                charges += t.getCharges() == null ? 0 : t.getCharges();
                if (p > 0) { wins++; winSum += p; } else lossSum += p;
                best = first ? p : Math.max(best, p);
                worst = first ? p : Math.min(worst, p);
                first = false;
                switch (String.valueOf(t.getExitReason())) {
                    case "TARGET_HIT"      -> target++;
                    case "SL_HIT"          -> stop++;
                    case "FAILED_BREAKOUT" -> failed++;
                    case "EOD_SQUAREOFF"   -> eod++;
                    default -> { }
                }
                equity += p;
                peak = Math.max(peak, equity);
                dd = Math.min(dd, equity - peak);
            }
            int losses = ts.size() - wins;
            cumulative += net;
            rows.add(new BacktestMonthlyStat(backtestId, e.getKey().toString(), ts.size(), wins, losses,
                    net + charges, charges, net, wins == 0 ? 0 : winSum / wins, losses == 0 ? 0 : lossSum / losses,
                    best, worst, target, stop, failed, eod, dd, cumulative));
        }
        return rows;
    }
}
