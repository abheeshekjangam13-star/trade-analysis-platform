package com.tradeanalysis.strategy.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** First-admin setup, login, lockout, passwords and admin-only user management, with real tokens (X-User not trusted). */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false", "spring.datasource.url=jdbc:h2:mem:auth-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class AuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.tradeanalysis.strategy.user.AppUserRepository users;

    private JsonNode call(MockHttpServletRequestBuilder req, String asUser, int expected) throws Exception {
        if (asUser != null)   // a real login token for that user, as the UI sends it
            req.header("Authorization", "Bearer " + tokens.issue(users.findByUsernameIgnoreCase(asUser).orElseThrow()).token());
        var res = mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(status().is(expected)).andReturn().getResponse();
        String body = res.getContentAsString();
        if (body.isEmpty() && res.getErrorMessage() != null)   // MockMvc doesn't render the error body; the server does
            return json.createObjectNode().put("message", res.getErrorMessage());
        return body.isEmpty() ? null : json.readTree(body);
    }

    @Test @Order(1)
    void firstAdminSetupOnlyOnceAndPasswordsAreHashed() throws Exception {
        assertThat(call(get("/api/auth/status"), null, 200).get("setupRequired").asBoolean()).isTrue();
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"short\"}"), null, 400);
        JsonNode r = call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\",\"fullName\":\"Admin\"}"), null, 200);
        assertThat(r.get("token").asText()).isNotBlank();
        assertThat(tokens.verify(r.get("token").asText())).contains("admin");
        assertThat(r.get("user").get("roles").toString()).contains("ADMIN");
        assertThat(r.get("user").has("passwordHash")).isFalse();          // never returned
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = 'admin'", String.class)).startsWith("$2");
        assertThat(call(get("/api/auth/status"), null, 200).get("setupRequired").asBoolean()).isFalse();
        call(post("/api/auth/setup").content("{\"username\":\"x\",\"password\":\"Secret123\"}"), null, 409);
    }

    @Test @Order(2)
    void loginReturnsAVerifiableTokenAndWrongPasswordsLockTheAccount() throws Exception {
        JsonNode ok = call(post("/api/auth/login").content("{\"username\":\"ADMIN\",\"password\":\"Secret123\"}"), null, 200);
        assertThat(tokens.verify(ok.get("token").asText())).contains("admin");
        assertThat(tokens.verify(ok.get("token").asText() + "x")).isEmpty();
        JsonNode unknown = call(post("/api/auth/login").content("{\"username\":\"nobody\",\"password\":\"Secret123\"}"), null, 401);
        JsonNode wrong = call(post("/api/auth/login").content("{\"username\":\"admin\",\"password\":\"Wrong1234\"}"), null, 401);
        assertThat(unknown.get("message").asText()).isEqualTo(wrong.get("message").asText());   // doesn't reveal usernames
        for (int i = 0; i < 4; i++) call(post("/api/auth/login").content("{\"username\":\"admin\",\"password\":\"Wrong1234\"}"), null, 401);
        call(post("/api/auth/login").content("{\"username\":\"admin\",\"password\":\"Secret123\"}"), null, 429);   // locked now
    }

    @Test @Order(3)
    void onlyAdminsManageUsersAndUsersChangeTheirOwnPassword() throws Exception {
        JsonNode ravi = call(post("/api/users").content("{\"username\":\"ravi\",\"password\":\"Ravi12345\",\"roles\":[\"TRADER\"]}"), "admin", 201);
        assertThat(ravi.get("passwordSet").asBoolean()).isTrue();
        call(post("/api/users").content("{\"username\":\"sneaky\"}"), "ravi", 403);
        call(patch("/api/users/" + ravi.get("id").asLong()).content("{\"roles\":[\"ADMIN\"]}"), "ravi", 403);
        call(get("/api/users/me").header("X-User", "admin"), null, 401);       // a bare X-User header is not a login
        call(get("/api/strategies"), null, 401);                                   // nor is nothing
        call(get("/api/users/me").header("Authorization", "Bearer not-a-token"), null, 401);
        assertThat(call(get("/api/users/me"), "ravi", 200).get("username").asText()).isEqualTo("ravi");

        JsonNode login = call(post("/api/auth/login").content("{\"username\":\"ravi\",\"password\":\"Ravi12345\"}"), null, 200);
        assertThat(login.get("user").get("username").asText()).isEqualTo("ravi");
        call(post("/api/auth/change-password").content("{\"currentPassword\":\"nope\",\"newPassword\":\"Ravi99999\"}"), "ravi", 400);
        call(post("/api/auth/change-password").content("{\"currentPassword\":\"Ravi12345\",\"newPassword\":\"Ravi99999\"}"), "ravi", 204);
        call(post("/api/auth/login").content("{\"username\":\"ravi\",\"password\":\"Ravi99999\"}"), null, 200);

        call(post("/api/users/" + ravi.get("id").asLong() + "/password").content("{\"password\":\"Reset12345\"}"), "ravi", 403);
        call(post("/api/users/" + ravi.get("id").asLong() + "/password").content("{\"password\":\"Reset12345\"}"), "admin", 204);
        call(patch("/api/users/" + ravi.get("id").asLong()).content("{\"status\":\"DISABLED\"}"), "admin", 200);
        call(post("/api/auth/login").content("{\"username\":\"ravi\",\"password\":\"Reset12345\"}"), null, 403);   // disabled
        call(get("/api/users/me"), "ravi", 403);                                    // and a token issued earlier stops working
    }

    @Test @Order(4)
    void adminsCannotLockThemselvesOut() throws Exception {
        long id = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'admin'", Long.class);
        call(patch("/api/users/" + id).content("{\"status\":\"DISABLED\"}"), "admin", 400);
        call(patch("/api/users/" + id).content("{\"roles\":[\"TRADER\"]}"), "admin", 400);
    }
}
