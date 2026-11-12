package com.tradeanalysis.strategy.user;

import java.util.List;

/**
 * Subscription plans. Users pay online with Razorpay (PaymentService), or an admin confirms payment and sets the plan.
 * {@code pricePerMonth} is what is charged (after the discount); {@code listPricePerMonth} is the price before it.
 */
public enum Plan {
    FREE(0, 0, 0, "VIEWER", 2, 90, false, false, false, 4, 0, 0, List.of(
            "View strategies, backtests, reports and market data",
            "Create up to 2 strategies",
            "Backtest up to the last 3 months",
            "Delete up to 4 strategies or portfolios")),
    GOLD(1499, 2142, 30, "TRADER", 6, 365, true, false, true, -1, 0, 0, List.of(
            "Everything in Free",
            "Create up to 6 strategies, and portfolios",
            "Edit strategies, and delete without a limit",
            "Backtest up to the last 1 year",
            "Paper trading (simulated orders)",
            "Connect your own Kite account (positions, margins, orders)")),
    PREMIUM(2499, 4998, 50, "PREMIUM", 12, 730, true, true, true, -1, 8, 2, List.of(
            "Everything in Gold",
            "Create up to 12 strategies",
            "Backtest up to the last 2 years",
            "Paper and live trading on your own Kite account",
            "Up to 8 strategies trading live, up to 2 lots per order",
            "30 live orders included each month; add credits for more (₹10 per order)")),
    PREMIUM_PLUS(4499, 8998, 50, "PREMIUM", 20, 730, true, true, true, -1, 12, 4, List.of(
            "Everything in Premium",
            "Create up to 20 strategies",
            "Up to 12 strategies trading live, up to 4 lots per order")),
    PREMIUM_PRO(7999, 15998, 50, "PREMIUM", 30, 730, true, true, true, -1, 20, 8, List.of(
            "Everything in Premium Plus",
            "Create up to 30 strategies",
            "Up to 20 strategies trading live, up to 8 lots per order"));

    private final int pricePerMonth;
    private final int listPricePerMonth;
    private final int discountPercent;
    private final String role;
    private final int maxStrategies;
    private final int maxBacktestDays;
    private final boolean paperTrading;
    private final boolean liveTrading;
    private final boolean editStrategies;
    private final int maxDeletes;
    private final int maxLiveStrategies;
    private final int maxLiveLots;
    private final List<String> features;

    Plan(int pricePerMonth, int listPricePerMonth, int discountPercent, String role, int maxStrategies,
         int maxBacktestDays, boolean paperTrading, boolean liveTrading, boolean editStrategies, int maxDeletes,
         int maxLiveStrategies, int maxLiveLots, List<String> features) {
        this.pricePerMonth = pricePerMonth; this.listPricePerMonth = listPricePerMonth; this.discountPercent = discountPercent;
        this.role = role; this.maxStrategies = maxStrategies; this.maxBacktestDays = maxBacktestDays;
        this.paperTrading = paperTrading; this.liveTrading = liveTrading; this.editStrategies = editStrategies;
        this.maxDeletes = maxDeletes; this.maxLiveStrategies = maxLiveStrategies; this.maxLiveLots = maxLiveLots;
        this.features = features;
    }

    /** {@link #maxDeletes()} for plans without a delete limit. */
    public static final int UNLIMITED = -1;

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
    /** Whether the user may edit their strategies (name, description, parameters). */
    public boolean editStrategies() { return editStrategies; }
    /** How many strategies/portfolios the user may delete in all, or {@link #UNLIMITED}. */
    public int maxDeletes()        { return maxDeletes; }
    /** How many of the user's strategies may be set to LIVE at once (0 = no live trading). */
    public int maxLiveStrategies() { return maxLiveStrategies; }
    /** The most lots a live order may have (strategy entries and order tickets; 0 = no live trading). */
    public int maxLiveLots()       { return maxLiveLots; }
    /** Live orders included with each start or renewal of the plan (credited at once; 0 without live trading). */
    public int includedLiveOrders() { return liveTrading ? INCLUDED_LIVE_ORDERS : 0; }
    public static final int INCLUDED_LIVE_ORDERS = 30;
    /** The name shown to users, e.g. "Premium Plus". */
    public String label() {
        String n = name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        int sp = n.indexOf(' ');
        return sp < 0 ? n : n.substring(0, sp + 1) + Character.toUpperCase(n.charAt(sp + 1)) + n.substring(sp + 2);
    }
    /** The next plan up, or null for the highest. */
    public Plan next()             { return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null; }
    public List<String> features() { return features; }
    public boolean atLeast(Plan p) { return ordinal() >= p.ordinal(); }

    public record Info(Plan plan, String label, int pricePerMonth, int listPricePerMonth, int discountPercent, int maxStrategies,
                       int maxBacktestDays, boolean paperTrading, boolean liveTrading, boolean editStrategies,
                       int maxDeletes, int maxLiveStrategies, int maxLiveLots, int includedLiveOrders, List<String> features) {}
    public Info info() {
        return new Info(this, label(), pricePerMonth, listPricePerMonth, discountPercent, maxStrategies, maxBacktestDays,
                paperTrading, liveTrading, editStrategies, maxDeletes, maxLiveStrategies, maxLiveLots, includedLiveOrders(), features);
    }
    public static List<Info> catalog() {
        return java.util.Arrays.stream(values()).map(Plan::info).toList();
    }
}
