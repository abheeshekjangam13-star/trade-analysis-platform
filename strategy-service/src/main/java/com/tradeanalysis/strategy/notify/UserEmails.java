package com.tradeanalysis.strategy.notify;

import com.tradeanalysis.strategy.user.AppUser;
import com.tradeanalysis.strategy.user.Plan;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Subjects and plain-text bodies of the account emails. */
final class UserEmails {

    record Message(String subject, String body) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.of("Asia/Kolkata"));
    private static final String SIGNATURE = "\n\n— Trade Analysis Platform";

    private UserEmails() {}

    static Message signupReceived(AppUser u) {
        return new Message("We received your sign-up",
                greeting(u) + "Thanks for signing up as '" + u.getUsername() + "'"
                + (u.getRequestedPlan() == null || u.getRequestedPlan() == Plan.FREE ? "" :
                   " for the " + u.getRequestedPlan() + " plan (₹" + u.getRequestedPlan().pricePerMonth() + "/month)")
                + ".\n\nAn admin will review your account. We'll email you as soon as it is approved, and you can log in then."
                + SIGNATURE);
    }

    static Message signupAdminAlert(AppUser u, String appUrl) {
        return new Message("New sign-up waiting for approval: " + u.getUsername(),
                "A new user signed up and is waiting for approval.\n\n"
                + "Username: " + u.getUsername() + "\n"
                + "Name: " + orDash(u.getFullName()) + "\n"
                + "Email: " + orDash(u.getEmail()) + "\n"
                + "Phone: " + orDash(u.getPhone()) + "\n"
                + "Requested plan: " + (u.getRequestedPlan() == null ? Plan.FREE : u.getRequestedPlan()) + "\n\n"
                + "Review it here: " + appUrl + "/users" + SIGNATURE);
    }

    static Message approved(AppUser u, String appUrl) {
        Plan p = u.getPlan();
        return new Message("Your account is approved",
                greeting(u) + "Your account '" + u.getUsername() + "' has been approved.\n\n"
                + "Plan: " + p + (p == Plan.FREE ? "" : " (₹" + p.pricePerMonth() + "/month)") + "\n"
                + (u.getPlanExpiresAt() == null ? "" : "Valid until: " + DATE.format(u.getPlanExpiresAt()) + "\n")
                + remarks(u.getReviewNote())
                + "\nYou can log in now: " + appUrl + "/login" + SIGNATURE);
    }

    static Message rejected(AppUser u) {
        return new Message("Your sign-up was not approved",
                greeting(u) + "Sorry, your sign-up as '" + u.getUsername() + "' was not approved.\n"
                + remarks(u.getReviewNote())
                + "\nIf you think this is a mistake, reply to this email or contact the admin." + SIGNATURE);
    }

    static Message accountCreated(AppUser u, String appUrl) {
        return new Message("Your account has been created",
                greeting(u) + "An admin created an account for you.\n\n"
                + "Username: " + u.getUsername() + "\n"
                + "Plan: " + u.getPlan() + "\n\n"
                + "Ask the admin for your password, then log in at " + appUrl + "/login and change it under Account."
                + SIGNATURE);
    }

    static Message paymentReceived(AppUser u, Plan plan, long amountPaise, String paymentId, Instant validUntil, String appUrl) {
        return new Message("Payment received: " + plan + " plan active",
                greeting(u) + "We received your payment of ₹" + String.format("%.2f", amountPaise / 100.0)
                + " for the " + plan + " plan.\n\n"
                + "Payment id: " + paymentId + "\n"
                + "Valid until: " + DATE.format(validUntil) + "\n\n"
                + "Your plan is active now: " + appUrl + SIGNATURE);
    }

    private static String greeting(AppUser u) {
        String name = u.getFullName() == null || u.getFullName().isBlank() ? u.getUsername() : u.getFullName();
        return "Hi " + name + ",\n\n";
    }

    private static String remarks(String note) {
        return note == null || note.isBlank() ? "" : "\nRemarks from the admin:\n" + note.trim() + "\n";
    }

    private static String orDash(String s) { return s == null || s.isBlank() ? "-" : s; }
}
