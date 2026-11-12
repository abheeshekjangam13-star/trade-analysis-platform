package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.strategy.user.AppUserRepository;
import com.tradeanalysis.strategy.user.CurrentUser;
import com.tradeanalysis.strategy.user.Plan;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Live order billing: your included live orders and credits (see {@link LiveOrderBilling}); admins adjust anyone's. */
@RestController
@RequestMapping("/api/trading/billing")
public class BillingController {

    public record AdjustRequest(Integer orders, Integer rupees, String note) {}

    private final LiveOrderBilling billing;
    private final TradingService trading;
    private final AppUserRepository users;
    private final Clock clock;

    public BillingController(LiveOrderBilling billing, TradingService trading, AppUserRepository users, Clock clock) {
        this.billing = billing; this.trading = trading; this.users = users; this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Your live orders left, credits, the price per extra order, whether live trading is available, and recent movements")
    public Map<String, Object> mine() {
        Long me = CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first"));
        return view(me, true);
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "A user's live orders and credits (ADMIN)")
    public Map<String, Object> ofUser(@PathVariable Long userId) {
        trading.requireAdmin("see a user's live order billing");
        return view(userId, true);
    }

    @PostMapping("/users/{userId}")
    @Operation(summary = "Add (or with negative values take away) a user's included live orders and/or credits in rupees (ADMIN)")
    public Map<String, Object> adjust(@PathVariable Long userId, @RequestBody AdjustRequest r) {
        trading.requireAdmin("change a user's live orders or credits");
        if (users.findById(userId).isEmpty()) throw new TradingException(HttpStatus.NOT_FOUND, "Unknown user: " + userId);
        int orders = r == null || r.orders() == null ? 0 : r.orders();
        long paise = r == null || r.rupees() == null ? 0 : r.rupees() * 100L;
        billing.add(userId, orders, paise, "ADJUST", r == null || r.note() == null || r.note().isBlank() ? "By an admin" : r.note().trim());
        return view(userId, true);
    }

    private Map<String, Object> view(Long userId, boolean withMovements) {
        var u = users.findById(userId).orElseThrow(() -> new TradingException(HttpStatus.NOT_FOUND, "Unknown user: " + userId));
        Plan plan = u.effectivePlan(clock.instant());
        LiveOrderBilling.Balance b = billing.balance(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("charged", !u.isAdmin());                       // admins are never charged
        out.put("plan", plan);
        out.put("includedPerPlan", plan.includedLiveOrders());
        out.put("balance", b);
        out.put("canTradeLive", u.isAdmin() || billing.refusal(userId) == null);
        out.put("warnAtUsedPercent", Math.round(LiveOrderBilling.WARN_AT_USED * 100));
        if (withMovements) out.put("movements", billing.movements(userId, 50));
        return out;
    }
}
