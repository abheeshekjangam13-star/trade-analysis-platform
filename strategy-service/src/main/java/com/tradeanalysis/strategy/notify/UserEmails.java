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
