package com.tradeanalysis.strategy.user;

import java.util.List;

/** Subscription plans. Users pay online with Razorpay (PaymentService), or an admin confirms payment and sets the plan. */
public enum Plan {
    FREE(0, "VIEWER", List.of("View strategies, backtests, reports and market data")),
    GOLD(999, "TRADER", List.of("Everything in Free", "Create strategies and portfolios", "Run backtests and reports",
            "Paper trading (simulated orders)", "Connect your own Kite account (positions, margins, orders)")),
    PREMIUM(1999, "PREMIUM", List.of("Everything in Gold", "Live trading on your own Kite account"));

    private final int pricePerMonth;
    private final String role;
    private final List<String> features;

    Plan(int pricePerMonth, String role, List<String> features) {
        this.pricePerMonth = pricePerMonth; this.role = role; this.features = features;
    }

    public int pricePerMonth()     { return pricePerMonth; }
    /** The role that shows this plan in the user list. */
    public String role()           { return role; }
    public List<String> features() { return features; }
    public boolean atLeast(Plan p) { return ordinal() >= p.ordinal(); }

    public record Info(Plan plan, int pricePerMonth, List<String> features) {}
    public static List<Info> catalog() {
        return java.util.Arrays.stream(values()).map(p -> new Info(p, p.pricePerMonth, p.features)).toList();
    }
}
