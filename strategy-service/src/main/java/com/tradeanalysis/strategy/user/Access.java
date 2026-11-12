package com.tradeanalysis.strategy.user;

import java.util.Set;

/**
 * What each plan may call. Admins may call everything. FREE: read (GET), plus creating strategies and running
 * backtests (within the plan's limits, checked in StrategyService). GOLD and PREMIUM: also portfolios, reports and
 * paper trading. Some calls are admin-only whatever the plan
 * (user management, the access log, email notifications, trading limits, kill switch, running the live runner). Live orders and
 * LIVE strategies are checked again in the trading service (PREMIUM with their own Kite account, or admins). The broker
 * views (/api/trading/broker/**) show the caller's own Kite account.
 */
public final class Access {

    private static final Set<String> READ = Set.of("GET", "HEAD", "OPTIONS");

    private Access() {}

    /** Why the call is refused, or null when it is allowed. */
    public static String deny(AppUser user, Plan plan, String method, String path) {
        if (user.isAdmin()) return null;
        boolean read = READ.contains(method.toUpperCase());
        if ((path.startsWith("/api/users") && !path.equals("/api/users/me") && !path.equals("/api/users/me/usage"))
                || path.startsWith("/api/access-log")
                || path.startsWith("/api/notifications")
                || path.startsWith("/api/trading/kill-switch")
                || path.startsWith("/api/trading/runner/")
                || path.startsWith("/api/trading/billing/users")
                || (path.equals("/api/trading/controls") && !read))
            return "Only an ADMIN can do this";
        if (read || path.startsWith("/api/auth/") || path.startsWith("/api/payments/")) return null;   // FREE users may pay
        // Every plan may create strategies and run backtests; the counts and look-back are limited by plan.
        if (path.startsWith("/api/strategies") || path.startsWith("/api/backtests")) return null;
        // Anyone may switch a strategy's trading OFF (e.g. after a paid plan expired); TradingService needs GOLD for PAPER/LIVE.
        if (method.equalsIgnoreCase("PUT") && path.startsWith("/api/trading/strategies/")) return null;
        // The same for a whole portfolio's strategies, and everyone may set their own max day loss.
        if (method.equalsIgnoreCase("PUT") && (path.matches("/api/portfolios/\\d+/trading") || path.equals("/api/trading/day-loss"))) return null;
        // Anyone may delete their own portfolio (e.g. after a paid plan expired); creating, editing and reports need GOLD.
        if (method.equalsIgnoreCase("DELETE") && path.matches("/api/portfolios/\\d+")) return null;
        if (!plan.atLeast(Plan.GOLD))
            return "Your plan is " + plan + ". Upgrade to GOLD (₹" + Plan.GOLD.pricePerMonth() + "/month) for portfolios, "
                    + "reports and paper trading.";
        return null;
    }
}
