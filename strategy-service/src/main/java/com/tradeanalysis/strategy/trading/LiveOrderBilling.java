package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.strategy.notify.EmailService;
import com.tradeanalysis.strategy.user.AppUser;
import com.tradeanalysis.strategy.user.AppUserRepository;
import com.tradeanalysis.strategy.user.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Live order billing. A live-trading plan includes {@link Plan#includedLiveOrders()} live orders: when the plan starts or
 * renews (or an admin sets it again), the included orders are topped up to that number. They never stack, so setting a
 * plan several times can't give more than one allowance. Each new live order Kite accepts uses one; once none are left, each costs
 * {@code billing.extra-live-order-paise} (₹10) from the user's prepaid credits, bought with Razorpay. Exits, paper
 * orders and admins are never charged; an order Kite rejects is not charged (or is refunded when the rejection comes
 * later). When 70% of the latest allowance is used, the user is emailed once to add credits.
 *
 * Balances live in app_user columns only this class touches (through JDBC, with atomic updates), and every movement is
 * recorded in live_order_charge.
 */
@Service
public class LiveOrderBilling {

    private static final Logger log = LoggerFactory.getLogger(LiveOrderBilling.class);
    /** The share of the allowance used at which the user is told to add credits. */
    public static final double WARN_AT_USED = 0.7;

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EmailService emails;
    private final Clock clock;
    private final long pricePaise;

    public LiveOrderBilling(JdbcTemplate jdbc, AppUserRepository users, EmailService emails, Clock clock,
                            @Value("${billing.extra-live-order-paise:1000}") long pricePaise) {
        this.jdbc = jdbc; this.users = users; this.emails = emails; this.clock = clock; this.pricePaise = pricePaise;
    }

    /** What the user has: included orders left (of the latest allowance), credits, the price per extra order. */
    public record Balance(int liveOrdersLeft, int liveOrdersGranted, long creditPaise, long pricePaise,
                          int ordersFromCredits, boolean low, boolean exhausted) {}

    public record Movement(Long id, Long tradeOrderId, String source, int orders, long amountPaise, String note, Instant createdAt) {}

    public long pricePaise() { return pricePaise; }

    public Balance balance(Long userId) {
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT live_orders_left, live_orders_granted, credit_paise FROM app_user WHERE id = ?", userId);
        int left = ((Number) r.get("live_orders_left")).intValue();
        int granted = ((Number) r.get("live_orders_granted")).intValue();
        long credit = ((Number) r.get("credit_paise")).longValue();
        int fromCredits = pricePaise > 0 ? (int) (credit / pricePaise) : 0;
        return new Balance(left, granted, credit, pricePaise, fromCredits, low(left, granted), left == 0 && fromCredits == 0);
    }

    /** At least 70% of the allowance used (so 30% or less left). */
    static boolean low(int left, int granted) {
        return granted > 0 && left <= Math.floor(granted * (1 - WARN_AT_USED));
    }

    public List<Movement> movements(Long userId, int limit) {
        return jdbc.query("SELECT id, trade_order_id, source, orders, amount_paise, note, created_at FROM live_order_charge "
                        + "WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, i) -> new Movement(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getInt(4), rs.getLong(5),
                        rs.getString(6), rs.getTimestamp(7).toInstant()), userId, Math.max(1, Math.min(limit, 500)));
    }

    /** Why this user can't send another live order for lack of orders/credits, or null (admins: never). */
    public String refusal(Long userId) {
        if (userId == null || isAdmin(userId)) return null;
        Balance b = balance(userId);
        if (b.liveOrdersLeft() > 0 || b.creditPaise() >= pricePaise) return null;
        return "No live orders left: your plan's included live orders are used and your credits (" + rupees(b.creditPaise())
                + ") don't cover the next order (" + rupees(pricePaise) + "). Add credits on the Trading page";
    }

    /**
     * Charges one new live order Kite accepted: an included order if any are left, else the price from credits. Sends
     * the "70% used" email once per allowance.
     */
    @Transactional
    public void charge(Long userId, Long tradeOrderId) {
        if (userId == null || isAdmin(userId)) return;
        if (jdbc.update("UPDATE app_user SET live_orders_left = live_orders_left - 1 WHERE id = ? AND live_orders_left > 0", userId) == 1) {
            record(userId, tradeOrderId, "INCLUDED", -1, 0, null);
        } else if (jdbc.update("UPDATE app_user SET credit_paise = credit_paise - ? WHERE id = ? AND credit_paise >= ?", pricePaise, userId, pricePaise) == 1) {
            record(userId, tradeOrderId, "CREDITS", 0, -pricePaise, null);
        } else {
            // Checked before sending; only a race gets here. The order is already at Kite: record it, uncharged.
            log.warn("BILLING order {} of user {} sent with no orders or credits left", tradeOrderId, userId);
            record(userId, tradeOrderId, "UNPAID", 0, 0, "No orders or credits left when it was sent");
        }
        warnIfLow(userId);
    }

    /** Gives back the charge of an order Kite rejected after accepting it (once). */
    @Transactional
    public void refund(Long tradeOrderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, source, orders, amount_paise FROM live_order_charge WHERE trade_order_id = ? ORDER BY id", tradeOrderId);
        if (rows.isEmpty() || rows.stream().anyMatch(r -> "REFUND".equals(r.get("source")))) return;
        Map<String, Object> c = rows.get(0);
        Long userId = ((Number) c.get("user_id")).longValue();
        int orders = -((Number) c.get("orders")).intValue();
        long paise = -((Number) c.get("amount_paise")).longValue();
        if (orders == 0 && paise == 0) return;
        jdbc.update("UPDATE app_user SET live_orders_left = live_orders_left + ?, credit_paise = credit_paise + ? WHERE id = ?", orders, paise, userId);
        record(userId, tradeOrderId, "REFUND", orders, paise, "Rejected by Kite");
    }

    /**
     * Tops a live-trading plan's included orders up to {@link Plan#includedLiveOrders()} (when it starts or renews, or an
     * admin sets it): unused ones don't carry over and repeats don't stack. Nothing is recorded when already full.
     */
    @Transactional
    public void grant(Long userId, Plan plan, String why) {
        int n = plan == null ? 0 : plan.includedLiveOrders();
        if (userId == null || n <= 0) return;
        Integer left = jdbc.queryForObject("SELECT live_orders_left FROM app_user WHERE id = ? FOR UPDATE", Integer.class, userId);
        int added = left == null ? n : Math.max(0, n - left);
        if (added == 0) return;
        jdbc.update("UPDATE app_user SET live_orders_left = ?, live_orders_granted = ?, live_orders_warned = FALSE WHERE id = ?", n, n, userId);
        record(userId, null, "GRANT", added, 0, "Topped up to " + n + " live orders included with " + plan.label()
                + (why == null ? "" : " (" + why + ")"));
    }

    /** Adds credits (a Razorpay top-up) and/or included orders (an admin adjustment); negative values take away. */
    @Transactional
    public Balance add(Long userId, int orders, long paise, String source, String note) {
        if (orders == 0 && paise == 0) throw new TradingException("Nothing to add");
        Balance b = balance(userId);
        if (b.liveOrdersLeft() + orders < 0 || b.creditPaise() + paise < 0)
            throw new TradingException(HttpStatus.CONFLICT, "That would take the balance below zero");
        jdbc.update("UPDATE app_user SET live_orders_left = live_orders_left + ?, credit_paise = credit_paise + ?"
                + (orders > 0 ? ", live_orders_granted = live_orders_left + ?, live_orders_warned = FALSE" : "") + " WHERE id = ?",
                orders > 0 ? new Object[]{orders, paise, orders, userId} : new Object[]{orders, paise, userId});
        record(userId, null, source, orders, paise, note);
        return balance(userId);
    }

    private void warnIfLow(Long userId) {
        Balance b = balance(userId);
        if (!b.low()) return;
        if (jdbc.update("UPDATE app_user SET live_orders_warned = TRUE WHERE id = ? AND live_orders_warned = FALSE", userId) != 1) return;
        users.findById(userId).ifPresent(u -> emails.liveOrdersLow(u, b.liveOrdersLeft(), b.liveOrdersGranted(), b.creditPaise(), pricePaise));
        log.info("BILLING user {} has used {}% of {} included live orders: {} left, credits {}", userId,
                Math.round(WARN_AT_USED * 100), b.liveOrdersGranted(), b.liveOrdersLeft(), b.creditPaise());
    }

    private void record(Long userId, Long tradeOrderId, String source, int orders, long paise, String note) {
        jdbc.update("INSERT INTO live_order_charge (user_id, trade_order_id, source, orders, amount_paise, note, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, tradeOrderId, source, orders, paise, note == null ? null : note.length() > 200 ? note.substring(0, 200) : note,
                Timestamp.from(clock.instant()));
    }

    private boolean isAdmin(Long userId) {
        return users.findById(userId).map(AppUser::isAdmin).orElse(false);
    }

    static String rupees(long paise) {
        return paise % 100 == 0 ? "₹" + paise / 100 : String.format("₹%.2f", paise / 100.0);
    }
}
