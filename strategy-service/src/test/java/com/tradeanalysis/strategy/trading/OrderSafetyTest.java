package com.tradeanalysis.strategy.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Safety rules for orders, through the API, with Kite mocked (no real orders). Live trading is off on this server. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
class OrderSafetyTest {

    @TestConfiguration
    static class Clocks {
        @Bean @org.springframework.context.annotation.Primary Clock testClock() { return new MutableClock(Instant.parse("2026-11-03T05:00:00Z")); }   // Tue 10:30 IST
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean KiteApi kite;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM live_position");
        jdbc.update("DELETE FROM trade_order");
        // Live trading off (the default): other tests in this context may have turned it on.
        jdbc.update("UPDATE trading_control SET kill_switch = FALSE, live_enabled = FALSE, live_restricted = FALSE, max_orders_per_day = 10, max_quantity_per_order = 130, daily_loss_limit = 5000");
        when(kite.ltp(anyList())).thenReturn(Map.of("NFO:NIFTY26N0324150CE", 120.0));
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String user, int expected) throws Exception {
        String body = mvc.perform(req.header("X-User", user).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    /** A NIFTY_OPENING_BREAKOUT strategy owned by this user (master strategies are admin-only). */
    private long ownBreakout(String username, String name) {
        jdbc.update("INSERT INTO strategy (name, type, created_at, created_by_user_id) "
                + "SELECT ?, 'NIFTY_OPENING_BREAKOUT', CURRENT_TIMESTAMP, id FROM app_user WHERE username = ?", name, username);
        return jdbc.queryForObject("SELECT id FROM strategy WHERE name = ?", Long.class, name);
    }

    private String order(String mode, String side, int qty) {
        return """
               {"exchange":"NFO","tradingsymbol":"NIFTY26N0324150CE","transactionType":"%s","quantity":%d,
                "product":"MIS","orderType":"MARKET","mode":"%s","confirm":true}""".formatted(side, qty, mode);
    }

    @Test
    void paperOrdersFillAtTheLastPriceAndAreRecorded() throws Exception {
        JsonNode o = call(post("/api/trading/orders").content(order("PAPER", "BUY", 130)), "trader1", 201);
        assertThat(o.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(o.get("averagePrice").asDouble()).isEqualTo(120.0);
        assertThat(o.get("source").asText()).isEqualTo("MANUAL");
        assertThat(call(get("/api/trading/orders"), "trader1", 200)).hasSize(1);
        verify(kite, never()).placeOrder(any());
    }

    @Test
    void ordersNeedConfirmationAndValidFields() throws Exception {
        call(post("/api/trading/orders").content(order("PAPER", "BUY", 65).replace("\"confirm\":true", "\"confirm\":false")), "t", 400);
        call(post("/api/trading/orders").content(order("PAPER", "HOLD", 65)), "t", 400);
        call(post("/api/trading/orders").content(order("PAPER", "BUY", 65).replace("MARKET", "LIMIT")), "t", 400);   // no price
    }

    @Test
    void liveOrdersNeedAnAdminAndAreRefusedWhileLiveTradingIsDisabled() throws Exception {
        call(post("/api/trading/orders").content(order("LIVE", "BUY", 65)), "trader2", 403);
        jdbc.update("INSERT INTO app_user (username, status, created_at) VALUES ('boss', 'ACTIVE', CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO app_user_role (user_id, role_id) SELECT u.id, r.id FROM app_user u, app_role r WHERE u.username = 'boss' AND r.name = 'ADMIN'");
        JsonNode o = call(post("/api/trading/orders").content(order("LIVE", "BUY", 65)), "boss", 201);
        assertThat(o.get("status").asText()).isEqualTo("REJECTED");
        assertThat(o.get("message").asText()).contains("Live trading is turned off by the admin");
        verify(kite, never()).placeOrder(any());
        call(get("/api/trading/strategies"), "trader2", 200);   // registers trader2
        long id = ownBreakout("trader2", "trader2 CE");
        call(put("/api/trading/strategies/" + id).content("{\"mode\":\"LIVE\"}"), "boss", 400);       // server switch off
        call(put("/api/trading/strategies/" + id).content("{\"mode\":\"LIVE\"}"), "trader2", 403);    // not an admin
        call(put("/api/trading/day-loss").content("{\"maxDayLoss\":5000}"), "trader2", 200);
        assertThat(call(put("/api/trading/strategies/" + id).content("{\"mode\":\"PAPER\"}"), "trader2", 200).get("mode").asText()).isEqualTo("PAPER");
        call(put("/api/trading/strategies/" + id).content("{\"mode\":\"OFF\"}"), "trader2", 200);
    }

    @Test
    void quantityDailyOrderAndDailyLossLimitsRefuseNewRisk() throws Exception {
        assertThat(call(post("/api/trading/orders").content(order("PAPER", "BUY", 195)), "t", 201).get("message").asText())
                .contains("above the limit of 130");

        // Buy 130 @ 120, sell 130 @ 100 → realized -2,600; limit 2,000 → next new order refused.
        call(post("/api/trading/orders").content(order("PAPER", "BUY", 130)), "t", 201);
        when(kite.ltp(anyList())).thenReturn(Map.of("NFO:NIFTY26N0324150CE", 100.0));
        call(post("/api/trading/orders").content(order("PAPER", "SELL", 130)), "t", 201);
        assertThat(call(get("/api/trading/status"), "t", 200).get("paper").get("realizedToday").asDouble()).isEqualTo(-2600.0);
        jdbc.update("UPDATE trading_control SET daily_loss_limit = 2000");
        assertThat(call(post("/api/trading/orders").content(order("PAPER", "BUY", 65)), "t", 201).get("message").asText())
                .contains("Daily loss limit reached");

        jdbc.update("UPDATE trading_control SET daily_loss_limit = 100000, max_orders_per_day = 2");
        assertThat(call(post("/api/trading/orders").content(order("PAPER", "BUY", 65)), "t", 201).get("message").asText())
                .contains("Daily limit of 2 PAPER orders");
    }

    @Test
    void onlyOpeningBreakoutStrategiesCanTradeLive() throws Exception {
        call(get("/api/trading/strategies"), "t", 200);   // registers user t
        jdbc.update("INSERT INTO strategy (name, type, created_at, created_by_user_id) SELECT 'sma for trading test', 'SMA_CROSSOVER', CURRENT_TIMESTAMP, id FROM app_user WHERE username = 't'");
        long sma = jdbc.queryForObject("SELECT id FROM strategy WHERE name = 'sma for trading test'", Long.class);
        call(put("/api/trading/strategies/" + sma).content("{\"mode\":\"PAPER\"}"), "t", 400);
    }

    @Test
    void killSwitchStopsNewOrdersAndStrategies() throws Exception {
        jdbc.update("DELETE FROM strategy_user_mode");   // other tests in this database leave strategies on
        call(get("/api/trading/strategies"), "t", 200);   // registers user t
        long id = ownBreakout("t", "t CE for kill switch");
        call(put("/api/trading/day-loss").content("{\"maxDayLoss\":5000}"), "t", 200);
        call(put("/api/trading/strategies/" + id).content("{\"mode\":\"PAPER\"}"), "t", 200);
        call(post("/api/trading/kill-switch"), "t", 403);            // admins only, now that outside users can sign up
        jdbc.update("INSERT INTO app_user (username, status, created_at) SELECT 'chief', 'ACTIVE', CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'chief')");
        jdbc.update("INSERT INTO app_user_role (user_id, role_id) SELECT u.id, r.id FROM app_user u, app_role r WHERE u.username = 'chief' AND r.name = 'ADMIN' "
                + "AND NOT EXISTS (SELECT 1 FROM app_user_role x WHERE x.user_id = u.id AND x.role_id = r.id)");
        JsonNode k = call(post("/api/trading/kill-switch"), "chief", 200);
        assertThat(k.get("strategiesStopped").asInt()).isEqualTo(1);
        JsonNode modes = call(get("/api/trading/strategies"), "t", 200);
        for (JsonNode m : modes) assertThat(m.get("mode").asText()).isEqualTo("OFF");
        assertThat(call(post("/api/trading/orders").content(order("PAPER", "BUY", 65)), "t", 201).get("message").asText())
                .contains("Kill switch is on");
        call(delete("/api/trading/kill-switch"), "t", 403);          // releasing needs an admin
        assertThat(call(get("/api/trading/status"), "t", 200).get("controls").get("killSwitch").asBoolean()).isTrue();
    }

    @Test
    void statusSaysWhenKiteIsNotConnected() throws Exception {
        when(kite.profile()).thenThrow(new TradingException("Kite is not connected: log in to Kite first"));
        JsonNode s = call(get("/api/trading/status"), "t", 200);
        assertThat(s.get("kite").get("connected").asBoolean()).isFalse();
        assertThat(s.get("liveTradingEnabled").asBoolean()).isFalse();
        assertThat(s.get("marketOpen").asBoolean()).isTrue();
    }
}
