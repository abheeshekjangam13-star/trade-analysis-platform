package com.tradeanalysis.strategy.notify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Without SMTP (empty spring.mail.host) emails are logged and marked LOGGED instead of failing. */
@SpringBootTest(properties = {"spring.mail.host=", "notifications.email.dispatch-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:email-nosmtp-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("it")
@DirtiesContext
class EmailDispatcherNoSmtpTest {

    @Autowired EmailNotificationRepository emails;
    @Autowired EmailDispatcher dispatcher;

    @Test
    void emailsAreLoggedWhenSmtpIsNotConfigured() {
        assertThat(dispatcher.smtpConfigured()).isFalse();
        EmailNotification n = emails.save(new EmailNotification(null, "x@example.com",
                EmailNotification.Kind.SIGNUP_RECEIVED, "Hello", "Body"));
        dispatcher.dispatchPending();
        EmailNotification after = emails.findById(n.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(EmailNotification.Status.LOGGED);
        assertThat(after.getSentAt()).isNotNull();
    }
}
