package com.tradeanalysis.strategy.notify;

import com.tradeanalysis.strategy.notify.EmailTemplate.Tone;
import com.tradeanalysis.strategy.user.AppUser;
import com.tradeanalysis.strategy.user.Plan;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Subjects and bodies (plain text and HTML, from {@link EmailTemplate}) of the account emails. */
final class UserEmails {

    record Message(String subject, String body, String html) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.of("Asia/Kolkata"));

    private UserEmails() {}

    static Message signupReceived(AppUser u) {
        Plan wanted = u.getRequestedPlan() == null ? Plan.FREE : u.getRequestedPlan();
        return EmailTemplate.subject("We received your sign-up")
                .headline("Thanks for signing up")
                .greeting(name(u))
                .para("Thanks for signing up as '" + u.getUsername() + "'"
                        + (wanted == Plan.FREE ? "" : " for the " + wanted + " plan (" + price(wanted) + ")") + ".")
                .details("Username", u.getUsername(), "Plan", wanted.name(), "Status", "Waiting for approval")
                .para("An admin will review your account. We'll email you as soon as it is approved, and you can log in then.")
                .build();
    }

    static Message signupAdminAlert(AppUser u, String appUrl) {
        return EmailTemplate.subject("New sign-up waiting for approval: " + u.getUsername())
                .headline("New sign-up to review")
                .tone(Tone.WARNING)
                .para("A new user signed up and is waiting for approval.")
                .details("Username", u.getUsername(),
                        "Name", orDash(u.getFullName()),
                        "Email", orDash(u.getEmail()),
                        "Phone", orDash(u.getPhone()),
                        "Requested plan", String.valueOf(u.getRequestedPlan() == null ? Plan.FREE : u.getRequestedPlan()),
                        "Paid", u.getPrepaidPlan() == null ? null
                                : u.getPrepaidPlan() + " for " + u.getPrepaidDays() + " days (starts when you approve)")
                .action("Review it here", appUrl + "/users")
                .build();
    }

    static Message signupPaymentReceived(AppUser u, Plan plan, long amountPaise, String paymentId) {
        return EmailTemplate.subject("Payment received: sign-up sent for approval")
                .headline("Payment received")
                .tone(Tone.SUCCESS)
                .greeting(name(u))
                .para("We received your payment of " + amount(amountPaise) + " for the " + plan + " plan.")
                .details("Plan", plan.name(), "Amount", amount(amountPaise), "Payment id", paymentId)
                .para("Your sign-up '" + u.getUsername() + "' is now with an admin for approval. Your " + plan
                        + " plan starts on the day it is approved, so you don't lose any paid days while you wait."
                        + " We'll email you as soon as it is approved.")
                .build();
    }

    static Message approved(AppUser u, String appUrl) {
        Plan p = u.getPlan();
        EmailTemplate t = EmailTemplate.subject("Your account is approved")
                .headline("Welcome! Your account is approved")
                .tone(Tone.SUCCESS)
                .greeting(name(u))
                .para("Your account '" + u.getUsername() + "' has been approved.")
                .details("Plan", p + (p == Plan.FREE ? "" : " (" + price(p) + ")"),
                        "Valid until", u.getPlanExpiresAt() == null ? null : DATE.format(u.getPlanExpiresAt()))
                .bullets(p.features())
                .box("Remarks from the admin", u.getReviewNote(), Tone.INFO)
                .action("Log in now", appUrl + "/login");
        if (p == Plan.FREE) upgradeHint(t, p, appUrl);
        return t.build();
    }

    static Message rejected(AppUser u) {
        return EmailTemplate.subject("Your sign-up was not approved")
                .headline("Your sign-up was not approved")
                .tone(Tone.DANGER)
                .greeting(name(u))
                .para("Sorry, your sign-up as '" + u.getUsername() + "' was not approved.")
                .box("Remarks from the admin", u.getReviewNote(), Tone.DANGER)
                .para("If you think this is a mistake, reply to this email or contact the admin.")
                .build();
    }

    static Message accountCreated(AppUser u, String appUrl) {
        return EmailTemplate.subject("Your account has been created")
                .headline("Your account is ready")
                .greeting(name(u))
                .para("An admin created an account for you.")
                .details("Username", u.getUsername(), "Plan", String.valueOf(u.getPlan()))
                .para("Ask the admin for your password, then log in and change it under My account.")
                .action("Log in", appUrl + "/login")
                .build();
    }

    static Message paymentReceived(AppUser u, Plan plan, long amountPaise, String paymentId, Instant validUntil, String appUrl) {
        return EmailTemplate.subject("Payment received: " + plan + " plan active")
                .headline("Your " + plan + " plan is active")
                .tone(Tone.SUCCESS)
                .greeting(name(u))
                .para("We received your payment of " + amount(amountPaise) + " for the " + plan + " plan.")
                .details("Plan", plan.name(), "Amount", amount(amountPaise), "Payment id", paymentId,
                        "Valid until", DATE.format(validUntil))
                .bullets(plan.features())
                .action("Open SysGrow", appUrl)
                .build();
    }

    static Message liveOrdersLow(AppUser u, int left, int granted, long creditPaise, long pricePaise, String appUrl) {
        long fromCredits = pricePaise > 0 ? creditPaise / pricePaise : 0;
        return EmailTemplate.subject("You've used " + (granted - left) + " of your " + granted + " live orders: add credits")
                .headline("Add credits to keep trading live")
                .tone(Tone.WARNING)
                .greeting(name(u))
                .para("You've used " + (granted - left) + " of the " + granted + " live orders included with your plan, so "
                        + left + " are left. When they and your credits run out, live trading stops for your account until you "
                        + "add credits (open positions still close as usual).")
                .details("Live orders left", String.valueOf(left), "Credits", amount(creditPaise)
                                + (fromCredits > 0 ? " (" + fromCredits + " more orders)" : ""),
                        "Price per extra order", amount(pricePaise))
                .action("Add credits", appUrl + "/trading")
                .build();
    }

    static Message creditsAdded(AppUser u, long amountPaise, String paymentId, long balancePaise, long pricePaise, String appUrl) {
        return EmailTemplate.subject("Credits added: " + amount(amountPaise))
                .headline("Your credits are ready")
                .tone(Tone.SUCCESS)
                .greeting(name(u))
                .para("We received your payment of " + amount(amountPaise) + ". It's added to your credits for live orders.")
                .details("Added", amount(amountPaise), "Credits now", amount(balancePaise),
                        "Live orders it covers", String.valueOf(pricePaise > 0 ? balancePaise / pricePaise : 0), "Payment id", paymentId)
                .action("Open Trading", appUrl + "/trading")
                .build();
    }

    static Message paymentFailed(AppUser u, Plan plan, long amountPaise, String reason, String appUrl) {
        return EmailTemplate.subject("Payment failed: " + plan + " plan not activated")
                .headline("Your payment didn't go through")
                .tone(Tone.DANGER)
                .greeting(name(u))
                .para("Your payment of " + amount(amountPaise) + " for the " + plan
                        + " plan did not go through, so the plan was not activated.")
                .details("Plan", plan.name(), "Amount", amount(amountPaise), "Reason", orDash(reason))
                .box("No money was taken", "If your bank shows a debit, it is refunded automatically, usually within 5–7 working days.", Tone.INFO)
                .action("Try again", appUrl + "/account")
                .build();
    }

    static Message planChanged(AppUser u, Plan from, String appUrl) {
        Plan p = u.getPlan();
        boolean up = p.ordinal() > from.ordinal();
        EmailTemplate t = EmailTemplate.subject("Your plan is now " + p)
                .headline(up ? "You've been upgraded to " + p : "Your plan is now " + p)
                .tone(up ? Tone.SUCCESS : Tone.INFO)
                .greeting(name(u))
                .para("An admin changed your plan from " + from + " to " + p + ".")
                .details("Plan", p + (p == Plan.FREE ? "" : " (" + price(p) + ")"),
                        "Valid until", u.getPlanExpiresAt() == null || p == Plan.FREE ? null : DATE.format(u.getPlanExpiresAt()))
                .para(limits(p))
                .action("See your account", appUrl + "/account");
        if (!up) upgradeHint(t, p, appUrl);
        return t.build();
    }

    static Message planExpiring(AppUser u, String appUrl) {
        Plan p = u.getPlan();
        return EmailTemplate.subject("Your " + p + " plan ends on " + DATE.format(u.getPlanExpiresAt()))
                .headline("Your " + p + " plan ends soon")
                .tone(Tone.WARNING)
                .greeting(name(u))
                .para("Your " + p + " plan ends on " + DATE.format(u.getPlanExpiresAt()) + ".")
                .para("Renew it to keep " + limits(p).toLowerCase(java.util.Locale.ROOT))
                .box("What happens if it ends", "Your account moves to the Free plan: up to " + Plan.FREE.maxStrategies()
                        + " strategies, backtests of the last 3 months, no paper trading, no editing, and up to "
                        + Plan.FREE.maxDeletes() + " deletes. Your strategies are kept.", Tone.WARNING)
                .action("Renew now", appUrl + "/account")
                .build();
    }

    static Message planExpired(AppUser u, String appUrl) {
        Plan p = u.getPlan();
        return EmailTemplate.subject("Your " + p + " plan has ended")
                .headline("Your " + p + " plan has ended")
                .tone(Tone.WARNING)
                .greeting(name(u))
                .para("Your " + p + " plan ended on " + DATE.format(u.getPlanExpiresAt())
                        + ", so your account is now on the Free plan.")
                .para("Your strategies and reports are kept. Paper/live trading has stopped taking new entries; "
                        + "you can still turn trading off. On the Free plan strategies can't be edited, and you can delete up to "
                        + Plan.FREE.maxDeletes() + " strategies or portfolios.")
                .box("Renew to get back", limits(p), Tone.INFO)
                .action("Renew any time", appUrl + "/account")
                .build();
    }

    static Message passwordChanged(AppUser u, boolean byAdmin, String appUrl) {
        return EmailTemplate.subject("Your password was changed")
                .headline("Your password was changed")
                .tone(Tone.WARNING)
                .greeting(name(u))
                .para((byAdmin ? "An admin set a new password for your account '" : "The password of your account '")
                        + u.getUsername() + "'" + (byAdmin ? "." : " was changed."))
                .box("If this wasn't you", "Contact the admin right away.", Tone.DANGER)
                .action("Log in", appUrl + "/login")
                .build();
    }

    /**
     * Step 1 of the beta: an admin nominates the user. Congratulates them, says how few were picked, and asks them to
     * request the link from My account if they'd like to try it. The admin's optional note is shown in a box.
     */
    static Message betaInvite(AppUser u, String appUrl, long totalUsers, String note) {
        return EmailTemplate.subject("Congratulations! You're invited to try the SysGrow beta app")
                .headline("You're one of our first beta app users")
                .tone(Tone.SUCCESS)
                .greeting(name(u))
                .para("Congratulations, " + u.getUsername() + "! As part of SysGrow's continuous growth, we are pleased to"
                        + " inform you that you have been nominated to try the new SysGrow mobile app for Android before"
                        + " anyone else.")
                .para("For this first phase we picked only 10% of our " + String.format(java.util.Locale.ENGLISH, "%,d", totalUsers)
                        + " users, at random, and you are one of them.")
                .box("Please note", "For now the beta app works only on Android phones (in Chrome). It is not yet"
                        + " available for iPhone or iPad; we'll let you know when it is.", Tone.WARNING)
                .box("A note from the SysGrow team", note, Tone.INFO)
                .para("With the app you can follow your strategies, see your trades and P&L, and get alerts on your phone"
                        + " when an order is placed, when a target or stop loss is hit, when the market opens, and when it's"
                        + " time to log in to Kite.")
                .para("Happy to try the beta? Ask for your app link in three clicks:")
                .bullets(List.of(
                        "Log in to SysGrow on the web",
                        "Open My account (your name at the top right)",
                        "Click \"Send me the app link\" in the SysGrow beta app box"))
                .para("We'll email you the link and the install steps straight away. As it's a beta, you may see small"
                        + " changes as we improve it, and we'd love to hear what you think.")
                .action("Go to My account", appUrl + "/account#beta-app")
                .footnote("Not interested right now? No action is needed; the offer stays open on My account.")
                .build();
    }

    /**
     * Step 2 of the beta: the user asked for the link on My account. The link, who it's for, how to install it from
     * Chrome (no Play Store yet) and how to turn on phone alerts.
     */
    static Message appLink(AppUser u, String appUrl) {
        return EmailTemplate.subject("Your SysGrow beta app link")
                .headline("Here's your SysGrow beta app")
                .tone(Tone.SUCCESS)
                .greeting(name(u))
                .para("As you requested, here is your link to the SysGrow beta app for Android. Build NIFTY option"
                        + " strategies, backtest them on past market data, and follow them in paper or live trading on your"
                        + " own Zerodha Kite account, all from your phone.")
                .para("Install it in under a minute (no Play Store needed):")
                .bullets(List.of(
                        "Open the link below in Chrome on your Android phone",
                        "Log in with your username '" + u.getUsername() + "'",
                        "Tap \"Install app\" at the top (or Chrome menu ⋮ → Install app / Add to Home screen)",
                        "Open SysGrow from your home screen, go to My account and turn on phone alerts"))
                .para("With alerts on, your phone tells you when an order is placed, when a position exits (target or"
                        + " stop loss hit), when the market opens, and when it's time to log in to Kite for the day.")
                .action("Open SysGrow on your phone", appUrl + "/login")
                .footnote("This is a beta app: please tell us if anything looks wrong. Backtest results are estimates;"
                        + " past results don't guarantee future returns. Not investment advice.")
                .build();
    }

    static Message test(AppUser u, String appUrl) {
        return EmailTemplate.subject("Test email from SysGrow")
                .headline("Email delivery works")
                .tone(Tone.SUCCESS)
                .greeting(name(u))
                .para("This is a test email. If you can read it, email delivery from the server works.")
                .action("Open SysGrow", appUrl)
                .build();
    }

    /** "Upgrade to GOLD for …": what the next plan adds, with a link to upgrade. */
    private static void upgradeHint(EmailTemplate t, Plan current, String appUrl) {
        Plan next = current.next();
        if (next == null) return;
        t.box("Want more? Upgrade to " + next + " (" + price(next) + ")", String.join("\n", gains(current, next)), Tone.INFO)
         .footnote("Upgrade on My account: " + appUrl + "/account");
    }

    /** What moving from one plan to the next gains, in short lines. */
    static List<String> gains(Plan from, Plan to) {
        List<String> g = new java.util.ArrayList<>();
        if (to.maxStrategies() > from.maxStrategies()) g.add("• Up to " + to.maxStrategies() + " strategies (now " + from.maxStrategies() + ")");
        if (to.maxBacktestDays() > from.maxBacktestDays()) g.add("• Backtests of the last " + to.maxBacktestDays() + " days (now " + from.maxBacktestDays() + ")");
        if (to.editStrategies() && !from.editStrategies()) g.add("• Edit your strategies");
        if (to.maxDeletes() == Plan.UNLIMITED && from.maxDeletes() != Plan.UNLIMITED) g.add("• Delete without a limit (now " + from.maxDeletes() + ")");
        if (to.paperTrading() && !from.paperTrading()) g.add("• Portfolios, reports and paper trading");
        if (to.liveTrading() && !from.liveTrading()) g.add("• Live trading on your own Kite account");
        return g;
    }

    private static String limits(Plan p) {
        return "Up to " + p.maxStrategies() + " strategies, backtests of the last " + p.maxBacktestDays() + " days"
                + (p.liveTrading() ? ", paper and live trading" : p.paperTrading() ? ", paper trading" : "") + ".";
    }

    private static String price(Plan p) { return "₹" + p.pricePerMonth() + "/month"; }

    private static String amount(long paise) { return "₹" + String.format("%.2f", paise / 100.0); }

    private static String name(AppUser u) {
        return u.getFullName() == null || u.getFullName().isBlank() ? u.getUsername() : u.getFullName();
    }

    private static String orDash(String s) { return s == null || s.isBlank() ? "-" : s; }
}
