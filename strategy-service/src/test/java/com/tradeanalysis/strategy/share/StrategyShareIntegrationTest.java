package com.tradeanalysis.strategy.share;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.strategy.auth.TokenService;
import com.tradeanalysis.strategy.user.AppUserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin-only sharing (accepting creates the user's own copy), the creator shown to admins, each user's own max day
 * loss, and switching a whole portfolio to paper/live.
 */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false",
        "spring.datasource.url=jdbc:h2:mem:share-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class StrategyShareIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired AppUserRepository users;

    private JsonNode call(MockHttpServletRequestBuilder req, String asUser, int expected) throws Exception {
        if (asUser != null)
            req.header("Authorization", "Bearer " + tokens.issue(users.findByUsernameIgnoreCase(asUser).orElseThrow()).token());
        var res = mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(status().is(expected)).andReturn().getResponse();
        String body = res.getContentAsString();
        if (body.isEmpty() && res.getErrorMessage() != null) return json.createObjectNode().put("message", res.getErrorMessage());
        return body.isEmpty() ? null : json.readTree(body);
    }

    private long id(String username) { return users.findByUsernameIgnoreCase(username).orElseThrow().getId(); }

    private void user(String name, String plan) throws Exception {
        call(post("/api/users").content("{\"username\":\"" + name + "\",\"password\":\"Pass1234\"}"), "admin", 201);
        call(put("/api/users/" + id(name) + "/plan").content("{\"plan\":\"" + plan + "\"}"), "admin", 200);
    }

    private List<Long> ids(JsonNode array, String field) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asLong()));
        return out;
    }

    static long ravisCopy;

    @Test @Order(1)
    void onlyAnAdminSharesAndAcceptingCreatesTheUsersOwnCopy() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\"}"), null, 200);
        user("ravi", "GOLD");
        user("sita", "GOLD");

        call(post("/api/strategies/56/shares").content("{\"userIds\":[" + id("sita") + "]}"), "ravi", 403);   // users can't share
        call(post("/api/strategies/56/shares").content("{\"userIds\":[]}"), "admin", 400);
        call(post("/api/strategies/56/shares").content("{\"userIds\":[999999]}"), "admin", 400);
        JsonNode shared = call(post("/api/strategies/56/shares")
                .content("{\"userIds\":[" + id("ravi") + "," + id("sita") + "],\"note\":\"Try this CE\"}"), "admin", 201);
        assertThat(shared).hasSize(2);
        // sharing again keeps the open invitation
        JsonNode again = call(post("/api/strategies/56/shares").content("{\"userIds\":[" + id("ravi") + "]}"), "admin", 201);
        assertThat(again.get(0).get("id").asLong()).isEqualTo(shared.get(0).get("id").asLong());

        // ravi sees the invitation with the rules, but not the master strategy itself
        JsonNode incoming = call(get("/api/strategies/shares/incoming"), "ravi", 200);
        assertThat(incoming).hasSize(1);
        JsonNode inv = incoming.get(0);
        assertThat(inv.get("status").asText()).isEqualTo("PENDING");
        assertThat(inv.get("sharedBy").asText()).isEqualTo("admin");
        assertThat(inv.get("note").asText()).isEqualTo("Try this CE");
        assertThat(inv.get("parameters").get("directions").asText()).isEqualTo("CE");
        call(get("/api/strategies/56"), "ravi", 404);
        long before = call(get("/api/strategies"), "ravi", 200).size();

        call(post("/api/strategies/shares/" + inv.get("id").asLong() + "/accept"), "sita", 404);   // not hers
        JsonNode accepted = call(post("/api/strategies/shares/" + inv.get("id").asLong() + "/accept"), "ravi", 200);
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        ravisCopy = accepted.get("createdStrategyId").asLong();
        JsonNode copy = call(get("/api/strategies/" + ravisCopy), "ravi", 200);
        assertThat(copy.get("createdByUserId").asLong()).isEqualTo(id("ravi"));
        assertThat(copy.get("clonedFromStrategyId").asLong()).isEqualTo(56);
        assertThat(copy.get("description").asText()).contains("Shared with you by admin");
        JsonNode master = call(get("/api/strategies/56"), "admin", 200);
        assertThat(copy.get("parameters")).isEqualTo(master.get("parameters"));
        assertThat(call(get("/api/strategies"), "ravi", 200)).hasSize((int) before + 1);
        call(post("/api/strategies/shares/" + inv.get("id").asLong() + "/accept"), "ravi", 409);   // only once

        // sita declines: nothing is created
        long sitas = call(get("/api/strategies/shares/incoming"), "sita", 200).get(0).get("id").asLong();
        assertThat(call(post("/api/strategies/shares/" + sitas + "/decline"), "sita", 200).get("status").asText()).isEqualTo("DECLINED");
        assertThat(call(get("/api/strategies"), "sita", 200)).isEmpty();

        JsonNode forStrategy = call(get("/api/strategies/56/shares"), "admin", 200);
        assertThat(forStrategy).hasSize(2);
        call(get("/api/strategies/56/shares"), "ravi", 403);
    }

    @Test @Order(2)
    void theAdminWithdrawsAPendingShareAndPlanLimitsApply() throws Exception {
        user("free", "FREE");                                                   // FREE: up to 2 strategies
        long shareId = call(post("/api/strategies/55/shares").content("{\"userIds\":[" + id("free") + "]}"), "admin", 201).get(0).get("id").asLong();
        assertThat(call(delete("/api/strategies/shares/" + shareId), "admin", 200).get("status").asText()).isEqualTo("REVOKED");
        call(post("/api/strategies/shares/" + shareId + "/accept"), "free", 409);

        var body = json.createObjectNode().put("name", "f1").put("type", "NIFTY_OPENING_BREAKOUT");
        body.putObject("parameters").put("directions", "CE");
        call(post("/api/strategies").content(body.toString()), "free", 201);
        body.put("name", "f2");
        call(post("/api/strategies").content(body.toString()), "free", 201);
        long full = call(post("/api/strategies/55/shares").content("{\"userIds\":[" + id("free") + "]}"), "admin", 201).get(0).get("id").asLong();
        call(post("/api/strategies/shares/" + full + "/accept"), "free", 403);  // at the plan's limit
        assertThat(call(get("/api/strategies/shares/incoming"), "free", 200).get(0).get("status").asText()).isEqualTo("PENDING");
    }

    @Test @Order(3)
    void adminsSeeWhoCreatedEachStrategy() throws Exception {
        JsonNode all = call(get("/api/strategies"), "admin", 200);
        boolean found = false;
        for (JsonNode s : all) if (s.get("id").asLong() == ravisCopy) {
            assertThat(s.get("createdByUserId").asLong()).isEqualTo(id("ravi"));
            found = true;
        }
        assertThat(found).isTrue();
    }

    @Test @Order(4)
    void aMaxDayLossIsNeededBeforePaperOrLive() throws Exception {
        JsonNode refused = call(put("/api/trading/strategies/" + ravisCopy).content("{\"mode\":\"PAPER\"}"), "ravi", 400);
        assertThat(refused.get("message").asText()).contains("max day loss");
        assertThat(call(get("/api/trading/day-loss"), "ravi", 200).get("maxDayLoss").isNull()).isTrue();
        call(put("/api/trading/day-loss").content("{\"maxDayLoss\":0}"), "ravi", 400);
        call(put("/api/trading/day-loss").content("{\"maxDayLoss\":-100}"), "ravi", 400);
        call(put("/api/trading/day-loss").content("{}"), "ravi", 400);
        assertThat(call(put("/api/trading/day-loss").content("{\"maxDayLoss\":5000}"), "ravi", 200).get("maxDayLoss").asDouble()).isEqualTo(5000.0);
        assertThat(call(get("/api/trading/status"), "ravi", 200).get("maxDayLoss").asDouble()).isEqualTo(5000.0);
        assertThat(call(put("/api/trading/strategies/" + ravisCopy).content("{\"mode\":\"PAPER\"}"), "ravi", 200).get("mode").asText()).isEqualTo("PAPER");
        call(put("/api/trading/strategies/" + ravisCopy).content("{\"mode\":\"OFF\"}"), "ravi", 200);
        assertThat(call(get("/api/trading/day-loss"), "sita", 200).get("maxDayLoss").isNull()).isTrue();   // each user their own
    }

    @Test @Order(5)
    void aWholePortfolioSwitchesToPaperWithinThePlan() throws Exception {
        long second = call(post("/api/strategies/" + ravisCopy + "/clone").content("{\"lots\":3}"), "ravi", 201).get("id").asLong();
        long smaPortfolioStrategy = call(post("/api/strategies").content(
                "{\"name\":\"ravi sma\",\"type\":\"SMA_CROSSOVER\",\"parameters\":{\"fast\":\"10\",\"slow\":\"30\"}}"), "ravi", 201).get("id").asLong();
        long portfolio = call(post("/api/portfolios").content("{\"name\":\"Ravi P\",\"strategyIds\":[" + ravisCopy + "," + second + ","
                + smaPortfolioStrategy + "]}"), "ravi", 201).get("id").asLong();

        JsonNode paper = call(put("/api/portfolios/" + portfolio + "/trading").content("{\"mode\":\"PAPER\"}"), "ravi", 200);
        assertThat(ids(paper.get("changed"), "strategyId")).containsExactlyInAnyOrder(ravisCopy, second);
        paper.get("changed").forEach(m -> assertThat(m.get("mode").asText()).isEqualTo("PAPER"));
        assertThat(ids(paper.get("skipped"), "strategyId")).containsExactly(smaPortfolioStrategy);   // not a breakout strategy
        JsonNode modes = call(get("/api/trading/strategies"), "ravi", 200);
        modes.forEach(m -> { if (m.get("strategyId").asLong() == second) assertThat(m.get("mode").asText()).isEqualTo("PAPER"); });

        call(put("/api/portfolios/" + portfolio + "/trading").content("{\"mode\":\"LIVE\"}"), "ravi", 403);    // GOLD: paper only
        call(put("/api/portfolios/" + portfolio + "/trading").content("{}"), "ravi", 400);
        call(put("/api/portfolios/" + portfolio + "/trading").content("{\"mode\":\"PAPER\"}"), "sita", 404);  // not hers

        JsonNode off = call(put("/api/portfolios/" + portfolio + "/trading").content("{\"mode\":\"OFF\"}"), "ravi", 200);
        off.get("changed").forEach(m -> assertThat(m.get("mode").asText()).isEqualTo("OFF"));
    }

    @Test @Order(6)
    void aPlanWithoutPaperTradingCanOnlySwitchAPortfolioOff() throws Exception {
        call(put("/api/trading/day-loss").content("{\"maxDayLoss\":1000}"), "free", 200);   // any plan may set it
        long portfolio = call(get("/api/portfolios"), "ravi", 200).get(0).get("id").asLong();
        call(put("/api/users/" + id("ravi") + "/plan").content("{\"plan\":\"FREE\"}"), "admin", 200);
        call(put("/api/portfolios/" + portfolio + "/trading").content("{\"mode\":\"PAPER\"}"), "ravi", 403);
        call(put("/api/portfolios/" + portfolio + "/trading").content("{\"mode\":\"OFF\"}"), "ravi", 200);
        call(put("/api/users/" + id("ravi") + "/plan").content("{\"plan\":\"GOLD\"}"), "admin", 200);
    }
}
