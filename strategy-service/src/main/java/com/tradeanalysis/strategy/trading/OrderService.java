package com.tradeanalysis.strategy.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * The only way the platform sends orders. Validates, applies the safety rules and records every order (rejected ones
 * included) in trade_order. PAPER orders fill at once at the option's last traded price; LIVE orders go to the order
 * owner's own Kite account ({@link KiteAccounts#forUser}: admins without one use the platform account) and require
 * live trading to be on (an admin switches it on the Trading page; see {@link #liveEnabled}).
 *
 * Safety rules for orders that open or add risk (exits are always allowed, so positions can always be closed):
 * kill switch off, quantity ≤ max per order, orders today < max per day, today's realized loss < daily loss limit
 * (all per user), today's loss (realized + open positions) < the user's own max day loss, and for LIVE: the user may trade live (PREMIUM with their own Kite account, or an admin).
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Set<String> SIDES = Set.of("BUY", "SELL");
    static final Set<String> TYPES = Set.of("MARKET", "LIMIT", "SL", "SL-M");
    static final Set<String> PRODUCTS = Set.of("MIS", "NRML", "CNC");
    static final Set<String> EXCHANGES = Set.of("NFO", "NSE", "BSE", "BFO");
    private static final EnumSet<TradeOrder.Status> COUNTED = EnumSet.of(TradeOrder.Status.OPEN, TradeOrder.Status.COMPLETE);

    private final TradeOrderRepository orders;
    private final TradingControlRepository controls;
    private final LivePositionRepository positions;
    private final KiteApi kite;          // platform session: quotes for paper fills
    private final KiteAccounts accounts; // the order owner's own account: live orders
    private final Clock clock;
    private final com.tradeanalysis.strategy.user.AppUserRepository users;
    private final StrategyLiveConfigRepository configs;
    private final LiveOrderBilling billing;
    private final TradeAlerts alerts;

    public OrderService(TradeOrderRepository orders, TradingControlRepository controls, LivePositionRepository positions,
                        KiteApi kite, KiteAccounts accounts, Clock clock,
                        com.tradeanalysis.strategy.user.AppUserRepository users, StrategyLiveConfigRepository configs,
                        LiveOrderBilling billing, TradeAlerts alerts) {
        this.orders = orders; this.controls = controls; this.positions = positions; this.kite = kite; this.accounts = accounts;
        this.clock = clock; this.users = users; this.configs = configs; this.billing = billing; this.alerts = alerts;
    }

    /** Whether live trading is on for the platform: an admin switches it on the Trading page (off by default). */
    public boolean liveEnabled() { return controls.get().isLiveEnabled(); }

    /**
     * Why this user may not trade live while an admin restricts live trading to picked users, or null. Applies to
     * admins too (an admin without live access is refused); exits are never blocked by it.
     */
    public String accessRefusal(Long userId) {
        TradingControl c = controls.get();
        if (!c.isLiveRestricted()) return null;
        boolean allowed = userId != null && users.findById(userId).map(com.tradeanalysis.strategy.user.AppUser::isLiveAccess).orElse(false);
        return allowed ? null : c.getLiveRestrictedMessage();
    }

    public static final String LIVE_OFF = "Live trading is turned off by the admin (Trading page): no new live orders";

    public LocalDate today() { return LocalDate.now(clock.withZone(IST)); }

    /**
     * @param reducing true for orders that close an existing position (strategy exits, kill switch): they skip the
     *                 limits and the kill switch so a position can always be closed.
     * @return the recorded order; status REJECTED (with the reason in message) if a safety rule or the broker refused it
     */
    public TradeOrder place(OrderRequest r, TradingMode mode, String source, Long strategyId, Long userId, boolean reducing) {
        TradeOrder o = placeOrder(r, mode, source, strategyId, userId, reducing);
        alerts.orderPlaced(o, reducing);   // phone alert (background; never throws)
        return o;
    }

    private TradeOrder placeOrder(OrderRequest r, TradingMode mode, String source, Long strategyId, Long userId, boolean reducing) {
        validate(r, mode);
        TradeOrder order = orders.save(new TradeOrder(today(), mode, source, strategyId, r, userId));
        String refusal = reducing ? null : refusal(r, mode, userId);
        if (refusal == null && !reducing && mode == TradingMode.LIVE) refusal = planRefusal(r, userId, strategyId);
        // Live trading needs credit first: included orders or credits for the next order (exits are never charged).
        if (refusal == null && !reducing && mode == TradingMode.LIVE) refusal = billing.refusal(userId);
        // Off stops new live risk only: orders that close a position (exits, kill switch) still go out.
        if (refusal == null && !reducing && mode == TradingMode.LIVE && !liveEnabled()) refusal = LIVE_OFF;
        if (refusal != null) {
            order.reject(refusal);
            log.warn("ORDER_REFUSED id={} {} {} {} x{}: {}", order.getId(), mode, r.transactionType(), r.tradingsymbol(), r.quantity(), refusal);
            return orders.save(order);
        }
        if (mode == TradingMode.PAPER) {
            Double px = paperPrice(r);
            order.filled(r.quantity(), px, px == null ? "Paper fill (no price: Kite not connected; P&L uses the strategy estimate)"
                                                      : "Paper fill at last traded price");
        } else {
            try {
                String id = accounts.forUser(userId).placeOrder(new KiteApi.PlaceOrder(r.exchange(), r.tradingsymbol(), r.transactionType(), r.quantity(),
                        r.product(), r.orderType(), r.price(), r.triggerPrice(), "TAP" + (strategyId == null ? "M" : strategyId)));
                order.placed(id, "Sent to Kite");
                if (!reducing) {
                    try { billing.charge(userId, order.getId()); }
                    catch (RuntimeException e) { log.error("BILLING charge failed order={} user={}: {}", order.getId(), userId, e.getMessage(), e); }
                }
                log.info("ORDER_LIVE id={} kite={} {} {} x{}", order.getId(), id, r.transactionType(), r.tradingsymbol(), r.quantity());
            } catch (RuntimeException e) {
                order.reject(e.getMessage());
            }
        }
        return orders.save(order);
    }

    public TradeOrder cancel(Long id) {
        TradeOrder o = orders.findById(id).orElseThrow(() -> new TradingException(HttpStatus.NOT_FOUND, "Order not found: " + id));
        if (o.getStatus() != TradeOrder.Status.OPEN) throw new TradingException("Order " + id + " is already " + o.getStatus());
        if (o.getMode() == TradingMode.LIVE && o.getBrokerOrderId() != null) accounts.forUser(o.getUserId()).cancelOrder(o.getBrokerOrderId());
        o.update(TradeOrder.Status.CANCELLED, o.getFilledQuantity(), null, "Cancelled");
        return orders.save(o);
    }

    /** Refreshes open live orders from Kite (fills, rejections). */
    public void syncLiveOrders() {
        for (TradeOrder o : orders.findByModeAndStatus(TradingMode.LIVE, TradeOrder.Status.OPEN)) {
            if (o.getBrokerOrderId() == null) continue;
            try {
                KiteApi.BrokerOrder b = accounts.forUser(o.getUserId()).order(o.getBrokerOrderId());
                o.update(mapStatus(b.status()), b.filledQuantity(), b.averagePrice(), b.statusMessage());
                orders.save(o);
                if (o.getStatus() == TradeOrder.Status.REJECTED) billing.refund(o.getId());   // Kite refused it after all: not charged
            } catch (RuntimeException e) {
                log.warn("ORDER_SYNC_FAILED id={} kite={}: {}", o.getId(), o.getBrokerOrderId(), e.getMessage());
            }
        }
    }

    static TradeOrder.Status mapStatus(String kiteStatus) {
        return switch (String.valueOf(kiteStatus)) {
            case "COMPLETE" -> TradeOrder.Status.COMPLETE;
            case "REJECTED" -> TradeOrder.Status.REJECTED;
            case "CANCELLED" -> TradeOrder.Status.CANCELLED;
            default -> TradeOrder.Status.OPEN;   // OPEN, TRIGGER PENDING, *_PENDING, PUT ORDER REQ RECEIVED, …
        };
    }

    /** Orders today (open or filled) in this mode, for everyone. */
    public long ordersToday(TradingMode mode) {
        return orders.countByTradeDateAndModeAndStatusIn(today(), mode, COUNTED);
    }

    /** Orders today in this mode for one user (null = everyone). */
    public long ordersToday(TradingMode mode, Long userId) {
        return userId == null ? ordersToday(mode) : orders.countByTradeDateAndModeAndUserIdAndStatusIn(today(), mode, userId, COUNTED);
    }

    public double realizedToday(TradingMode mode) { return realizedToday(mode, null); }

    /**
     * Today's realized P&L in this mode: closed strategy positions plus manual round trips (sell value − buy value of
     * the quantity both bought and sold, per symbol).
     */
    public double realizedToday(TradingMode mode, Long userId) {
        double strategies = positions.findByTradeDateAndModeAndStatus(today(), mode, LivePosition.Status.CLOSED).stream()
                .filter(p -> userId == null || userId.equals(p.getUserId()))
                .mapToDouble(p -> p.getPnl() == null ? 0 : p.getPnl()).sum();
        Map<String, double[]> bySymbol = new HashMap<>();   // buyQty, buyValue, sellQty, sellValue
        for (TradeOrder o : orders.findByTradeDateAndMode(today(), mode)) {
            if (!"MANUAL".equals(o.getSource()) || o.getStatus() != TradeOrder.Status.COMPLETE || o.getAveragePrice() == null) continue;
            if (userId != null && !userId.equals(o.getUserId())) continue;
            double[] a = bySymbol.computeIfAbsent(o.getTradingsymbol(), k -> new double[4]);
            int i = "BUY".equals(o.getTransactionType()) ? 0 : 2;
            a[i] += o.getFilledQuantity();
            a[i + 1] += o.getFilledQuantity() * o.getAveragePrice();
        }
        double manual = 0;
        for (double[] a : bySymbol.values()) {
            double closed = Math.min(a[0], a[2]);
            if (closed > 0) manual += closed * (a[3] / a[2] - a[1] / a[0]);
        }
        return strategies + manual;
    }

    /**
     * Today's P&L on the user's open strategy positions in this mode, at the last traded prices (positions without an
     * entry price or a quote count as 0).
     */
    public double unrealizedToday(TradingMode mode, Long userId) {
        List<LivePosition> open = positions.findByTradeDateAndModeAndStatus(today(), mode, LivePosition.Status.OPEN).stream()
                .filter(p -> userId == null || userId.equals(p.getUserId()))
                .filter(p -> p.getTradingsymbol() != null && p.getEntryPrice() != null && p.getQuantity() > 0).toList();
        if (open.isEmpty()) return 0;
        Map<String, Double> ltp;
        try {
            ltp = kite.ltp(open.stream().map(p -> "NFO:" + p.getTradingsymbol()).distinct().toList());
        } catch (RuntimeException e) {
            log.debug("No LTP for open positions: {}", e.getMessage());
            return 0;
        }
        double sum = 0;
        for (LivePosition p : open) {
            Double px = ltp == null ? null : ltp.get("NFO:" + p.getTradingsymbol());
            if (px != null && px > 0) sum += (px - p.getEntryPrice()) * p.getQuantity();
        }
        return sum;
    }

    /** Today's P&L for the user's own day-loss limit: realized plus open strategy positions. */
    public double dayPnl(TradingMode mode, Long userId) {
        return realizedToday(mode, userId) + unrealizedToday(mode, userId);
    }

    /** The user's own max day loss, or null when they haven't set one. */
    public Double maxDayLoss(Long userId) {
        return userId == null ? null : users.findById(userId).map(com.tradeanalysis.strategy.user.AppUser::getMaxDayLoss).orElse(null);
    }

    /**
     * Why the user may not open new risk today because of their own max day loss (realized + open positions, all
     * strategies together), or null when they may.
     */
    public String dayLossRefusal(TradingMode mode, Long userId) {
        Double limit = maxDayLoss(userId);
        if (limit == null || limit <= 0) return null;
        double pnl = dayPnl(mode, userId);
        return pnl <= -limit ? String.format("Your max day loss is reached (today %.0f, limit %.0f): no new entries today", pnl, limit) : null;
    }

    /**
     * Why the user's plan refuses this new live order, or null: more lots than the plan allows per order (from the
     * option's lot size), or, for a strategy's entry, a strategy beyond the plan's number of live strategies (the
     * user's LIVE strategies in id order; e.g. after a downgrade, the later ones stop entering). Admins: no limit.
     */
    String planRefusal(OrderRequest r, Long userId, Long strategyId) {
        if (userId == null) return null;
        com.tradeanalysis.strategy.user.AppUser u = users.findById(userId).orElse(null);
        if (u == null || u.isAdmin()) return null;
        com.tradeanalysis.strategy.user.Plan plan = u.effectivePlan(clock.instant());
        int lotSize = lotSizeOf(r.tradingsymbol());
        if (lotSize > 0 && r.quantity() > plan.maxLiveLots() * lotSize)
            return "Your " + plan.label() + " plan trades live up to " + plan.maxLiveLots() + " lot" + (plan.maxLiveLots() == 1 ? "" : "s")
                    + " per order (" + plan.maxLiveLots() * lotSize + " quantity); this order is " + r.quantity() + ". Lower the lots or upgrade.";
        if (strategyId != null) {
            List<Long> live = configs.findByUserId(userId).stream().filter(c -> c.getMode() == TradingMode.LIVE)
                    .map(StrategyLiveConfig::getStrategyId).sorted().toList();
            int at = live.indexOf(strategyId);
            if (at >= plan.maxLiveStrategies())
                return "Your " + plan.label() + " plan allows " + plan.maxLiveStrategies() + " strategies trading live at once and you have "
                        + live.size() + " set to LIVE: switch some off or upgrade";
        }
        return null;
    }

    /** Contracts per lot of an NFO option symbol (NIFTY…, BANKNIFTY…), or 0 when unknown. */
    static int lotSizeOf(String tradingsymbol) {
        if (tradingsymbol == null) return 0;
        String s = tradingsymbol.toUpperCase(java.util.Locale.ROOT);
        if (s.startsWith("BANKNIFTY")) return com.tradeanalysis.common.market.Underlying.BANKNIFTY.defaultLotSize;
        if (s.startsWith("NIFTY")) return com.tradeanalysis.common.market.Underlying.NIFTY.defaultLotSize;
        return 0;
    }

    /** Limits count each user's own orders and P&L (each user trades live on their own Kite account). */
    private String refusal(OrderRequest r, TradingMode mode, Long userId) {
        Long who = userId;
        TradingControl c = controls.get();
        if (c.isKillSwitch()) return "Kill switch is on: no new orders";
        if (mode == TradingMode.LIVE) {
            String access = accessRefusal(userId);
            if (access != null) return access;
            String live = accounts.liveRefusal(userId);
            if (live != null) return live;
        }
        if (r.quantity() > c.getMaxQuantityPerOrder())
            return "Quantity " + r.quantity() + " is above the limit of " + c.getMaxQuantityPerOrder() + " per order";
        if (ordersToday(mode, who) >= c.getMaxOrdersPerDay()) return "Daily limit of " + c.getMaxOrdersPerDay() + " " + mode + " orders reached";
        double realized = realizedToday(mode, who);
        if (realized <= -c.getDailyLossLimit())
            return String.format("Daily loss limit reached (realized %.0f, limit %.0f)", realized, c.getDailyLossLimit());
        return dayLossRefusal(mode, who);
    }

    private Double paperPrice(OrderRequest r) {
        try {
            Double ltp = kite.ltp(List.of(r.exchange() + ":" + r.tradingsymbol())).get(r.exchange() + ":" + r.tradingsymbol());
            if (ltp != null && ltp > 0) return ltp;
        } catch (RuntimeException e) {
            log.debug("No LTP for paper fill of {}: {}", r.tradingsymbol(), e.getMessage());
        }
        return r.price();
    }

    static void validate(OrderRequest r, TradingMode mode) {
        if (mode == null || mode == TradingMode.OFF) throw new TradingException("Mode must be PAPER or LIVE");
        if (r.tradingsymbol() == null || r.tradingsymbol().isBlank()) throw new TradingException("Missing tradingsymbol");
        if (!EXCHANGES.contains(r.exchange())) throw new TradingException("Exchange must be one of " + EXCHANGES);
        if (!SIDES.contains(r.transactionType())) throw new TradingException("transactionType must be BUY or SELL");
        if (r.quantity() <= 0) throw new TradingException("Quantity must be positive");
        if (!PRODUCTS.contains(r.product())) throw new TradingException("Product must be one of " + PRODUCTS);
        if (!TYPES.contains(r.orderType())) throw new TradingException("orderType must be one of " + TYPES);
        if (("LIMIT".equals(r.orderType()) || "SL".equals(r.orderType())) && (r.price() == null || r.price() <= 0))
            throw new TradingException(r.orderType() + " orders need a price");
        if (("SL".equals(r.orderType()) || "SL-M".equals(r.orderType())) && (r.triggerPrice() == null || r.triggerPrice() <= 0))
            throw new TradingException(r.orderType() + " orders need a trigger price");
    }
}
