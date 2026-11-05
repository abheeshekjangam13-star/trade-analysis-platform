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

    private void queue(AppUser to, EmailNotification.Kind kind, UserEmails.Message m) {
        if (!hasEmail(to)) return;
        repo.save(new EmailNotification(to.getId(), to.getEmail(), kind, m.subject(), m.body()));
    }

    private static boolean hasEmail(AppUser u) { return u.getEmail() != null && !u.getEmail().isBlank(); }
}
