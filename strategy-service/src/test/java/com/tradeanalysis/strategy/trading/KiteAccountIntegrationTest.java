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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GOLD/PREMIUM users connect their own Kite app; PREMIUM users' live orders go to their own account. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kite-account-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
class KiteAccountIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderService orders;
    @Autowired LivePositionRepository positionRepo;
    @MockitoBean KiteApi platform;             // the admin's platform session
    @MockitoSpyBean KiteAccounts accounts;
    final KiteApi own = mock(KiteApi.class);   // the user's own Kite account

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM strategy_user_mode");
        jdbc.update("DELETE FROM live_position");
        jdbc.update("DELETE FROM trade_order");
        jdbc.update("DELETE FROM kite_account");
        jdbc.update("UPDATE trading_control SET kill_switch = FALSE, live_enabled = TRUE, max_orders_per_day = 10, max_quantity_per_order = 130, daily_loss_limit = 5000");
        doReturn(own).when(accounts).client(any());
        when(own.profile()).thenReturn(new KiteApi.Profile("ZX9999", "Priya", "ZERODHA"));
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

    private void plan(String user, String plan) throws Exception {
        call(get("/api/trading/kite-account"), user, 200);   // registers the user
        jdbc.update("UPDATE app_user SET plan = ?, plan_expires_at = NULL WHERE username = ?", plan, user);
    }

    private static final String LIVE_ORDER = """
            {"exchange":"NFO","tradingsymbol":"NIFTY26N0324150CE","transactionType":"BUY","quantity":65,
             "product":"MIS","orderType":"LIMIT","price":120.5,"mode":"LIVE","confirm":true}""";

    @Test
    void freeUsersCannotConnectAKiteAccount() throws Exception {
        plan("freeuser", "FREE");
        assertThat(call(get("/api/trading/kite-account"), "freeuser", 200).get("configured").asBoolean()).isFalse();
        call(put("/api/trading/kite-account/app").content("{\"apiKey\":\"abc123key\",\"apiSecret\":\"secret123\"}"), "freeuser", 403);
    }

    @Test
    void goldUserSavesTheirAppLogsInAndSeesTheirOwnAccount() throws Exception {
        plan("goldie", "GOLD");
        JsonNode s = call(put("/api/trading/kite-account/app").content("{\"apiKey\":\"abc123key\",\"apiSecret\":\"secret123\"}"), "goldie", 200);
        assertThat(s.get("configured").asBoolean()).isTrue();
        assertThat(s.get("connected").asBoolean()).isFalse();
        assertThat(s.toString()).doesNotContain("secret123");
        String stored = jdbc.queryForObject("SELECT api_secret_enc FROM kite_account WHERE api_key = 'abc123key'", String.class);
        assertThat(stored).isNotEqualTo("secret123");   // encrypted at rest

        String url = call(get("/api/trading/kite-account/login-url"), "goldie", 200).get("loginUrl").asText();
        assertThat(url).contains("api_key=abc123key").contains("redirect_params=account%3Dme");

        doReturn(Map.of("access_token", "tok-xyz", "user_id", "ZX9999", "user_name", "Priya"))
                .when(accounts).createSession("abc123key", "req-1", "secret123");
        s = call(post("/api/trading/kite-account/session").content("{\"requestToken\":\"req-1\"}"), "goldie", 200);
        assertThat(s.get("connected").asBoolean()).isTrue();
        assertThat(s.get("kiteUserId").asText()).isEqualTo("ZX9999");
        assertThat(s.toString()).doesNotContain("tok-xyz");
        assertThat(jdbc.queryForObject("SELECT access_token_enc FROM kite_account WHERE api_key = 'abc123key'", String.class)).isNotEqualTo("tok-xyz");
        verify(accounts).client(new KiteSession.Credentials("abc123key", "tok-xyz", "ZX9999", "Priya"));

        when(own.margins()).thenReturn(new KiteApi.Margins(50_000, 50_000, 0));
        assertThat(call(get("/api/trading/broker/margins"), "goldie", 200).get("available").asDouble()).isEqualTo(50_000);
        verify(platform, never()).margins();

        // GOLD: no live trading, even with their own account.
        call(post("/api/trading/orders").content(LIVE_ORDER), "goldie", 403);
        call(put("/api/trading/strategies/" + ownBreakout("goldie", "goldie CE")).content("{\"mode\":\"LIVE\"}"), "goldie", 403);

        // The same Kite account cannot be linked to a second user.
        plan("copycat", "GOLD");
        call(put("/api/trading/kite-account/app").content("{\"apiKey\":\"other99key\",\"apiSecret\":\"secret456\"}"), "copycat", 200);
        doReturn(Map.of("access_token", "tok-2", "user_id", "ZX9999", "user_name", "Priya"))
                .when(accounts).createSession("other99key", "req-2", "secret456");
        call(post("/api/trading/kite-account/session").content("{\"requestToken\":\"req-2\"}"), "copycat", 409);

        call(delete("/api/trading/kite-account"), "goldie", 204);
        assertThat(call(get("/api/trading/kite-account"), "goldie", 200).get("configured").asBoolean()).isFalse();
    }

    @Test
    void premiumUsersTradeLiveOnTheirOwnAccountOnly() throws Exception {
        plan("prem", "PREMIUM");
        call(post("/api/trading/orders").content(LIVE_ORDER), "prem", 403);   // no account yet

        call(put("/api/trading/kite-account/app").content("{\"apiKey\":\"prem12key\",\"apiSecret\":\"premsecret\"}"), "prem", 200);
        doReturn(Map.of("access_token", "tok-p", "user_id", "PR1111", "user_name", "Prem"))
                .when(accounts).createSession("prem12key", "req-p", "premsecret");
        call(post("/api/trading/kite-account/session").content("{\"requestToken\":\"req-p\"}"), "prem", 200);

        when(own.placeOrder(any())).thenReturn("OWN-1");
        JsonNode o = call(post("/api/trading/orders").content(LIVE_ORDER), "prem", 201);
        assertThat(o.get("status").asText()).isEqualTo("OPEN");
        assertThat(o.get("brokerOrderId").asText()).isEqualTo("OWN-1");
        verify(own).placeOrder(any());
        verify(platform, never()).placeOrder(any());

        // They can cancel their own live order (on their own account).
        call(post("/api/trading/orders/" + o.get("id").asLong() + "/cancel"), "prem", 200);
        verify(own).cancelOrder("OWN-1");

        // LIVE strategies are allowed, and the account cannot be removed while one is LIVE.
        long prems = ownBreakout("prem", "prem CE");
        call(put("/api/trading/day-loss").content("{\"maxDayLoss\":5000}"), "prem", 200);
        assertThat(call(put("/api/trading/strategies/" + prems).content("{\"mode\":\"LIVE\"}"), "prem", 200).get("mode").asText()).isEqualTo("LIVE");
        call(delete("/api/trading/kite-account"), "prem", 400);
        call(put("/api/trading/strategies/" + prems).content("{\"mode\":\"OFF\"}"), "prem", 200);
        call(delete("/api/trading/kite-account"), "prem", 204);

        // A LIVE strategy whose owner's PREMIUM plan expired: the order service refuses new live entries.
        call(put("/api/trading/kite-account/app").content("{\"apiKey\":\"prem12key\",\"apiSecret\":\"premsecret\"}"), "prem", 200);
        jdbc.update("UPDATE app_user SET plan = 'PREMIUM', plan_expires_at = TIMESTAMP '2000-01-01 00:00:00' WHERE username = 'prem'");
        Long prem = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'prem'", Long.class);
        TradeOrder refused = orders.place(new OrderRequest("NFO", "NIFTY26N0324150CE", "BUY", 65, "MIS", "LIMIT", 120.5, null),
                TradingMode.LIVE, "STRATEGY", prems, prem, false);
        assertThat(refused.getStatus()).isEqualTo(TradeOrder.Status.REJECTED);
        assertThat(refused.getMessage()).contains("PREMIUM");
    }

    @Test
    void positionHistoryCoversADateRangeAndShowsUsersOnlyTheirOwn() throws Exception {
        plan("hist_a", "GOLD"); plan("hist_b", "GOLD");
        long a = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'hist_a'", Long.class);
        long b = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'hist_b'", Long.class);
        long sa = ownBreakout("hist_a", "hist a strategy");
        java.time.LocalDate d1 = java.time.LocalDate.of(2026, 10, 1), d2 = java.time.LocalDate.of(2026, 10, 15), d3 = java.time.LocalDate.of(2026, 11, 2);
        for (var d : new java.time.LocalDate[]{d2, d1, d3})
            positionRepo.save(new LivePosition(sa, a, d, TradingMode.PAPER, "CE", "NIFTY" + d + "CE", 65,
                    d.atTime(9, 25).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant(), 24000, 23980.0));
        positionRepo.save(new LivePosition(sa, b, d2, TradingMode.LIVE, "PE", "NIFTYBPE", 65,
                d2.atTime(9, 30).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant(), 24000, 24020.0));

        JsonNode mine = call(get("/api/trading/positions/history?from=2026-10-01&to=2026-10-31"), "hist_a", 200);
        assertThat(mine).hasSize(2);                                     // d3 is outside the range; b's is not mine
        assertThat(mine.get(0).get("tradeDate").asText()).isEqualTo("2026-10-01");   // oldest first
        assertThat(mine.get(1).get("tradeDate").asText()).isEqualTo("2026-10-15");
        assertThat(call(get("/api/trading/positions/history?from=2026-10-01&to=2026-11-30"), "hist_b", 200)).hasSize(1);

        call(get("/api/trading/positions/history?from=2025-01-01&to=2026-11-30"), "hist_a", 400);   // more than 400 days
        call(get("/api/trading/positions/history?from=2026-11-30&to=2026-10-01"), "hist_a", 400);   // from after to
    }

    @Test
    void onlyAnAdminSwitchesLiveTradingAndOffBlocksLiveForEveryone() throws Exception {
        plan("liveprem", "PREMIUM");
        call(put("/api/trading/live").content("{\"enabled\":false}"), "liveprem", 403);   // not an admin
        call(get("/api/trading/kite-account"), "liveadmin", 200);                         // registers the admin
        jdbc.update("INSERT INTO app_user_role (user_id, role_id) SELECT u.id, r.id FROM app_user u, app_role r "
                + "WHERE u.username = 'liveadmin' AND r.name = 'ADMIN'");
        call(put("/api/trading/live").content("{}"), "liveadmin", 400);                    // enabled is required

        assertThat(call(put("/api/trading/live").content("{\"enabled\":false}"), "liveadmin", 200).get("liveEnabled").asBoolean()).isFalse();
        assertThat(call(get("/api/trading/status"), "liveprem", 200).get("liveTradingEnabled").asBoolean()).isFalse();
        // Off: a LIVE strategy can't be switched on, even by an admin.
        call(put("/api/trading/strategies/" + ownBreakout("liveadmin", "admin live CE")).content("{\"mode\":\"LIVE\"}"), "liveadmin", 400);

        assertThat(call(put("/api/trading/live").content("{\"enabled\":true}"), "liveadmin", 200).get("liveEnabled").asBoolean()).isTrue();
        assertThat(call(get("/api/trading/status"), "liveprem", 200).get("liveTradingEnabled").asBoolean()).isTrue();
    }
}
