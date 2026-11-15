package com.tradeanalysis.strategy.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.strategy.TestPan;
import com.tradeanalysis.strategy.auth.TokenService;
import com.tradeanalysis.strategy.user.AppUserRepository;
import com.tradeanalysis.strategy.user.PlanSales;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Only Free and Gold are on sale (the default): Premium plans are "coming shortly" at sign-up and for upgrades. */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false", "plans.on-sale=FREE,GOLD",
        "razorpay.key-id=rzp_test_key", "razorpay.key-secret=test_secret", "razorpay.webhook-secret=hook_secret",
        "notifications.email.dispatch-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:plan-sales-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class PlanSalesIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RazorpayApi razorpay;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void razorpayCreatesOrders() {
        when(razorpay.createOrder(anyLong(), eq("INR"), anyString(), anyMap()))
                .thenAnswer(inv -> new RazorpayApi.Order("order_" + SEQ.incrementAndGet(), inv.getArgument(0), "INR", "created"));
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String asUser, int expected) throws Exception {
        if (asUser != null)
            req.header("Authorization", "Bearer " + tokens.issue(users.findByUsernameIgnoreCase(asUser).orElseThrow()).token());
        var res = mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(status().is(expected)).andReturn().getResponse();
        String body = res.getContentAsString();
        if (body.isEmpty() && res.getErrorMessage() != null) return json.createObjectNode().put("message", res.getErrorMessage());
        return body.isEmpty() ? null : json.readTree(body);
    }

    private long id(String u) { return users.findByUsernameIgnoreCase(u).orElseThrow().getId(); }

    private static String signup(String user, String phone, String plan) {
        return "{\"username\":\"" + user + "\",\"password\":\"Pass1234\",\"email\":\"" + user + "@example.com\",\"phone\":\"" + phone
                + "\"," + TestPan.fields(user) + "\"acceptTerms\":true,\"plan\":\"" + plan + "\"}";
    }

    @Test @Order(1)
    void theCatalogMarksPremiumPlansComingShortly() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\"}"), null, 200);
        JsonNode plans = call(get("/api/auth/plans"), null, 200);
        assertThat(plans).hasSize(5);
        for (JsonNode p : plans) {
            boolean open = p.get("plan").asText().equals("FREE") || p.get("plan").asText().equals("GOLD");
            assertThat(p.get("available").asBoolean()).as(p.get("plan").asText()).isEqualTo(open);
        }
    }

    @Test @Order(2)
    void signUpOffersFreeAndGoldOnly() throws Exception {
        for (String plan : new String[]{"PREMIUM", "PREMIUM_PLUS", "PREMIUM_PRO"}) {
            JsonNode no = call(post("/api/auth/signup").content(signup("p" + plan.length(), "98765000" + (10 + plan.length()), plan)), null, 400);
            assertThat(no.get("message").asText()).isEqualTo(PlanSales.COMING_SHORTLY);
        }
        assertThat(users.findAll()).hasSize(1);   // only the admin: nothing was created
        call(post("/api/auth/signup").content(signup("goldie", "9876500001", "GOLD")), null, 201);
        call(post("/api/auth/signup").content(signup("freddie", "9876500002", "FREE")), null, 201);
    }

    @Test @Order(3)
    void usersCanBuyGoldButNotUpgradeToPremium() throws Exception {
        call(post("/api/users/" + id("freddie") + "/approve").content("{\"plan\":\"FREE\"}"), "admin", 200);
        assertThat(call(post("/api/payments/orders").content("{\"plan\":\"PREMIUM\"}"), "freddie", 409).get("message").asText())
                .isEqualTo(PlanSales.COMING_SHORTLY);
        call(post("/api/payments/orders").content("{\"plan\":\"GOLD\"}"), "freddie", 201);

        // Already on Premium (given by an admin): renewing it works, upgrading to a higher Premium plan doesn't.
        call(put("/api/users/" + id("freddie") + "/plan").content("{\"plan\":\"PREMIUM\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}"), "admin", 200);
        call(post("/api/payments/orders").content("{\"plan\":\"PREMIUM\"}"), "freddie", 201);
        call(post("/api/payments/orders").content("{\"plan\":\"PREMIUM_PLUS\"}"), "freddie", 409);
    }
}
