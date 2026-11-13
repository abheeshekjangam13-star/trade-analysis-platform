package com.tradeanalysis.strategy.service;

import com.tradeanalysis.strategy.model.NiftyTradeLog;
import com.tradeanalysis.strategy.report.BacktestDailyReport;
import com.tradeanalysis.strategy.report.BacktestMonthlyStat;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a backtest's trades and daily/monthly report rows in batches. Saving them through JPA was one INSERT round
 * trip per row (their ids are database identities, which Hibernate can't batch): a year's backtest took ~0.6 s just
 * to save. Joins the caller's transaction. The rows get no ids back: everything reads them from the database later.
 */
public class BacktestRows {

    private static final String TRADE_SQL = "INSERT INTO nifty_trade_log (backtest_result_id, strategy_id, symbol, trade_date, "
            + "trade_number, breakout_type, reference_high, reference_low, reference_timestamp, entry_timestamp, entry_spot_price, "
            + "option_type, strike_price, lots, requested_lots, lot_size, initial_stop_loss, final_stop_loss, gap_percent, charges, "
            + "exit_timestamp, exit_spot_price, exit_reason, skip_reason, estimated_pnl, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final int[] TRADE_TYPES = {Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.DATE,
            Types.INTEGER, Types.VARCHAR, Types.DOUBLE, Types.DOUBLE, Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE, Types.DOUBLE,
            Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.DOUBLE, Types.DOUBLE, Types.DOUBLE, Types.DOUBLE,
            Types.TIMESTAMP_WITH_TIMEZONE, Types.DOUBLE, Types.VARCHAR, Types.VARCHAR, Types.DOUBLE, Types.TIMESTAMP_WITH_TIMEZONE};

    private static final String DAILY_SQL = "INSERT INTO backtest_daily_report (backtest_result_id, trade_date, day_of_week, "
            + "gap_percent, status, reason, trades, net_pnl) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final int[] DAILY_TYPES = {Types.BIGINT, Types.DATE, Types.VARCHAR, Types.DOUBLE, Types.VARCHAR, Types.VARCHAR,
            Types.INTEGER, Types.DOUBLE};

    private static final String MONTHLY_SQL = "INSERT INTO backtest_monthly_stat (backtest_result_id, period_month, trades, wins, "
            + "losses, gross_pnl, charges, net_pnl, avg_win, avg_loss, best_trade, worst_trade, target_hits, stop_exits, "
            + "failed_breakout_exits, eod_exits, max_drawdown, cumulative_pnl) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final int[] MONTHLY_TYPES = {Types.BIGINT, Types.VARCHAR, Types.INTEGER, Types.INTEGER,
            Types.INTEGER, Types.DOUBLE, Types.DOUBLE, Types.DOUBLE, Types.DOUBLE, Types.DOUBLE, Types.DOUBLE, Types.DOUBLE, Types.INTEGER, Types.INTEGER,
            Types.INTEGER, Types.INTEGER, Types.DOUBLE, Types.DOUBLE};

    private final JdbcTemplate jdbc;

    public BacktestRows(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insertTrades(List<NiftyTradeLog> trades) {
        List<Object[]> rows = new ArrayList<>(trades.size());
        for (NiftyTradeLog t : trades) rows.add(new Object[]{
                t.getBacktestResultId(), t.getStrategyId(), t.getSymbol(), t.getTradeDate(),
                t.getTradeNumber(), t.getBreakoutType(), t.getReferenceHigh(), t.getReferenceLow(), at(t.getReferenceTimestamp()),
                at(t.getEntryTimestamp()), t.getEntrySpotPrice(),
                t.getOptionType(), t.getStrikePrice(), t.getLots(), t.getRequestedLots(), t.getLotSize(), t.getInitialStopLoss(),
                t.getFinalStopLoss(), t.getGapPercent(), t.getCharges(),
                at(t.getExitTimestamp()), t.getExitSpotPrice(), t.getExitReason(), t.getSkipReason(), t.getEstimatedPnl(),
                at(t.getCreatedAt() == null ? Instant.now() : t.getCreatedAt())});
        if (!rows.isEmpty()) jdbc.batchUpdate(TRADE_SQL, rows, TRADE_TYPES);
    }

    public void insertDaily(List<BacktestDailyReport> days) {
        List<Object[]> rows = new ArrayList<>(days.size());
        for (BacktestDailyReport d : days) rows.add(new Object[]{
                d.getBacktestResultId(), d.getTradeDate(), d.getDayOfWeek(), d.getGapPercent(), d.getStatus(), d.getReason(),
                d.getTrades(), d.getNetPnl()});
        if (!rows.isEmpty()) jdbc.batchUpdate(DAILY_SQL, rows, DAILY_TYPES);
    }

    public void insertMonthly(List<BacktestMonthlyStat> months) {
        List<Object[]> rows = new ArrayList<>(months.size());
        for (BacktestMonthlyStat m : months) rows.add(new Object[]{
                m.getBacktestResultId(), m.getPeriodMonth(), m.getTrades(), m.getWins(),
                m.getLosses(), m.getGrossPnl(), m.getCharges(), m.getNetPnl(), m.getAvgWin(), m.getAvgLoss(), m.getBestTrade(),
                m.getWorstTrade(), m.getTargetHits(), m.getStopExits(),
                m.getFailedBreakoutExits(), m.getEodExits(), m.getMaxDrawdown(), m.getCumulativePnl()});
        if (!rows.isEmpty()) jdbc.batchUpdate(MONTHLY_SQL, rows, MONTHLY_TYPES);
    }

    private static OffsetDateTime at(Instant i) { return i == null ? null : i.atOffset(ZoneOffset.UTC); }
}
