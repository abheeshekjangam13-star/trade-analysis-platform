package com.tradeanalysis.strategy.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;

/**
 * Sends queued emails. With SMTP configured (spring.mail.host) each email goes out through JavaMailSender and failures
 * are retried on the next runs; without SMTP the email is written to the log and marked LOGGED, so development works
 * without a mail server.
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);
    private static final int BATCH = 20;

    private final EmailNotificationRepository repo;
    private final JavaMailSender mail;          // null when spring.mail.host is not set
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String from;

    public EmailDispatcher(EmailNotificationRepository repo, ObjectProvider<JavaMailSender> mail,
                           TransactionTemplate tx, Clock clock,
                           @Value("${spring.mail.host:}") String host,
                           @Value("${spring.mail.username:}") String username,
                           @Value("${notifications.email.from:}") String from) {
        // Spring Boot creates a JavaMailSender even for an empty host, so check the host ourselves.
        this.repo = repo; this.mail = host.isBlank() ? null : mail.getIfAvailable(); this.tx = tx; this.clock = clock;
        this.from = from.isBlank() ? username : from;
        if (this.mail == null) log.warn("EMAIL disabled: spring.mail.host is not set; emails are logged instead of sent");
    }

    public boolean smtpConfigured() { return mail != null; }
    /** The From address used (empty when neither MAIL_FROM nor MAIL_USERNAME is set). */
    public String from() { return from == null ? "" : from; }

    @Scheduled(fixedDelayString = "${notifications.email.dispatch-ms:15000}", initialDelayString = "${notifications.email.dispatch-ms:15000}")
    public void dispatchPending() {
        List<Long> ids = tx.execute(s -> repo.findByStatusInAndAttemptsLessThanOrderByCreatedAtAsc(
                EnumSet.of(EmailNotification.Status.PENDING, EmailNotification.Status.FAILED),
                EmailNotification.MAX_ATTEMPTS, PageRequest.of(0, BATCH)).stream().map(EmailNotification::getId).toList());
        if (ids != null) ids.forEach(this::dispatch);
    }

    /**
     * The email as a MIME message: plain text plus the HTML version (multipart/alternative) when there is one, so
     * clients without HTML still read it.
     */
    MimeMessagePreparator message(EmailNotification n) {
        return mime -> {
            boolean html = n.getHtmlBody() != null && !n.getHtmlBody().isBlank();
            MimeMessageHelper h = new MimeMessageHelper(mime, html, "UTF-8");
            if (!from.isBlank()) h.setFrom(from);
            h.setTo(n.getRecipient());
            h.setSubject(n.getSubject());
            if (html) h.setText(n.getBody(), n.getHtmlBody());
            else h.setText(n.getBody());
        };
    }

    /** Sends (or logs) one email and records the outcome. Each email has its own transaction. */
    public void dispatch(Long id) {
        tx.executeWithoutResult(s -> {
            EmailNotification n = repo.findById(id).orElse(null);
            if (n == null || !n.retryable()) return;
            if (mail == null) {
                log.info("EMAIL (not sent, SMTP off) to={} kind={} subject='{}'\n{}", n.getRecipient(), n.getKind(),
                        n.getSubject(), n.getBody());
                n.logged(clock.instant());
                return;
            }
            try {
                mail.send(message(n));
                n.sent(clock.instant());
                log.info("EMAIL sent id={} to={} kind={}", n.getId(), n.getRecipient(), n.getKind());
            } catch (RuntimeException e) {
                n.failed(e.getMessage());
                log.warn("EMAIL failed id={} to={} attempt={}/{}: {}", n.getId(), n.getRecipient(), n.getAttempts(),
                        EmailNotification.MAX_ATTEMPTS, e.getMessage());
            }
        });
    }
}
