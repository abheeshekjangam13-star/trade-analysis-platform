package com.tradeanalysis.strategy.user;

import java.util.List;

/**
 * Subscription plans. Users pay online with Razorpay (PaymentService), or an admin confirms payment and sets the plan.
 * {@code pricePerMonth} is what is charged (after the discount); {@code listPricePerMonth} is the price before it.
 */
public enum Plan {
    FREE(0, 0, 0, "VIEWER", 2, 90, false, false, List.of(
            "View strategies, backtests, reports and market data",
            "Create up to 2 strategies",
            "Backtest up to the last 3 months")),
    GOLD(1499, 2142, 30, "TRADER", 6, 365, true, false, List.of(
            "Everything in Free",
            "Create up to 6 strategies, and portfolios",
            "Backtest up to the last 1 year",
            "Paper trading (simulated orders)",
            "Connect your own Kite account (positions, margins, orders)")),
    PREMIUM(2499, 4998, 50, "PREMIUM", 12, 730, true, true, List.of(
            "Everything in Gold",
            "Create up to 12 strategies",
            "Backtest up to the last 2 years",
            "Paper and live trading on your own Kite account"));

    private final int pricePerMonth;
    private final int listPricePerMonth;
    private final int discountPercent;
    private final String role;
    private final int maxStrategies;
    private final int maxBacktestDays;
    private final boolean paperTrading;
    private final boolean liveTrading;
    private final List<String> features;

    Plan(int pricePerMonth, int listPricePerMonth, int discountPercent, String role, int maxStrategies,
         int maxBacktestDays, boolean paperTrading, boolean liveTrading, List<String> features) {
        this.pricePerMonth = pricePerMonth; this.listPricePerMonth = listPricePerMonth; this.discountPercent = discountPercent;
        this.role = role; this.maxStrategies = maxStrategies; this.maxBacktestDays = maxBacktestDays;
        this.paperTrading = paperTrading; this.liveTrading = liveTrading; this.features = features;
    }

    /** What the user pays per month (after the discount). */
    public int pricePerMonth()     { return pricePerMonth; }
    /** The price before the discount. */
    public int listPricePerMonth() { return listPricePerMonth; }
    public int discountPercent()   { return discountPercent; }
    /** The role that shows this plan in the user list. */
    public String role()           { return role; }
    /** How many strategies a user on this plan may own (clones count; shared master strategies don't). */
    public int maxStrategies()     { return maxStrategies; }
    /** The longest backtest look-back, in days. */
    public int maxBacktestDays()   { return maxBacktestDays; }
    public boolean paperTrading()  { return paperTrading; }
    public boolean liveTrading()   { return liveTrading; }
    public List<String> features() { return features; }
    public boolean atLeast(Plan p) { return ordinal() >= p.ordinal(); }

    public record Info(Plan plan, int pricePerMonth, int listPricePerMonth, int discountPercent, int maxStrategies,
                       int maxBacktestDays, boolean paperTrading, boolean liveTrading, List<String> features) {}
    public Info info() {
        return new Info(this, pricePerMonth, listPricePerMonth, discountPercent, maxStrategies, maxBacktestDays,
                paperTrading, liveTrading, features);
    }
    public static List<Info> catalog() {
        return java.util.Arrays.stream(values()).map(Plan::info).toList();
    }
}
