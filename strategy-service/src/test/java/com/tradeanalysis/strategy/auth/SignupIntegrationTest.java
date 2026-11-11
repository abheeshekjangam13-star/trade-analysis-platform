package com.tradeanalysis.strategy.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.strategy.user.AppUserRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Self sign-up, admin approval/rejection, plans and what each plan may do, with real tokens. */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false",
        "spring.datasource.url=jdbc:h2:mem:signup-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class SignupIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;

    private JsonNode call(MockHttpServletRequestBuilder req, String asUser, int expected) throws Exception {
        if (asUser != null)
            req.header("Authorization", "Bearer " + tokens.issue(users.findByUsernameIgnoreCase(asUser).orElseThrow()).token());
        var res = mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(status().is(expected)).andReturn().getResponse();
        String body = res.getContentAsString();
        if (body.isEmpty() && res.getErrorMessage() != null) return json.createObjectNode().put("message", res.getErrorMessage());
        return body.isEmpty() ? null : json.readTree(body);
    }

    private static String signup(String user, String email, String phone, String plan) {
        return "{\"username\":\"" + user + "\",\"password\":\"Pass1234\",\"email\":" + (email == null ? "null" : "\"" + email + "\"")
                + ",\"phone\":" + (phone == null ? "null" : "\"" + phone + "\"") + ",\"acceptTerms\":true,\"plan\":\"" + plan + "\"}";
    }

    private long id(String username) { return users.findByUsernameIgnoreCase(username).orElseThrow().getId(); }

    private static final String STRATEGY = """
            {"name":"%s","type":"NIFTY_OPENING_BREAKOUT","description":"d","parameters":{"directions":"CE"}}""";

    @Test @Order(1)
    void signUpNeedsEmailAndPhoneAndWaitsForApproval() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\"}"), null, 200);
        assertThat(call(get("/api/auth/plans"), null, 200).toString()).contains("GOLD", "1499", "2142", "PREMIUM", "2499", "4998", "maxStrategies", "maxBacktestDays");

        call(post("/api/auth/signup").content(signup("ravi", null, "9876543210", "GOLD")), null, 400);
        call(post("/api/auth/signup").content(signup("ravi", "ravi@example.com", null, "GOLD")), null, 400);
        call(post("/api/auth/signup").content(signup("ravi", "not-an-email", "9876543210", "GOLD")), null, 400);
        call(post("/api/auth/signup").content(signup("ravi", "ravi@example.com", "12345", "GOLD")), null, 400);
        // the Terms and Conditions must be accepted: left out or false is refused, and no account is made
        JsonNode noTerms = call(post("/api/auth/signup").content(signup("ravi", "ravi@example.com", "9876543210", "GOLD")
                .replace("\"acceptTerms\":true,", "")), null, 400);
        assertThat(noTerms.get("message").asText()).contains("Terms and Conditions");
        call(post("/api/auth/signup").content(signup("ravi", "ravi@example.com", "9876543210", "GOLD")
                .replace("\"acceptTerms\":true", "\"acceptTerms\":false")), null, 400);
        assertThat(users.findByUsernameIgnoreCase("ravi")).isEmpty();

        JsonNode r = call(post("/api/auth/signup").content(signup("ravi", "Ravi@Example.com", "098765 43210", "GOLD")), null, 201);
        assertThat(r.get("status").asText()).isEqualTo("PENDING");
        assertThat(r.has("token")).isFalse();
        assertThat(jdbc.queryForObject("SELECT phone FROM app_user WHERE username = 'ravi'", String.class)).isEqualTo("+919876543210");
        assertThat(jdbc.queryForObject("SELECT plan FROM app_user WHERE username = 'ravi'", String.class)).isEqualTo("FREE");   // until approved
        assertThat(users.findByUsernameIgnoreCase("ravi").orElseThrow().getTermsVersion()).isEqualTo(com.tradeanalysis.strategy.user.Terms.VERSION);
        assertThat(users.findByUsernameIgnoreCase("ravi").orElseThrow().getTermsAcceptedAt()).isNotNull();

        call(post("/api/auth/signup").content(signup("ravi2", "ravi@example.com", "9876500000", "FREE")), null, 409);   // email
        call(post("/api/auth/signup").content(signup("ravi3", "r3@example.com", "+91 98765 43210", "FREE")), null, 409); // phone
        call(post("/api/auth/signup").content(signup("RAVI", "r4@example.com", "9876500001", "FREE")), null, 409);       // username

        JsonNode pending = call(post("/api/auth/login").content("{\"username\":\"ravi\",\"password\":\"Pass1234\"}"), null, 403);
        assertThat(pending.get("message").asText()).contains("waiting for admin approval");
        call(post("/api/auth/login").content("{\"username\":\"ravi\",\"password\":\"Wrong1234\"}"), null, 401);   // status only after the right password
    }

    @Test @Order(2)
    void adminApprovesWithAPlanAndGoldCanBacktestButNotManageUsers() throws Exception {
        assertThat(call(get("/api/users/pending"), "admin", 200).toString()).contains("ravi").contains("\"requestedPlan\":\"GOLD\"");
        call(get("/api/users/me"), "ravi", 403);                                    // a pending user's token gets nothing
        call(post("/api/users/" + id("ravi") + "/approve").content("{}"), "ravi", 403);

        String expiry = Instant.now().plus(30, ChronoUnit.DAYS).toString();
        JsonNode u = call(post("/api/users/" + id("ravi") + "/approve").content("{\"expiresAt\":\"" + expiry + "\",\"note\":\"UPI paid\"}"), "admin", 200);
        assertThat(u.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(u.get("plan").asText()).isEqualTo("GOLD");                       // the plan they asked for
        assertThat(u.get("effectivePlan").asText()).isEqualTo("GOLD");
        assertThat(u.get("roles").toString()).contains("TRADER");
        call(post("/api/users/" + id("ravi") + "/approve").content("{}"), "admin", 409);   // only once

        call(post("/api/auth/login").content("{\"username\":\"ravi\",\"password\":\"Pass1234\"}"), null, 200);
        long ravis = call(post("/api/strategies").content(STRATEGY.formatted("ravi gold strategy")), "ravi", 201).get("id").asLong();
        call(put("/api/trading/day-loss").content("{\"maxDayLoss\":5000}"), "ravi", 200);
        call(put("/api/trading/strategies/" + ravis).content("{\"mode\":\"PAPER\"}"), "ravi", 200);      // paper trading
        call(put("/api/trading/strategies/" + ravis).content("{\"mode\":\"LIVE\"}"), "ravi", 403);       // live needs PREMIUM
        call(get("/api/users"), "ravi", 403);
        call(get("/api/users/" + id("admin")), "ravi", 403);
        call(get("/api/trading/broker/margins"), "ravi", 400);                       // no own Kite account connected yet
        call(post("/api/trading/kill-switch"), "ravi", 403);
        assertThat(call(get("/api/users/me"), "ravi", 200).get("phone").asText()).isEqualTo("+919876543210");
    }

    @Test @Order(3)
    void freeHasLimitsAndAnExpiredPlanFallsBackToFree() throws Exception {
        call(post("/api/auth/signup").content(signup("sita", "sita@example.com", "9123456789", "FREE")), null, 201);
        call(post("/api/users/" + id("sita") + "/approve").content("{}"), "admin", 200);
        call(get("/api/strategies"), "sita", 200);
        long sitas = call(post("/api/strategies").content(STRATEGY.formatted("sita strategy 1")), "sita", 201).get("id").asLong();
        call(post("/api/strategies").content(STRATEGY.formatted("sita strategy 2")), "sita", 201);
        JsonNode full = call(post("/api/strategies").content(STRATEGY.formatted("sita strategy 3")), "sita", 403);
        assertThat(full.get("message").asText()).contains("reached the limit", "FREE", "2 strategies", "Upgrade", "GOLD", "6 strategies");
        call(post("/api/strategies/" + sitas + "/clone").content("{\"lots\":2}"), "sita", 403);   // clones count too
        JsonNode tooLong = call(post("/api/backtests?strategyId=" + sitas + "&symbol=NIFTY%2050&lookbackDays=91"), "sita", 403);
        assertThat(tooLong.get("message").asText()).contains("90 days");
        JsonNode paper = call(put("/api/trading/strategies/" + sitas).content("{\"mode\":\"PAPER\"}"), "sita", 403);
        assertThat(paper.get("message").asText()).contains("FREE").contains("GOLD");
        call(post("/api/auth/change-password").content("{\"currentPassword\":\"Pass1234\",\"newPassword\":\"Pass12345\"}"), "sita", 204);

        call(put("/api/users/" + id("sita") + "/plan").content("{\"plan\":\"GOLD\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}"), "admin", 400);
        JsonNode upgraded = call(put("/api/users/" + id("sita") + "/plan").content("{\"plan\":\"PREMIUM\"}"), "admin", 200);
        assertThat(upgraded.get("roles").toString()).contains("PREMIUM").doesNotContain("VIEWER");
        call(post("/api/strategies").content(STRATEGY.formatted("sita premium strategy")), "sita", 201);

        jdbc.update("UPDATE app_user SET plan_expires_at = ? WHERE username = 'sita'", java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
        assertThat(call(get("/api/users/me"), "sita", 200).get("effectivePlan").asText()).isEqualTo("FREE");
        call(post("/api/strategies").content(STRATEGY.formatted("sita expired strategy")), "sita", 403);   // 3 > FREE's 2
        call(post("/api/backtests?strategyId=" + sitas + "&symbol=NIFTY%2050&lookbackDays=365"), "sita", 403);
    }

    @Test @Order(4)
    void adminRejectsASignUp() throws Exception {
        call(post("/api/auth/signup").content(signup("spam", "spam@example.com", "9000000001", "PREMIUM")), null, 201);
        call(post("/api/users/" + id("spam") + "/reject").content("{\"note\":\"no payment\"}"), "admin", 200);
        call(post("/api/users/" + id("spam") + "/reject").content("{}"), "admin", 409);
        JsonNode r = call(post("/api/auth/login").content("{\"username\":\"spam\",\"password\":\"Pass1234\"}"), null, 403);
        assertThat(r.get("message").asText()).contains("not approved");
        assertThat(call(get("/api/users/pending"), "admin", 200)).isEmpty();
    }

    @Test @Order(5)
    void onlyAdminsDeleteUsersAndUsersWithDataAreKept() throws Exception {
        call(post("/api/auth/signup").content(signup("temp", "temp@example.com", "9876500077", "FREE")), null, 201);
        long temp = id("temp");
        call(delete("/api/users/" + temp), "ravi", 403);                       // not an admin
        call(delete("/api/users/" + id("admin")), "admin", 409);               // not yourself
        JsonNode keep = call(delete("/api/users/" + id("ravi")), "admin", 409); // owns strategies: disable instead
        assertThat(keep.get("message").asText()).contains("DISABLED");
        assertThat(users.findByUsernameIgnoreCase("ravi")).isPresent();

        call(delete("/api/users/" + temp), "admin", 204);
        assertThat(users.findByUsernameIgnoreCase("temp")).isEmpty();
        call(delete("/api/users/" + temp), "admin", 404);
    }
}
