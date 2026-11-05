package com.tradeanalysis.strategy.user;

import java.util.Set;

/**
 * What each plan may call. Admins may call everything. FREE: read (GET) only. GOLD and PREMIUM: also create
 * strategies, portfolios, backtests, reports and paper trading. Some calls are admin-only whatever the plan
 * (user management, the access log, the broker account, trading limits, kill switch, running the live runner). Live orders and
 * LIVE strategies are checked again in the trading service (admin-only until each user has their own Kite account).
 */
public final class Access {

    private static final Set<String> READ = Set.of("GET", "HEAD", "OPTIONS");

    private Access() {}

    /** Why the call is refused, or null when it is allowed. */
    public static String deny(AppUser user, Plan plan, String method, String path) {
        if (user.isAdmin()) return null;
        boolean read = READ.contains(method.toUpperCase());
        if ((path.startsWith("/api/users") && !path.equals("/api/users/me"))
                || path.startsWith("/api/access-log")
                || path.startsWith("/api/trading/broker/")
                || path.startsWith("/api/trading/kill-switch")
                || path.startsWith("/api/trading/runner/")
                || (path.equals("/api/trading/controls") && !read))
            return "Only an ADMIN can do this";
        if (read || path.startsWith("/api/auth/")) return null;
        if (!plan.atLeast(Plan.GOLD))
            return "Your plan is " + plan + " (view only). Upgrade to GOLD (₹999/month) to create strategies, run backtests "
                    + "and paper trade: ask an admin.";
        return null;
    }
}
