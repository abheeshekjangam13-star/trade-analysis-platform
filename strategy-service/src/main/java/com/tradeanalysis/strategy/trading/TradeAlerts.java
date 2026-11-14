package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.strategy.notify.PushService;
import com.tradeanalysis.strategy.notify.PushService.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What the phone alerts say and when they go out: orders placed or refused, positions closed (target, stop loss, day
 * loss limit, kill switch), market open, and the morning Kite login reminder. Only users who turned alerts on get
 * them; sending happens in the background ({@link PushService}), and nothing here ever breaks trading.
 */
@Component
public class TradeAlerts {

    private static final Logger log = LoggerFactory.getLogger(TradeAlerts.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Trading alerts are stale after the session: let the push service drop them if the phone is off till then. */
    private static final int TRADE_TTL = 4 * 3600;

    private final PushService push;
    private final JdbcTemplate jdbc;
    private final KiteAccounts kite;
    private final TradingDays days;
    private final StrategyLiveConfigRepository configs;
    private final Clock clock;

    public TradeAlerts(PushService push, JdbcTemplate jdbc, KiteAccounts kite, TradingDays days,
                       StrategyLiveConfigRepository configs, Clock clock) {
        this.push = push; this.jdbc = jdbc; this.kite = kite; this.days = days; this.configs = configs; this.clock = clock;
    }

    // ---- orders and exits -----------------------------------------------------------------------------------------

    /**
     * After {@link OrderService#place}: entries and manual orders say "placed"/"filled", refusals say why. Strategy
     * exits are announced by {@link #positionClosed} instead (with the reason and P&L), unless the exit was refused.
     */
    public void orderPlaced(TradeOrder o, boolean reducing) {
        try {
            if (o == null || o.getUserId() == null) return;
            boolean refused = o.getStatus() == TradeOrder.Status.REJECTED;
            if (reducing && o.getStrategyId() != null && !refused) return;
            String what = o.getTransactionType() + " " + o.getQuantity() + " × " + o.getTradingsymbol()
                    + (o.getAveragePrice() == null ? "" : " @ " + rupees(o.getAveragePrice()));
            String title = refused ? (reducing ? "⚠️ Exit order refused" : "Order refused")
                    : o.getMode() == TradingMode.PAPER ? (reducing ? "Paper exit filled" : "Paper order filled")
                    : (reducing ? "Exit order sent to Kite" : "Order sent to Kite");
            String body = what + " · " + o.getMode() + strategySuffix(o.getStrategyId())
                    + (refused && o.getMessage() != null ? "\n" + o.getMessage() : "");
            push.notify(o.getUserId(), new Alert(title, body, "/trading", "order-" + o.getId(), TRADE_TTL));
        } catch (RuntimeException e) {
            log.warn("ALERT order {} not sent: {}", o == null ? null : o.getId(), e.getMessage());
        }
    }

    /** After a strategy position is closed: target hit, stop loss hit, day loss limit, kill switch, stopped, … */
    public void positionClosed(LivePosition p) {
        try {
            if (p == null || p.getUserId() == null) return;
            String reason = p.getExitReason();
            String title = switch (reason == null ? "" : reason) {
                case "TARGET_HIT" -> "🎯 Target hit";
                case "SL_HIT" -> "Stop loss hit";
                case "MAX_DAY_LOSS" -> "Day loss limit reached: position closed";
                case "KILL_SWITCH" -> "Kill switch: position closed";
                case "ENTRY_NOT_FILLED" -> "Entry not filled: cancelled";
                default -> "Position closed: " + StrategyStatusService.readable(reason);
            };
            StringBuilder body = new StringBuilder(strategyName(p.getStrategyId()));
            if (!"ENTRY_NOT_FILLED".equals(reason)) body.append(" · SELL ").append(p.getQuantity()).append(" × ").append(p.getTradingsymbol());
            body.append(" · ").append(p.getMode());
            if (p.getPnl() != null) body.append("\nP&L ").append(signedRupees(p.getPnl()))
                    .append(p.getMode() == TradingMode.LIVE && p.getExitPrice() == null ? " (estimate until the fill comes in)" : "");
            push.notify(p.getUserId(), new Alert(title, body.toString(), "/trading", "position-" + p.getId(), TRADE_TTL));
        } catch (RuntimeException e) {
            log.warn("ALERT position {} not sent: {}", p == null ? null : p.getId(), e.getMessage());
        }
    }

    // ---- market open and Kite login -------------------------------------------------------------------------------

    /**
     * 09:15 IST on NSE trading days (weekdays not on the holiday list): tells every subscribed user the market is open
     * and how many of their strategies are running.
     */
    @Scheduled(cron = "${push.alerts.market-open-cron:0 15 9 * * MON-FRI}", zone = "Asia/Kolkata")
    public void marketOpen() {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        if (!tradingDay(today, "market open")) return;
        List<Long> users = push.subscribedUsers();
        if (users.isEmpty()) return;
        Map<Long, List<StrategyLiveConfig>> running = configs.findByModeNot(TradingMode.OFF).stream()
                .collect(Collectors.groupingBy(StrategyLiveConfig::getUserId));
        for (Long u : users) push.notify(u, marketOpenAlert(today, running.getOrDefault(u, List.of())));
    }

    static Alert marketOpenAlert(LocalDate today, List<StrategyLiveConfig> mine) {
        long live = mine.stream().filter(c -> c.getMode() == TradingMode.LIVE).count();
        String body = mine.isEmpty()
                ? "NSE is open for trading. None of your strategies is switched on today."
                : "NSE is open for trading. " + mine.size() + " of your strategies " + (mine.size() == 1 ? "is" : "are")
                  + " running (" + live + " live, " + (mine.size() - live) + " paper); entries from 9:20.";
        return new Alert("Market is open", body, "/", "market-open-" + today, 2 * 3600);
    }

    /** Whether to send today's morning alerts: NSE trading day; warns once a day when the holiday list is out of date. */
    private boolean tradingDay(LocalDate today, String what) {
        if (!days.coversYear(today.getYear()))
            log.warn("TRADING_DAYS no NSE holidays listed for {}: add this year's dates to trading.market-holidays", today.getYear());
        if (days.isTradingDay(today)) return true;
        log.info("ALERT {} skipped: {} is an NSE holiday", what, today);
        return false;
    }

    /**
     * 08:45 IST on NSE trading days: users whose own Kite account is set up but not logged in today are reminded to log in
     * (Kite logins end every morning), so their strategies can trade from 9:20.
     */
    @Scheduled(cron = "${push.alerts.kite-login-cron:0 45 8 * * MON-FRI}", zone = "Asia/Kolkata")
    public void kiteLoginReminder() {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        if (!tradingDay(today, "Kite reminder")) return;
        for (Long u : push.subscribedUsers()) {
            try {
                KiteAccounts.Status s = kite.status(u);
                if (!s.configured() || s.connected()) continue;
                push.notify(u, new Alert("Log in to Kite for today",
                        "Kite logins end every morning. Log in before 9:20 so your strategies can trade and you can see your positions.",
                        "/account#kite", "kite-login-" + today, 3 * 3600));
            } catch (RuntimeException e) {
                log.warn("ALERT Kite reminder for user {} skipped: {}", u, e.getMessage());
            }
        }
    }

    // ---- wording --------------------------------------------------------------------------------------------------

    private String strategySuffix(Long strategyId) { return strategyId == null ? " · manual" : " · " + strategyName(strategyId); }

    private String strategyName(Long strategyId) {
        if (strategyId == null) return "Manual";
        try {
            List<String> n = jdbc.queryForList("SELECT name FROM strategy WHERE id = ?", String.class, strategyId);
            return n.isEmpty() || n.get(0) == null ? "Strategy #" + strategyId : n.get(0);
        } catch (RuntimeException e) {
            return "Strategy #" + strategyId;
        }
    }

    private static String rupees(double v) { return String.format(Locale.ENGLISH, "₹%,.2f", v); }

    private static String signedRupees(double v) { return (v >= 0 ? "+" : "−") + rupees(Math.abs(v)); }
}
