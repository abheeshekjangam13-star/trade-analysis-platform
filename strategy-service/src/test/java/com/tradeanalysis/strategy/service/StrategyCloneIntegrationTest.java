package com.tradeanalysis.strategy.service;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cloning one of your strategies with different lots; master strategies are admin-only. */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false",
        "spring.datasource.url=jdbc:h2:mem:clone-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class StrategyCloneIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired AppUserRepository users;

    private JsonNode call(MockHttpServletRequestBuilder req, String asUser, int expected) throws Exception {
        if (asUser != null)
            req.header("Authorization", "Bearer " + tokens.issue(users.findByUsernameIgnoreCase(asUser).orElseThrow()).token());
        String body = mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    private long id(String username) { return users.findByUsernameIgnoreCase(username).orElseThrow().getId(); }

    private void user(String name, String plan) throws Exception {
        call(post("/api/users").content("{\"username\":\"" + name + "\",\"password\":\"Pass1234\"}"), "admin", 201);
        call(put("/api/users/" + id(name) + "/plan").content("{\"plan\":\"" + plan + "\"}"), "admin", 200);
    }

    /** The user's own copy of master strategy #56 (same parameters, 2 lots), made with a normal create. */
    private long ownCopyOfMaster(String user, String name) throws Exception {
        JsonNode master = call(get("/api/strategies/56"), "admin", 200);
        var body = json.createObjectNode().put("name", name).put("type", master.get("type").asText()).put("description", "d");
        body.set("parameters", master.get("parameters"));
        return call(post("/api/strategies").content(body.toString()), user, 201).get("id").asLong();
    }

    static long ravis;

    @Test @Order(1)
    void goldUserClonesTheirOwnStrategyWithNewLots() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\"}"), null, 200);
        user("ravi", "GOLD");
        ravis = ownCopyOfMaster("ravi", "My CE");
        JsonNode source = call(get("/api/strategies/" + ravis), "ravi", 200);

        JsonNode c = call(post("/api/strategies/" + ravis + "/clone").content("{\"lots\":5}"), "ravi", 201);
        assertThat(c.get("id").asLong()).isNotEqualTo(ravis);
        assertThat(c.get("name").asText()).isEqualTo("My CE (5 lots)");
        assertThat(c.get("parameters").get("lots").asText()).isEqualTo("5");
        assertThat(c.get("createdByUserId").asLong()).isEqualTo(id("ravi"));
        assertThat(c.get("clonedFromStrategyId").asLong()).isEqualTo(ravis);
        assertThat(c.get("description").asText()).contains("Cloned from #" + ravis, "5 lots");
        // every other parameter is copied unchanged
        source.get("parameters").fields().forEachRemaining(e -> {
            if (!e.getKey().equals("lots")) assertThat(c.get("parameters").get(e.getKey()).asText()).isEqualTo(e.getValue().asText());
        });
        assertThat(call(get("/api/strategies/" + ravis), "ravi", 200).get("parameters").get("lots").asText()).isEqualTo("2");  // source unchanged

        JsonNode again = call(post("/api/strategies/" + ravis + "/clone").content("{\"lots\":5}"), "ravi", 201);
        assertThat(again.get("name").asText()).endsWith("(5 lots) #2");
    }

    @Test @Order(2)
    void customNameAndValidation() throws Exception {
        JsonNode c = call(post("/api/strategies/" + ravis + "/clone").content("{\"lots\":3,\"name\":\"My CE 3 lots\"}"), "ravi", 201);
        assertThat(c.get("name").asText()).isEqualTo("My CE 3 lots");
        call(post("/api/strategies/" + ravis + "/clone").content("{\"lots\":3,\"name\":\"My CE 3 lots\"}"), "ravi", 409);
        call(post("/api/strategies/" + ravis + "/clone").content("{\"lots\":0}"), "ravi", 400);
        call(post("/api/strategies/" + ravis + "/clone").content("{\"lots\":51}"), "ravi", 400);
        call(post("/api/strategies/" + ravis + "/clone").content("{}"), "ravi", 400);
        call(post("/api/strategies/999999/clone").content("{\"lots\":2}"), "ravi", 404);
    }

    @Test @Order(3)
    void masterStrategiesAreAdminOnly() throws Exception {
        call(post("/api/strategies/56/clone").content("{\"lots\":2}"), "ravi", 404);   // users can't see them
        call(post("/api/strategies/55/clone").content("{\"lots\":2}"), "ravi", 404);
        JsonNode c = call(post("/api/strategies/56/clone").content("{\"lots\":3}"), "admin", 201);
        assertThat(c.get("clonedFromStrategyId").asLong()).isEqualTo(56);
    }

    @Test @Order(4)
    void othersPrivateStrategiesCantBeClonedAndFreeUsersCloneUpToTheirLimit() throws Exception {
        user("sita", "GOLD");
        long ravisClone = call(post("/api/strategies/" + ravis + "/clone").content("{\"lots\":4}"), "ravi", 201).get("id").asLong();
        call(post("/api/strategies/" + ravisClone + "/clone").content("{\"lots\":2}"), "sita", 404);
        call(post("/api/strategies/" + ravisClone + "/clone").content("{\"lots\":2}"), "ravi", 201);   // own clone is fine

        user("free", "FREE");                                                                   // FREE: up to 2 strategies
        long frees = ownCopyOfMaster("free", "Free CE");
        call(post("/api/strategies/" + frees + "/clone").content("{\"lots\":3}"), "free", 201);
        call(post("/api/strategies/" + frees + "/clone").content("{\"lots\":4}"), "free", 403);
    }
}
