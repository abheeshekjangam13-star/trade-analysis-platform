package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.common.market.Underlying;
import com.tradeanalysis.strategy.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/trading")
@Tag(name = "Trading", description = "Kite account, orders (paper/live), strategy live modes, kill switch")
public class TradingController {

    public record ModeRequest(TradingMode mode, String product) {}
    public record LiveRequest(Boolean enabled) {}
    public record ControlsRequest(Integer maxOrdersPerDay, Integer maxQuantityPerOrder, Double dailyLossLimit) {}
    public record ManualOrder(String exchange, String tradingsymbol, String transactionType, Integer quantity, String product,
                              String orderType, Double price, Double triggerPrice, TradingMode mode, Boolean confirm) {}

    private final TradingService trading;
    private final OrderService orders;
    private final TradeOrderRepository orderRepo;
    private final LivePositionRepository positionRepo;
    private final BrokerAccounts accounts;
    private final KiteApi platform;
    private final NiftyOptions options;
    private final ObjectProvider<LiveStrategyRunner> runner;
    private final Clock clock;
    private final QuoteCache quoteCache;
    private final com.tradeanalysis.strategy.compliance.ComplianceAudit audit;
    private final ObjectProvider<TradingDays> tradingDays;

    public TradingController(TradingService trading, OrderService orders, TradeOrderRepository orderRepo,
                             LivePositionRepository positionRepo, BrokerAccounts accounts, KiteApi platform, NiftyOptions options,
                             ObjectProvider<LiveStrategyRunner> runner, Clock clock, QuoteCache quoteCache,
                             com.tradeanalysis.strategy.compliance.ComplianceAudit audit, ObjectProvider<TradingDays> tradingDays) {
        this.audit = audit;
        this.tradingDays = tradingDays;
        this.quoteCache = quoteCache;
        this.trading = trading; this.orders = orders; this.orderRepo = orderRepo; this.positionRepo = positionRepo;
        this.accounts = accounts; this.platform = platform; this.options = options; this.runner = runner; this.clock = clock;
    }

    @GetMapping("/status")
    @Operation(summary = "Your Kite connection, live switch, controls and today's order counts / realized P&L")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> k = new LinkedHashMap<>();
        Long me = CurrentUser.id().orElse(null);
        k.put("ownAccount", accounts.hasOwnAccount(me));   // false: admins use the platform account
        k.put("broker", accounts.brokerOf(me).name());      // KITE or ANGEL_ONE (the "kite" key is kept for older UIs)
        k.put("brokerLabel", accounts.label(me));
        try {
            KiteApi.Profile p = accounts.forUser(me).profile();
            k.put("connected", true); k.put("userId", p.userId()); k.put("userName", p.userName());
        } catch (RuntimeException e) {
            k.put("connected", false); k.put("message", e.getMessage());
        }
        out.put("kite", k);
        boolean market;   // the platform session (quotes, option contracts, paper fills) works for everyone
        try { platform.profile(); market = true; } catch (RuntimeException e) { market = false; }
        out.put("marketData", Map.of("connected", market));
        out.put("liveTradingEnabled", orders.liveEnabled());
        out.put("liveAllowed", trading.liveAllowed());
        out.put("liveBlockedMessage", me == null ? null : trading.liveRefusal(me));   // why the caller can't trade live, or null
        out.put("liveAccessMessage", orders.accessRefusal(me));   // the admin's message while live is restricted and the caller isn't picked, or null
        out.put("runnerEnabled", runner.getIfAvailable() != null);
        out.put("marketOpen", LiveStrategyRunner.exchangeOpen(clock.instant(), tradingDays.getIfAvailable()));   // NSE hours, not the runner's 09:20 start
        out.put("nextMarketOpen", LiveStrategyRunner.nextExchangeOpen(clock.instant(), tradingDays.getIfAvailable())
                .toOffsetDateTime().toString());   // e.g. 2026-11-18T09:15+05:30, for "resume at 9:15 AM on Wed, 18 Nov"
        out.put("controls", trading.controls());
        out.put("maxDayLoss", trading.currentMaxDayLoss());   // the caller's own, for all their strategies together
        out.put("pairStopPoints", trading.currentPairStopPoints());   // the caller's own pair stop (null = off)
        Long who = trading.isAdmin() ? null : me;   // non-admins: their own numbers
        for (TradingMode m : who == null || trading.liveAllowed() ? List.of(TradingMode.PAPER, TradingMode.LIVE) : List.of(TradingMode.PAPER))
            out.put(m.name().toLowerCase(), Map.of("ordersToday", orders.ordersToday(m, who), "realizedToday", orders.realizedToday(m, who),
                    "dayPnl", me == null ? 0.0 : orders.dayPnl(m, me)));
        return out;
    }

    /**
     * Today's special exchange session (e.g. Muhurat trading), for the banner: null on normal days. {@code open}
     * says whether the market counts as open right now.
     */
    @GetMapping("/session")
    public Map<String, Object> session() {
        java.time.Instant now = clock.instant();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("open", LiveStrategyRunner.exchangeOpen(now, tradingDays.getIfAvailable()));
        out.put("special", com.tradeanalysis.common.market.SpecialSessions.on(now.atZone(com.tradeanalysis.common.market.SpecialSessions.IST).toLocalDate())
                .map(s -> Map.of("name", s.name(), "date", s.date().toString(), "preOpen", s.preOpen().toString(),
                        "tradingFrom", s.tradingFrom().toString(), "tradingTo", s.tradingTo().toString(), "close", s.close().toString()))
                .orElse(null));
        return out;
    }

    public record DayLossRequest(Double maxDayLoss) {}

    @GetMapping("/day-loss")
    @Operation(summary = "Your own max day loss (null = not set yet)")
    public Map<String, Object> dayLoss() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxDayLoss", trading.currentMaxDayLoss());
        return out;
    }

    @PutMapping("/day-loss")
    @Operation(summary = "Set your own max day loss (₹): once today's loss across all your strategies (realized + open "
            + "positions, paper and live counted apart) reaches it, your open strategy positions are closed and no new "
            + "entries are taken that day. Needed before switching a strategy to PAPER or LIVE")
    public Map<String, Object> setDayLoss(@RequestBody DayLossRequest r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxDayLoss", trading.setMaxDayLoss(r == null ? null : r.maxDayLoss()));
        return out;
    }

    public record PairStopRequest(Integer points) {}

    @GetMapping("/pair-stop")
    @Operation(summary = "Your own pair stop (null = off)")
    public Map<String, Object> pairStop() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", trading.currentPairStopPoints());
        return out;
    }

    @PutMapping("/pair-stop")
    @Operation(summary = "Set your own pair stop: when two of your strategies bought the same option on the same signal "
            + "minute and one is stopped out at a loss, the other is closed too: at once (0) or once the index moves this "
            + "many more points against it. Null turns it off. Portfolio reports apply it the same way")
    public Map<String, Object> setPairStop(@RequestBody PairStopRequest r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", trading.setPairStopPoints(r == null ? null : r.points()));
        return out;
    }

    @GetMapping("/controls")
    public TradingControl controls() { return trading.controls(); }

    @PutMapping("/controls")
    @Operation(summary = "Change limits (ADMIN)")
    public TradingControl updateControls(@RequestBody ControlsRequest r) {
        TradingControl c = trading.updateControls(r.maxOrdersPerDay(), r.maxQuantityPerOrder(), r.dailyLossLimit());
        audit.record("CONTROLS", "maxOrdersPerDay=" + c.getMaxOrdersPerDay() + " maxQuantityPerOrder=" + c.getMaxQuantityPerOrder()
                + " dailyLossLimit=" + c.getDailyLossLimit());
        return c;
    }

    @PostMapping("/kill-switch")
    @Operation(summary = "Stop everything: no new orders, all strategies OFF, cancel open live orders, close strategy positions")
    public TradingService.KillResult kill() {
        TradingService.KillResult k = trading.engageKillSwitch();
        audit.record("KILL_SWITCH_ON", "cancelled " + k.cancelledOrders() + " orders, closed " + k.closedPositions()
                + " positions, stopped " + k.strategiesStopped() + " strategies");
        return k;
    }

    @PutMapping("/live")
    @Operation(summary = "Turn live trading on or off for everyone (ADMIN). Off: no new live orders or entries; open "
            + "live positions are still managed and closed")
    public TradingControl setLive(@RequestBody LiveRequest r) {
        if (r == null || r.enabled() == null) throw new TradingException("Missing enabled (true or false)");
        TradingControl c = trading.setLiveTrading(r.enabled());
        audit.record(r.enabled() ? "LIVE_TRADING_ON" : "LIVE_TRADING_OFF", null);
        return c;
    }

    public record LiveRestrictionRequest(Boolean restricted, String message) {}
    public record UserLiveAccessRequest(Boolean allowed) {}

    @GetMapping("/live-access")
    @Operation(summary = "Whether live trading is restricted to picked users, the message the others see, and who is picked (ADMIN)")
    public TradingService.LiveAccess liveAccess() { return trading.liveAccess(); }

    @PutMapping("/live-access")
    @Operation(summary = "Restrict live trading to picked users and/or set the message everyone else sees (ADMIN; blank message = default)")
    public TradingService.LiveAccess setLiveRestriction(@RequestBody LiveRestrictionRequest r) {
        if (r == null || (r.restricted() == null && r.message() == null)) throw new TradingException("Send restricted and/or message");
        TradingService.LiveAccess a = trading.setLiveRestriction(r.restricted(), r.message());
        audit.record("LIVE_RESTRICTION", "restricted=" + r.restricted() + (r.message() == null ? "" : " message changed"));
        return a;
    }

    @PutMapping("/live-access/users/{userId}")
    @Operation(summary = "Give or take a user's live access (ADMIN)")
    public TradingService.LiveAccess setUserLiveAccess(@PathVariable Long userId, @RequestBody UserLiveAccessRequest r) {
        if (r == null || r.allowed() == null) throw new TradingException("Missing allowed (true or false)");
        TradingService.LiveAccess a = trading.setUserLiveAccess(userId, r.allowed());
        audit.record(r.allowed() ? "LIVE_ACCESS_GIVEN" : "LIVE_ACCESS_TAKEN", "user " + userId);
        return a;
    }

    @DeleteMapping("/kill-switch")
    @Operation(summary = "Allow new orders again (ADMIN); strategies stay OFF until switched on")
    public TradingControl release() {
        TradingControl c = trading.releaseKillSwitch();
        audit.record("KILL_SWITCH_OFF", null);
        return c;
    }

    @GetMapping("/strategies")
    public List<TradingService.StrategyMode> strategies() { return trading.strategyModes(); }

    @PutMapping("/strategies/{id}")
    @Operation(summary = "Set a strategy to OFF, PAPER or LIVE (LIVE: PREMIUM with your own Kite account or ADMIN, and the server switch)")
    public TradingService.StrategyMode setMode(@PathVariable Long id, @RequestBody ModeRequest r) {
        if (r.mode() == null) throw new TradingException("Missing mode");
        return trading.setMode(id, r.mode(), r.product());   // recorded in the audit trail with the change (TradingService)
    }

    @PostMapping("/runner/run")
    @Operation(summary = "Run the live runner once now for every PAPER/LIVE strategy")
    public List<String> runNow() {
        LiveStrategyRunner r = runner.getIfAvailable();
        if (r == null) throw new TradingException("The live runner is disabled on this server");
        return r.runAll();
    }

    @GetMapping("/orders")
    @Operation(summary = "Orders the platform sent on a day (default today)")
    public List<TradeOrder> orders(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date == null ? orders.today() : date;
        return trading.isAdmin() ? orderRepo.findByTradeDateOrderByCreatedAtDesc(day)          // admins: everyone's
                : trading.withoutDeletedStrategies(orderRepo.findByTradeDateAndUserIdOrderByCreatedAtDesc(day, CurrentUser.id().orElseThrow()),
                        TradeOrder::getStrategyId, TradeOrder::getMode);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Place a manual order (confirm=true required; LIVE needs PREMIUM with your own Kite account, or ADMIN)")
    public TradeOrder place(@RequestBody ManualOrder m) {
        if (!Boolean.TRUE.equals(m.confirm())) throw new TradingException("Set confirm=true to place the order");
        if (m.mode() == TradingMode.LIVE) trading.requireLive();
        OrderRequest r = new OrderRequest(m.exchange() == null ? "NFO" : m.exchange(), m.tradingsymbol(), m.transactionType(),
                m.quantity() == null ? 0 : m.quantity(), m.product() == null ? "MIS" : m.product(),
                m.orderType() == null ? "MARKET" : m.orderType(), m.price(), m.triggerPrice());
        return orders.place(r, m.mode(), "MANUAL", null, CurrentUser.id().orElse(null), false);
    }

    @PostMapping("/orders/{id}/cancel")
    public TradeOrder cancel(@PathVariable Long id) {
        if (!trading.isAdmin()) {
            TradeOrder o = orderRepo.findById(id).orElseThrow(() -> new TradingException(HttpStatus.NOT_FOUND, "Order not found: " + id));
            if (!java.util.Objects.equals(o.getUserId(), CurrentUser.id().orElse(null)))
                throw new TradingException(HttpStatus.NOT_FOUND, "Order not found: " + id);   // other users' orders don't exist for you
        }
        return orders.cancel(id);
    }

    @GetMapping("/positions")
    @Operation(summary = "Positions the live runner opened for strategies on a day (default today)")
    public List<LivePosition> positions(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date == null ? orders.today() : date;
        return trading.isAdmin() ? positionRepo.findByTradeDateOrderByOpenedAtDesc(day)
                : trading.withoutDeletedStrategies(positionRepo.findByTradeDateAndUserIdOrderByOpenedAtDesc(day, CurrentUser.id().orElseThrow()),
                        LivePosition::getStrategyId, LivePosition::getMode);
    }

    /** At most this many days per history request. */
    static final int MAX_HISTORY_DAYS = 400;

    @GetMapping("/positions/history")
    @Operation(summary = "Strategy positions (paper and live) between two days, oldest first: for the dashboard's history "
            + "(default the last 90 days, at most " + MAX_HISTORY_DAYS + " days). Non-admins see their own, without deleted strategies' paper trades")
    public List<LivePosition> positionHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? orders.today() : to;
        LocalDate start = from == null ? end.minusDays(89) : from;
        if (start.isAfter(end)) throw new TradingException("'from' must not be after 'to'");
        if (start.plusDays(MAX_HISTORY_DAYS).isBefore(end))
            throw new TradingException("Pick at most " + MAX_HISTORY_DAYS + " days");
        return trading.isAdmin() ? positionRepo.findByTradeDateBetweenOrderByTradeDateAscOpenedAtAsc(start, end)
                : trading.withoutDeletedStrategies(positionRepo.findByTradeDateBetweenAndUserIdOrderByTradeDateAscOpenedAtAsc(
                        start, end, CurrentUser.id().orElseThrow()), LivePosition::getStrategyId, LivePosition::getMode);
    }

    /** Your own Kite or Angel One account (admins without one: the platform Kite account). */
    private KiteApi myKite() { return accounts.forUser(CurrentUser.id().orElse(null)); }

    @GetMapping("/broker/margins")   public KiteApi.Margins brokerMargins() { return myKite().margins(); }
    @GetMapping("/broker/positions") public List<KiteApi.BrokerPosition> brokerPositions() { return myKite().positions(); }
    @GetMapping("/broker/orders")    public List<KiteApi.BrokerOrder> brokerOrders() { return myKite().orders(); }

    @GetMapping("/options/expiries")
    @Operation(summary = "Option expiries from today of an index (NIFTY or BANKNIFTY)")
    public List<LocalDate> expiries(@RequestParam(defaultValue = "NIFTY") String underlying) {
        return options.expiries(Underlying.of(underlying));
    }

    @GetMapping("/options/chain")
    @Operation(summary = "Options of one index (NIFTY or BANKNIFTY) and expiry, optionally only strikes within ±range of a spot price")
    public List<KiteApi.OptionInstrument> chain(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiry,
                                                @RequestParam(defaultValue = "NIFTY") String underlying,
                                                @RequestParam(required = false) Double spot,
                                                @RequestParam(defaultValue = "500") int range) {
        return options.chain(Underlying.of(underlying), expiry).stream()
                .filter(o -> spot == null || Math.abs(o.strike() - spot) <= range).toList();
    }

    @GetMapping("/ltp")
    @Operation(summary = "Last traded prices, e.g. symbols=NFO:NIFTY25N1123550CE,NSE:NIFTY 50 (at most 1 second old)")
    public Map<String, Double> ltp(@RequestParam List<String> symbols) {
        return quotes(symbols).prices();
    }

    @GetMapping("/quotes")
    @Operation(summary = "Last traded prices plus the time they were fetched from Kite (poll about once a second)")
    public QuoteCache.Quotes quotes(@RequestParam List<String> symbols) {
        if (symbols.size() > 50) throw new TradingException("At most 50 symbols");
        return quoteCache.get(symbols);
    }
}
