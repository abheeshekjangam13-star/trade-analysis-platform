package com.tradeanalysis.strategy.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.strategy.auth.TokenService;
import com.tradeanalysis.strategy.notify.EmailNotification;
import com.tradeanalysis.strategy.notify.EmailNotificationRepository;
import com.tradeanalysis.strategy.user.AppUserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Razorpay one-time payments: server-set amount, signature checks, idempotent activation, renewal and webhooks. */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false",
        "razorpay.key-id=rzp_test_key", "razorpay.key-secret=test_secret", "razorpay.webhook-secret=hook_secret",
        "notifications.email.dispatch-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:payment-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class PaymentIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired AppUserRepository users;
    @Autowired EmailNotificationRepository emails;
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
        String body = mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    private long id(String u) { return users.findByUsernameIgnoreCase(u).orElseThrow().getId(); }
    private Instant expiry(String u) { return users.findByUsernameIgnoreCase(u).orElseThrow().getPlanExpiresAt(); }
    private String plan(String u) { return users.findByUsernameIgnoreCase(u).orElseThrow().getPlan().name(); }

    private static String verifyBody(String orderId, String paymentId, String signature) {
        return "{\"orderId\":\"" + orderId + "\",\"paymentId\":\"" + paymentId + "\",\"signature\":\"" + signature + "\"}";
    }

    private static String webhook(String event, String orderId, String paymentId, long amount) {
        return "{\"event\":\"" + event + "\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\",\"order_id\":\""
                + orderId + "\",\"amount\":" + amount + ",\"currency\":\"INR\",\"status\":\"captured\"}}}}";
    }

    private void signupAndApprove(String user, String phone) throws Exception {
        call(post("/api/auth/signup").content("{\"username\":\"" + user + "\",\"password\":\"Pass1234\",\"email\":\"" + user
                + "@example.com\",\"phone\":\"" + phone + "\",\"plan\":\"GOLD\"}"), null, 201);
        call(post("/api/users/" + id(user) + "/approve").content("{\"plan\":\"FREE\"}"), "admin", 200);
    }

    @Test @Order(1)
    void freeUserPaysForGoldAndThePlanActivatesOnce() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\"}"), null, 200);
        signupAndApprove("ravi", "9876543210");
        assertThat(call(get("/api/payments/config"), "ravi", 200).get("keyId").asText()).isEqualTo("rzp_test_key");

        JsonNode order = call(post("/api/payments/orders").content("{\"plan\":\"GOLD\"}"), "ravi", 201);
        assertThat(order.get("amountPaise").asLong()).isEqualTo(149900);
        assertThat(order.get("keyId").asText()).isEqualTo("rzp_test_key");
        assertThat(order.get("email").asText()).isEqualTo("ravi@example.com");
        verify(razorpay).createOrder(eq(149900L), eq("INR"), startsWith("u" + id("ravi") + "-GOLD-"), anyMap());
        String orderId = order.get("orderId").asText();

        call(post("/api/payments/verify").content(verifyBody(orderId, "pay_1", "forged")), "ravi", 400);
        assertThat(plan("ravi")).isEqualTo("FREE");

        String sig = RazorpaySignature.hmacHex("test_secret", orderId + "|pay_1");
        JsonNode ok = call(post("/api/payments/verify").content(verifyBody(orderId, "pay_1", sig)), "ravi", 200);
        assertThat(ok.get("status").asText()).isEqualTo("PAID");
        assertThat(plan("ravi")).isEqualTo("GOLD");
        assertThat(Duration.between(Instant.now(), expiry("ravi")).toHours()).isCloseTo(30 * 24, within(1L));
        Instant first = expiry("ravi");

        JsonNode again = call(post("/api/payments/verify").content(verifyBody(orderId, "pay_1", sig)), "ravi", 200);
        assertThat(again.get("message").asText()).isEqualTo("Already activated");
        assertThat(expiry("ravi")).isEqualTo(first);

        call(post("/api/strategies/56/clone").content("{\"lots\":2}"), "ravi", 201);   // GOLD features work now
        assertThat(emails.findByUserIdOrderByCreatedAtDesc(id("ravi"), PageRequest.of(0, 10)))
                .anySatisfy(e -> {
                    assertThat(e.getKind()).isEqualTo(EmailNotification.Kind.PAYMENT_RECEIVED);
                    assertThat(e.getBody()).contains("₹1499.00", "pay_1");
                });
    }

    @Test @Order(2)
    void othersCantVerifySomeoneElsesOrder() throws Exception {
        signupAndApprove("sita", "9123456789");
        String orderId = call(post("/api/payments/orders").content("{\"plan\":\"GOLD\"}"), "ravi", 201).get("orderId").asText();
        String sig = RazorpaySignature.hmacHex("test_secret", orderId + "|pay_x");
        call(post("/api/payments/verify").content(verifyBody(orderId, "pay_x", sig)), "sita", 404);
        assertThat(call(get("/api/payments"), "sita", 200)).isEmpty();
        assertThat(call(get("/api/payments"), "ravi", 200).size()).isEqualTo(2);
        call(get("/api/payments?all=true"), "ravi", 403);
    }

    @Test @Order(3)
    void webhookRenewsFromTheCurrentExpiryWithoutALogin() throws Exception {
        Instant before = expiry("ravi");
        String orderId = call(post("/api/payments/orders").content("{\"plan\":\"GOLD\"}"), "ravi", 201).get("orderId").asText();
        String body = webhook("payment.captured", orderId, "pay_2", 149900);

        mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", "bad")).andExpect(status().isBadRequest());
        assertThat(expiry("ravi")).isEqualTo(before);

        mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", RazorpaySignature.hmacHex("hook_secret", body))).andExpect(status().isOk());
        assertThat(Duration.between(before, expiry("ravi")).toDays()).isEqualTo(30);

        // Razorpay retries webhooks: a repeat must not extend again
        mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", RazorpaySignature.hmacHex("hook_secret", body))).andExpect(status().isOk());
        assertThat(Duration.between(before, expiry("ravi")).toDays()).isEqualTo(30);
    }

    @Test @Order(4)
    void webhookWithTheWrongAmountDoesNotActivate() throws Exception {
        String orderId = call(post("/api/payments/orders").content("{\"plan\":\"PREMIUM\"}"), "sita", 201).get("orderId").asText();
        String body = webhook("payment.captured", orderId, "pay_3", 100);
        mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", RazorpaySignature.hmacHex("hook_secret", body))).andExpect(status().isOk());
        assertThat(plan("sita")).isEqualTo("FREE");
        assertThat(call(get("/api/payments"), "sita", 200).get(0).get("status").asText()).isEqualTo("FAILED");
    }

    @Test @Order(5)
    void planRules() throws Exception {
        call(post("/api/payments/orders").content("{\"plan\":\"FREE\"}"), "ravi", 400);
        call(post("/api/payments/orders").content("{\"plan\":\"PREMIUM\"}"), "ravi", 201);   // upgrade is fine
        call(put("/api/users/" + id("sita") + "/plan").content("{\"plan\":\"PREMIUM\"}"), "admin", 200);  // no expiry
        call(post("/api/payments/orders").content("{\"plan\":\"PREMIUM\"}"), "sita", 409);  // nothing to pay
        call(post("/api/payments/orders").content("{\"plan\":\"GOLD\"}"), "sita", 409);     // would be a downgrade
        call(post("/api/payments/orders").content("{\"plan\":\"GOLD\"}"), "admin", 409);
    }

    @Test @Order(6)
    void aFailedPaymentIsEmailedOnce() throws Exception {
        String orderId = call(post("/api/payments/orders").content("{\"plan\":\"PREMIUM\"}"), "ravi", 201).get("orderId").asText();
        String body = "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_9\",\"order_id\":\""
                + orderId + "\",\"amount\":249900,\"currency\":\"INR\",\"status\":\"failed\","
                + "\"error_description\":\"Your bank declined the payment\"}}}}";
        for (int i = 0; i < 2; i++)   // Razorpay retries webhooks
            mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                    .header("X-Razorpay-Signature", RazorpaySignature.hmacHex("hook_secret", body))).andExpect(status().isOk());
        var failed = emails.findByUserIdOrderByCreatedAtDesc(id("ravi"), PageRequest.of(0, 50)).stream()
                .filter(e -> e.getKind() == EmailNotification.Kind.PAYMENT_FAILED).toList();
        assertThat(failed).singleElement().satisfies(e -> {
            assertThat(e.getSubject()).contains("PREMIUM");
            assertThat(e.getBody()).contains("₹2499.00", "Your bank declined the payment", "/account");
        });
    }
}
