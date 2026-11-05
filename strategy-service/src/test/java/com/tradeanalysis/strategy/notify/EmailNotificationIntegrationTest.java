package com.tradeanalysis.strategy.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.strategy.auth.TokenService;
import com.tradeanalysis.strategy.user.AppUserRepository;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sign-up, approval and rejection queue emails (with the admin's remarks); the dispatcher sends, retries and records them. */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false",
        "spring.mail.host=smtp.test", "notifications.email.from=alerts@test.local",
        "notifications.email.dispatch-ms=3600000", "notifications.app-url=https://app.test",
        "spring.datasource.url=jdbc:h2:mem:email-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class EmailNotificationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired AppUserRepository users;
    @Autowired EmailNotificationRepository emails;
    @Autowired EmailDispatcher dispatcher;
    @MockitoBean JavaMailSender mail;

    private JsonNode call(MockHttpServletRequestBuilder req, String asUser, int expected) throws Exception {
        if (asUser != null)
            req.header("Authorization", "Bearer " + tokens.issue(users.findByUsernameIgnoreCase(asUser).orElseThrow()).token());
        String body = mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    private static String signup(String user, String email, String phone) {
        return "{\"username\":\"" + user + "\",\"password\":\"Pass1234\",\"email\":\"" + email + "\",\"phone\":\"" + phone
                + "\",\"fullName\":\"" + user.toUpperCase() + "\",\"plan\":\"GOLD\"}";
    }

    private long id(String username) { return users.findByUsernameIgnoreCase(username).orElseThrow().getId(); }

    private List<EmailNotification> emailsFor(String username) {
        return emails.findByUserIdOrderByCreatedAtDesc(id(username), PageRequest.of(0, 50));
    }

    @Test @Order(1)
    void signupEmailsTheUserAndTheAdmins() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\",\"email\":\"admin@test.local\"}"), null, 200);
        call(post("/api/auth/signup").content(signup("ravi", "ravi@example.com", "9876543210")), null, 201);

        assertThat(emailsFor("ravi")).singleElement().satisfies(e -> {
            assertThat(e.getKind()).isEqualTo(EmailNotification.Kind.SIGNUP_RECEIVED);
            assertThat(e.getRecipient()).isEqualTo("ravi@example.com");
            assertThat(e.getStatus()).isEqualTo(EmailNotification.Status.PENDING);
            assertThat(e.getBody()).contains("Hi RAVI", "GOLD", "₹999");
        });
        assertThat(emailsFor("admin")).singleElement().satisfies(e -> {
            assertThat(e.getKind()).isEqualTo(EmailNotification.Kind.SIGNUP_ADMIN_ALERT);
            assertThat(e.getSubject()).contains("ravi");
            assertThat(e.getBody()).contains("ravi@example.com", "+919876543210", "https://app.test/users");
        });
    }

    @Test @Order(2)
    void approvalEmailCarriesThePlanAndRemarks() throws Exception {
        call(post("/api/users/" + id("ravi") + "/approve").content("{\"note\":\"UPI payment received, welcome!\"}"), "admin", 200);
        EmailNotification e = emailsFor("ravi").get(0);
        assertThat(e.getKind()).isEqualTo(EmailNotification.Kind.ACCOUNT_APPROVED);
        assertThat(e.getBody()).contains("approved", "Plan: GOLD", "Remarks from the admin:", "UPI payment received, welcome!",
                "https://app.test/login");
    }

    @Test @Order(3)
    void rejectionEmailCarriesRemarks() throws Exception {
        call(post("/api/auth/signup").content(signup("spam", "spam@example.com", "9000000001")), null, 201);
        call(post("/api/users/" + id("spam") + "/reject").content("{\"note\":\"Payment not received\"}"), "admin", 200);
        EmailNotification e = emailsFor("spam").get(0);
        assertThat(e.getKind()).isEqualTo(EmailNotification.Kind.ACCOUNT_REJECTED);
        assertThat(e.getBody()).contains("not approved", "Remarks from the admin:", "Payment not received");
    }

    @Test @Order(4)
    void noRemarksSectionWithoutANote() throws Exception {
        call(post("/api/auth/signup").content(signup("quiet", "quiet@example.com", "9000000002")), null, 201);
        call(post("/api/users/" + id("quiet") + "/approve").content("{}"), "admin", 200);
        assertThat(emailsFor("quiet").get(0).getBody()).doesNotContain("Remarks");
    }

    @Test @Order(5)
    void adminCreatedUserGetsAnEmailWithoutThePassword() throws Exception {
        call(post("/api/users").content("{\"username\":\"meera\",\"email\":\"meera@example.com\",\"password\":\"TopSecret99\"}"), "admin", 201);
        EmailNotification e = emailsFor("meera").get(0);
        assertThat(e.getKind()).isEqualTo(EmailNotification.Kind.ACCOUNT_CREATED);
        assertThat(e.getBody()).contains("meera").doesNotContain("TopSecret99");
    }

    @Test @Order(6)
    void dispatcherSendsAndRetriesFailures() throws Exception {
        doThrow(new MailSendException("SMTP down")).doNothing().when(mail).send(any(SimpleMailMessage.class));
        long failing = emailsFor("ravi").get(0).getId();
        dispatcher.dispatch(failing);
        assertThat(emails.findById(failing).orElseThrow()).satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(EmailNotification.Status.FAILED);
            assertThat(e.getAttempts()).isEqualTo(1);
            assertThat(e.getLastError()).contains("SMTP down");
        });

        dispatcher.dispatchPending();
        assertThat(emails.findAll()).allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(EmailNotification.Status.SENT));

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeastOnce()).send(sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(m -> assertThat(m.getFrom()).isEqualTo("alerts@test.local"));
        assertThat(sent.getAllValues()).anySatisfy(m -> {
            assertThat(m.getTo()).containsExactly("spam@example.com");
            assertThat(m.getText()).contains("Payment not received");
        });
    }

    @Test @Order(7)
    void onlyAdminsSeeTheEmailLog() throws Exception {
        assertThat(call(get("/api/notifications/emails"), "admin", 200).size()).isGreaterThanOrEqualTo(7);
        call(get("/api/notifications/emails"), "ravi", 403);
        assertThat(call(get("/api/notifications/email/status"), "admin", 200).get("smtpConfigured").asBoolean()).isTrue();
    }
}
