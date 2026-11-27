package com.tradeanalysis.strategy.portfolio;

import com.tradeanalysis.common.exception.NotFoundException;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.*;

/**
 * A portfolio report's own version of the trades it combined (table portfolio_trade): the lots the shared capital
 * could buy, the P&L at those lots, trades left out (CAPITAL) or kept out by the max day loss (MAX_DAY_LOSS), and
 * exits the max day loss forced. Kept apart from the backtests' trades, which stay as each backtest made them, so a
 * backtest's trades always add up to its stored result.
 */
public final class PortfolioTrades {

    private PortfolioTrades() {}

    private static final String INSERT = "INSERT INTO portfolio_trade (portfolio_report_id, trade_log_id, lots, estimated_pnl, "
            + "skip_reason, exit_timestamp, exit_spot_price, exit_reason, option_symbol, entry_premium, exit_premium, slippage, "
            + "entry_delay_minutes, entry_spot_price) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** Stores the report's version of every trade it combined: kept, left out for capital, and kept out by the max day loss. */
    public static void store(JdbcTemplate jdbc, Long reportId, List<NiftyTradeLog> kept, List<NiftyTradeLog> left,
                             List<NiftyTradeLog> blocked) {
        store(jdbc, reportId, kept, left, blocked, List.of());
    }

    /** As above, plus the trades the same-strike wait dropped (over before the delayed buy). */
    public static void store(JdbcTemplate jdbc, Long reportId, List<NiftyTradeLog> kept, List<NiftyTradeLog> left,
                             List<NiftyTradeLog> blocked, List<NiftyTradeLog> waited) {
        List<Object[]> rows = new ArrayList<>();
        for (NiftyTradeLog t : kept) rows.add(row(reportId, t, null));
        for (NiftyTradeLog t : left) {
            t.markSkipped("CAPITAL");
            rows.add(row(reportId, t, "CAPITAL"));
        }
        for (NiftyTradeLog t : blocked) rows.add(row(reportId, t, com.tradeanalysis.strategy.estimate.DayLossStop.REASON));
        for (NiftyTradeLog t : waited) rows.add(row(reportId, t, com.tradeanalysis.strategy.estimate.SameStrikeWait.REASON));
        if (!rows.isEmpty()) jdbc.batchUpdate(INSERT, rows);
    }

    private static Object[] row(Long reportId, NiftyTradeLog t, String skip) {
        return new Object[]{reportId, t.getId(), t.getLots(), t.getEstimatedPnl(), skip,
                t.getExitTimestamp() == null ? null : Timestamp.from(t.getExitTimestamp()),
                t.getExitSpotPrice(), t.getExitReason(), t.getOptionSymbol(), t.getEntryPremium(), t.getExitPremium(), t.getSlippage(),
                t.getEntryDelayMinutes(), t.getEntryDelayMinutes() == null ? null : t.getEntrySpotPrice()};
    }

    /**
     * These trades of one backtest as the report counted them. 404 when the report didn't combine that backtest. Trades
     * the report has no version of (none should exist) are returned as the backtest made them.
     */
    public static List<NiftyTradeLog> asCounted(JdbcTemplate jdbc, Long reportId, Long backtestId, List<NiftyTradeLog> trades) {
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_report_item WHERE portfolio_report_id = ? "
                + "AND backtest_result_id = ?", Integer.class, reportId, backtestId);
        if (used == null || used == 0) throw new NotFoundException("Report " + reportId + " has no backtest " + backtestId);
        Map<Long, NiftyTradeLog> byId = new HashMap<>();
        for (NiftyTradeLog t : trades) byId.put(t.getId(), t);
        jdbc.query("SELECT p.* FROM portfolio_trade p JOIN nifty_trade_log t ON t.id = p.trade_log_id "
                + "WHERE p.portfolio_report_id = ? AND t.backtest_result_id = ?", rs -> {
            NiftyTradeLog t = byId.get(rs.getLong("trade_log_id"));
            if (t == null) return;
            Timestamp exit = rs.getTimestamp("exit_timestamp");
            t.asCountedByPortfolio(rs.getInt("lots"), rs.getDouble("estimated_pnl"), rs.getString("skip_reason"),
                    exit == null ? null : exit.toInstant(), rs.getDouble("exit_spot_price"), rs.getString("exit_reason"),
                    rs.getString("option_symbol"), rs.getObject("entry_premium", Double.class),
                    rs.getObject("exit_premium", Double.class), rs.getObject("slippage", Double.class));
            t.asDelayedByPortfolio(rs.getObject("entry_delay_minutes", Integer.class), rs.getObject("entry_spot_price", Double.class));
        }, reportId, backtestId);
        return trades;
    }
}
