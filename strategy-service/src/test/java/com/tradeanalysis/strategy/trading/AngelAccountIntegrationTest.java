package com.tradeanalysis.strategy.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GOLD/PREMIUM users can connect their own Angel One app instead of Kite; PREMIUM users' live orders go to it. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:angel-account-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
class AngelAccountIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderService orders;
    @MockitoBean KiteApi platform;              // the admin's platform Kite session
    @MockitoSpyBean AngelAccounts angel;
    @MockitoSpyBean KiteAccounts kite;
    final KiteApi own = mock(KiteApi.class);    // the user's own Angel One account
    final KiteApi ownKite = mock(KiteApi.class);

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM strategy_user_mode");
        jdbc.update("DELETE FROM live_position");
        jdbc.update("DELETE FROM trade_order");
        jdbc.update("DELETE FROM kite_account");
        jdbc.update("DELETE FROM angel_account");
        jdbc.update("UPDATE trading_control SET kill_switch = FALSE, live_enabled = TRUE, live_restricted = FALSE, live_restricted_message = NULL, max_orders_per_day = 10, max_quantity_per_order = 130, daily_loss_limit = 5000");
        doReturn(own).when(angel).client(any());
        doReturn(ownKite).when(kite).client(any());
        when(own.profile()).thenReturn(new KiteApi.Profile("A123456", "Ravi", "ANGELONE"));
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String user, int expected) throws Exception {
        String body = mvc.perform(req.header("X-User", user).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    private void plan(String user, String plan) throws Exception {
        call(get("/api/trading/angel-account"), user, 200);   // registers the user
        jdbc.update("UPDATE app_user SET plan = ?, plan_expires_at = NULL WHERE username = ?", plan, user);
        if (!plan.equals("FREE") && !plan.equals("GOLD"))
            jdbc.update("UPDATE app_user SET live_orders_left = 100, live_orders_granted = 100, live_orders_warned = FALSE WHERE username = ?", user);
    }

    private static final String LIVE_ORDER = """
            {"exchange":"NFO","tradingsymbol":"NIFTY26N0324150CE","transactionType":"BUY","quantity":65,
             "product":"MIS","orderType":"LIMIT","price":120.5,"mode":"LIVE","confirm":true}""";

    @Test
    void freeUsersCannotConnectAnAngelOneAccount() throws Exception {
        plan("afree", "FREE");
        assertThat(call(get("/api/trading/angel-account"), "afree", 200).get("configured").asBoolean()).isFalse();
        call(put("/api/trading/angel-account/app").content("{\"apiKey\":\"smartKey1\"}"), "afree", 403);
    }

    @Test
    void goldUserSavesTheirAppLogsInAndSeesTheirOwnAccount() throws Exception {
        plan("agold", "GOLD");
        JsonNode s = call(put("/api/trading/angel-account/app").content("{\"apiKey\":\"smartKey1\"}"), "agold", 200);
        assertThat(s.get("configured").asBoolean()).isTrue();
        assertThat(s.get("connected").asBoolean()).isFalse();

        String url = call(get("/api/trading/angel-account/login-url"), "agold", 200).get("loginUrl").asText();
        assertThat(url).contains("publisher-login").contains("api_key=smartKey1").contains("state=me");

        s = call(post("/api/trading/angel-account/session")
                .content("{\"authToken\":\"Bearer jwt-abc\",\"refreshToken\":\"ref-1\",\"feedToken\":\"feed-1\"}"), "agold", 200);
        assertThat(s.get("connected").asBoolean()).isTrue();
        assertThat(s.get("clientCode").asText()).isEqualTo("A123456");
        assertThat(s.toString()).doesNotContain("jwt-abc").doesNotContain("ref-1");
        assertThat(jdbc.queryForObject("SELECT jwt_token_enc FROM angel_account WHERE api_key = 'smartKey1'", String.class))
                .isNotNull().doesNotContain("jwt-abc");   // encrypted at rest
        verify(angel, atLeastOnce()).client(new AngelOneRestApi.Credentials("smartKey1", "jwt-abc", null));

        // Broker views and the status come from their Angel One account.
        when(own.margins()).thenReturn(new KiteApi.Margins(40_000, 40_000, 0));
        assertThat(call(get("/api/trading/broker/margins"), "agold", 200).get("available").asDouble()).isEqualTo(40_000);
        verify(platform, never()).margins();
        JsonNode st = call(get("/api/trading/status"), "agold", 200);
        assertThat(st.get("kite").get("broker").asText()).isEqualTo("ANGEL_ONE");
        assertThat(st.get("kite").get("ownAccount").asBoolean()).isTrue();

        // GOLD: no live trading.
        call(post("/api/trading/orders").content(LIVE_ORDER), "agold", 403);

        // One broker per user: no Kite app while Angel One is connected.
        call(put("/api/trading/kite-account/app").content("{\"apiKey\":\"abc123key\",\"apiSecret\":\"secret123\"}"), "agold", 400);

        // The same Angel One client cannot be linked to a second user.
        plan("acopy", "GOLD");
        call(put("/api/trading/angel-account/app").content("{\"apiKey\":\"smartKey2\"}"), "acopy", 200);
        call(post("/api/trading/angel-account/session").content("{\"authToken\":\"jwt-2\"}"), "acopy", 409);

        call(delete("/api/trading/angel-account"), "agold", 204);
        assertThat(call(get("/api/trading/angel-account"), "agold", 200).get("configured").asBoolean()).isFalse();
    }

    @Test
    void aKiteUserCannotAlsoConnectAngelOne() throws Exception {
        plan("akite", "GOLD");
        call(put("/api/trading/kite-account/app").content("{\"apiKey\":\"abc123key\",\"apiSecret\":\"secret123\"}"), "akite", 200);
        call(put("/api/trading/angel-account/app").content("{\"apiKey\":\"smartKey3\"}"), "akite", 400);
        call(delete("/api/trading/kite-account"), "akite", 204);
        call(put("/api/trading/angel-account/app").content("{\"apiKey\":\"smartKey3\"}"), "akite", 200);
    }

    @Test
    void premiumUsersTradeLiveOnTheirAngelOneAccount() throws Exception {
        plan("aprem", "PREMIUM");
        call(post("/api/trading/orders").content(LIVE_ORDER), "aprem", 403);   // no account yet

        call(put("/api/trading/angel-account/app").content("{\"apiKey\":\"premKey1\"}"), "aprem", 200);
        call(post("/api/trading/angel-account/session").content("{\"authToken\":\"jwt-p\"}"), "aprem", 200);

        when(own.placeOrder(any())).thenReturn("AO:N:2010200001:uniq-1");
        JsonNode o = call(post("/api/trading/orders").content(LIVE_ORDER), "aprem", 201);
        assertThat(o.get("status").asText()).isEqualTo("OPEN");
        assertThat(o.get("brokerOrderId").asText()).isEqualTo("AO:N:2010200001:uniq-1");
        assertThat(o.get("message").asText()).isEqualTo("Sent to Angel One");
        verify(platform, never()).placeOrder(any());
        verify(ownKite, never()).placeOrder(any());

        // The order sync reads it from Angel One.
        when(own.order("AO:N:2010200001:uniq-1")).thenReturn(new KiteApi.BrokerOrder("2010200001", "COMPLETE", "NIFTY26N0324150CE",
                "NFO", "BUY", 65, 65, 121.0, 120.5, "LIMIT", "MIS", "", "TAPM", "now"));
        orders.syncLiveOrders();
        assertThat(jdbc.queryForObject("SELECT status FROM trade_order WHERE broker_order_id = 'AO:N:2010200001:uniq-1'", String.class))
                .isEqualTo("COMPLETE");

        // Cancel goes to Angel One too.
        when(own.placeOrder(any())).thenReturn("AO:N:2010200002:uniq-2");
        JsonNode o2 = call(post("/api/trading/orders").content(LIVE_ORDER), "aprem", 201);
        call(post("/api/trading/orders/" + o2.get("id").asLong() + "/cancel"), "aprem", 200);
        verify(own).cancelOrder("AO:N:2010200002:uniq-2");
        verify(ownKite, never()).cancelOrder(anyString());
    }
}
