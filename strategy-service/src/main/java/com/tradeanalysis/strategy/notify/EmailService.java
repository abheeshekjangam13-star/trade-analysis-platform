package com.tradeanalysis.strategy.notify;

import com.tradeanalysis.strategy.user.AppUser;
import com.tradeanalysis.strategy.user.AppUserRepository;
import com.tradeanalysis.strategy.user.Plan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Queues account emails in the outbox. Call inside the transaction that changes the user, so the email is saved only
 * if the change is; {@link EmailDispatcher} sends it shortly after. Users without an email address are skipped.
 */
@Service
public class EmailService {

    private final EmailNotificationRepository repo;
    private final AppUserRepository users;
    private final String appUrl;

    public EmailService(EmailNotificationRepository repo, AppUserRepository users,
                        @Value("${notifications.app-url:http://localhost:5173}") String appUrl) {
        this.repo = repo; this.users = users;
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void signupReceived(AppUser u) {
        queue(u, EmailNotification.Kind.SIGNUP_RECEIVED, UserEmails.signupReceived(u));
        UserEmails.Message alert = UserEmails.signupAdminAlert(u, appUrl);
        users.findAll().stream()
                .filter(a -> a.isAdmin() && a.getStatus() == AppUser.Status.ACTIVE && hasEmail(a))
                .forEach(a -> queue(a, EmailNotification.Kind.SIGNUP_ADMIN_ALERT, alert));
    }

    /** A GOLD/PREMIUM sign-up paid: the user hears it is waiting for approval, and the admins get the sign-up now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void signupPaid(AppUser u, Plan plan, long amountPaise, String paymentId) {
        queue(u, EmailNotification.Kind.PAYMENT_RECEIVED, UserEmails.signupPaymentReceived(u, plan, amountPaise, paymentId));
        UserEmails.Message alert = UserEmails.signupAdminAlert(u, appUrl);
        users.findAll().stream()
                .filter(a -> a.isAdmin() && a.getStatus() == AppUser.Status.ACTIVE && hasEmail(a))
                .forEach(a -> queue(a, EmailNotification.Kind.SIGNUP_ADMIN_ALERT, alert));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(AppUser u) { queue(u, EmailNotification.Kind.ACCOUNT_APPROVED, UserEmails.approved(u, appUrl)); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rejected(AppUser u) { queue(u, EmailNotification.Kind.ACCOUNT_REJECTED, UserEmails.rejected(u)); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void accountCreated(AppUser u) { queue(u, EmailNotification.Kind.ACCOUNT_CREATED, UserEmails.accountCreated(u, appUrl)); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentReceived(AppUser u, Plan plan, long amountPaise, String paymentId, Instant validUntil) {
        queue(u, EmailNotification.Kind.PAYMENT_RECEIVED,
                UserEmails.paymentReceived(u, plan, amountPaise, paymentId, validUntil, appUrl));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentFailed(AppUser u, Plan plan, long amountPaise, String reason) {
        queue(u, EmailNotification.Kind.PAYMENT_FAILED, UserEmails.paymentFailed(u, plan, amountPaise, reason, appUrl));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void liveOrdersLow(AppUser u, int left, int granted, long creditPaise, long pricePaise) {
        queue(u, EmailNotification.Kind.LIVE_ORDERS_LOW, UserEmails.liveOrdersLow(u, left, granted, creditPaise, pricePaise, appUrl));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void creditsAdded(AppUser u, long amountPaise, String paymentId, long balancePaise, long pricePaise) {
        queue(u, EmailNotification.Kind.CREDITS_ADDED, UserEmails.creditsAdded(u, amountPaise, paymentId, balancePaise, pricePaise, appUrl));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void planChanged(AppUser u, Plan from) { queue(u, EmailNotification.Kind.PLAN_CHANGED, UserEmails.planChanged(u, from, appUrl)); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void passwordChanged(AppUser u, boolean byAdmin) {
        queue(u, EmailNotification.Kind.PASSWORD_CHANGED, UserEmails.passwordChanged(u, byAdmin, appUrl));
    }

    /**
     * Reminds paid users 3 days before their plan ends, and tells them once it has ended (each once per expiry date).
     * Runs daily; also callable directly. Returns how many emails were queued.
     */
    @Transactional
    public int planReminders(Instant now) {
        int n = 0;
        for (AppUser u : users.findAll()) {
            Instant until = u.getPlanExpiresAt();
            if (until == null || u.getPlan() == Plan.FREE || u.isAdmin() || u.getStatus() != AppUser.Status.ACTIVE || !hasEmail(u)) continue;
            if (until.isAfter(now) && !until.isAfter(now.plus(java.time.Duration.ofDays(3)))
                    && !repo.existsByUserIdAndKindAndCreatedAtAfter(u.getId(), EmailNotification.Kind.PLAN_EXPIRING, until.minus(java.time.Duration.ofDays(7)))) {
                queue(u, EmailNotification.Kind.PLAN_EXPIRING, UserEmails.planExpiring(u, appUrl));
                n++;
            } else if (!until.isAfter(now) && until.isAfter(now.minus(java.time.Duration.ofDays(7)))   // not for long-gone expiries
                    && !repo.existsByUserIdAndKindAndCreatedAtAfter(u.getId(), EmailNotification.Kind.PLAN_EXPIRED, until.minus(java.time.Duration.ofDays(7)))) {
                queue(u, EmailNotification.Kind.PLAN_EXPIRED, UserEmails.planExpired(u, appUrl));
                n++;
            }
        }
        return n;
    }

    @org.springframework.scheduling.annotation.Scheduled(cron = "${notifications.plan-reminders-cron:0 0 9 * * *}", zone = "Asia/Kolkata")
    public void dailyPlanReminders() {
        int n = planReminders(Instant.now());
        if (n > 0) org.slf4j.LoggerFactory.getLogger(EmailService.class).info("EMAIL plan reminders queued: {}", n);
    }

    /** Beta step 1: the admin nominated this user; they're asked to request the app link on My account. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void betaInvite(AppUser u, long totalUsers, String note) {
        queue(u, EmailNotification.Kind.BETA_INVITE, UserEmails.betaInvite(u, appUrl, totalUsers, note));
    }

    /** Beta step 2: the user asked for the app link; it goes out with the install steps. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appLink(AppUser u) {
        queue(u, EmailNotification.Kind.APP_INVITE, UserEmails.appLink(u, appUrl));
    }

    /** ADMIN check of the mail setup: queues a test email to this user. Returns its id (or null without an address). */
    @Transactional
    public Long test(AppUser to) {
        if (!hasEmail(to)) return null;
        UserEmails.Message m = UserEmails.test(to, appUrl);
        return repo.save(new EmailNotification(to.getId(), to.getEmail(), EmailNotification.Kind.TEST, m.subject(), m.body(), m.html())).getId();
    }

    private void queue(AppUser to, EmailNotification.Kind kind, UserEmails.Message m) {
        if (!hasEmail(to)) return;
        repo.save(new EmailNotification(to.getId(), to.getEmail(), kind, m.subject(), m.body(), m.html()));
    }

    private static boolean hasEmail(AppUser u) { return u.getEmail() != null && !u.getEmail().isBlank(); }
}
