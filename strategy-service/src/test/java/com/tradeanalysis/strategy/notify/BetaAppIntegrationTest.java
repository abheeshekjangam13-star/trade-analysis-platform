package com.tradeanalysis.strategy.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.strategy.auth.TokenService;
import com.tradeanalysis.strategy.user.AppUserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Beta app: admin nominates (congratulations email), the user asks for the link on My account (link email). */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false", "spring.mail.host=",
        "notifications.email.dispatch-ms=3600000", "notifications.app-url=https://app.test",
        "spring.datasource.url=jdbc:h2:mem:beta-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class BetaAppIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired AppUserRepository users;
    @Autowired EmailNotificationRepository emails;

    private JsonNode call(MockHttpServletRequestBuilder req, String asUser, int expected) throws Exception {
        if (asUser != null)
            req.header("Authorization", "Bearer " + tokens.issue(users.findByUsernameIgnoreCase(asUser).orElseThrow()).token());
        String body = mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    private long id(String username) { return users.findByUsernameIgnoreCase(username).orElseThrow().getId(); }

    private List<EmailNotification> emailsFor(String username) {
        return emails.findByUserIdOrderByCreatedAtDesc(id(username), PageRequest.of(0, 50));
    }

    @Test @Order(1)
    void adminNominatesUsersAndTheyGetTheCongratulationsEmail() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\",\"email\":\"admin@test.local\"}"), null, 200);
        call(post("/api/users").content("{\"username\":\"ravi\",\"email\":\"ravi@example.com\",\"password\":\"TopSecret99\",\"roles\":[\"VIEWER\"]}"), "admin", 201);
        call(post("/api/users").content("{\"username\":\"meena\",\"email\":\"meena@example.com\",\"password\":\"TopSecret99\",\"roles\":[\"VIEWER\"]}"), "admin", 201);
        call(post("/api/users").content("{\"username\":\"nomail\",\"password\":\"TopSecret99\",\"roles\":[\"VIEWER\"]}"), "admin", 201);

        JsonNode r = call(post("/api/users/beta/nominate")
                .content("{\"userIds\":[" + id("ravi") + "," + id("nomail") + "],\"note\":\"Thanks for being with us\"}"), "admin", 200);

        assertThat(r.get("nominated")).extracting(JsonNode::asText).containsExactly("ravi");
        assertThat(r.get("skipped").get(0).asText()).contains("nomail", "no email");
        assertThat(r.get("totalUsers").asLong()).isEqualTo(4);
        assertThat(emailsFor("ravi")).filteredOn(e -> e.getKind() == EmailNotification.Kind.BETA_INVITE).singleElement().satisfies(e -> {
            assertThat(e.getSubject()).contains("Congratulations");
            assertThat(e.getBody()).contains("Congratulations, ravi!", "As part of SysGrow's continuous growth, we are pleased to inform you",
                    "10% of our 4 users", "works only on Android phones", "not yet available for iPhone", "Thanks for being with us",
                    "Send me the app link", "https://app.test/account#beta-app");
        });

        JsonNode overview = call(get("/api/users/beta"), "admin", 200);
        assertThat(overview.get("users").has(String.valueOf(id("ravi")))).isTrue();
        assertThat(overview.get("users").has(String.valueOf(id("meena")))).isFalse();
    }

    @Test @Order(2)
    void onlyAdminsNominate() throws Exception {
        call(post("/api/users/beta/nominate").content("{\"userIds\":[" + id("meena") + "]}"), "ravi", 403);
        call(get("/api/users/beta"), "ravi", 403);
    }

    @Test @Order(3)
    void nominatedUserRequestsTheLinkAndGetsItOnce() throws Exception {
        assertThat(call(get("/api/users/me/beta"), "ravi", 200).get("nominated").asBoolean()).isTrue();

        JsonNode mine = call(post("/api/users/me/beta/request-link"), "ravi", 200);
        assertThat(mine.get("linksSent").asInt()).isEqualTo(1);
        assertThat(mine.get("linkRequestedAt").isNull()).isFalse();
        assertThat(emailsFor("ravi")).filteredOn(e -> e.getKind() == EmailNotification.Kind.APP_INVITE).singleElement().satisfies(e -> {
            assertThat(e.getSubject()).isEqualTo("Your SysGrow beta app link");
            assertThat(e.getBody()).contains("As you requested", "Install app", "https://app.test/login");
        });

        // Asking again straight away doesn't send a second email.
        call(post("/api/users/me/beta/request-link"), "ravi", 429);
        assertThat(emailsFor("ravi")).filteredOn(e -> e.getKind() == EmailNotification.Kind.APP_INVITE).hasSize(1);
    }

    @Test @Order(4)
    void usersWhoWereNotNominatedCannotGetTheLink() throws Exception {
        assertThat(call(get("/api/users/me/beta"), "meena", 200).get("nominated").asBoolean()).isFalse();
        call(post("/api/users/me/beta/request-link"), "meena", 403);
        assertThat(emailsFor("meena")).noneMatch(e -> e.getKind() == EmailNotification.Kind.APP_INVITE);
    }
}
