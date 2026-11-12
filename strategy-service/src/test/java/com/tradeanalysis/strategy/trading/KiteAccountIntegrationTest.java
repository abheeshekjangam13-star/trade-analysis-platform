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
        // Set straight in the database, so credit the plan's included live orders as a payment would.
        if (!plan.equals("FREE") && !plan.equals("GOLD"))
            jdbc.update("UPDATE app_user SET live_orders_left = 100, live_orders_granted = 100, live_orders_warned = FALSE WHERE username = ?", user);
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

    @Test
    void premiumTiersLimitLiveStrategiesAndLots() throws Exception {
        plan("tier", "PREMIUM");
        call(put("/api/trading/kite-account/app").content("{\"apiKey\":\"tier12key\",\"apiSecret\":\"tiersecret\"}"), "tier", 200);
        doReturn(Map.of("access_token", "tok-t", "user_id", "TR1111", "user_name", "Tier"))
                .when(accounts).createSession("tier12key", "req-t", "tiersecret");
        call(post("/api/trading/kite-account/session").content("{\"requestToken\":\"req-t\"}"), "tier", 200);
        call(put("/api/trading/day-loss").content("{\"maxDayLoss\":20000}"), "tier", 200);
        jdbc.update("UPDATE app_user SET plan = 'PREMIUM_PRO', plan_expires_at = NULL WHERE username = 'tier'");   // room to create 9
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 9; i++) ids.add(ownBreakout("tier", "tier CE " + i));
        jdbc.update("UPDATE app_user SET plan = 'PREMIUM' WHERE username = 'tier'");

        // Premium: 2 lots per order. A strategy with 4 lots can't go LIVE.
        long big = ids.get(8);
        jdbc.update("INSERT INTO strategy_param (strategy_id, param_key, param_value) VALUES (?, 'lots', '4')", big);
        call(put("/api/trading/strategies/" + big).content("{\"mode\":\"LIVE\"}"), "tier", 400);
        // Premium: 8 live strategies. The first 8 go LIVE; "Trade all" style bulk reports the rest.
        for (int i = 0; i < 8; i++)
            assertThat(call(put("/api/trading/strategies/" + ids.get(i)).content("{\"mode\":\"LIVE\"}"), "tier", 200).get("mode").asText()).isEqualTo("LIVE");
        jdbc.update("DELETE FROM strategy_param WHERE strategy_id = ? AND param_key = 'lots'", big);
        call(put("/api/trading/strategies/" + big).content("{\"mode\":\"LIVE\"}"), "tier", 400);   // a 9th live strategy

        // Orders: more than 2 lots (130) is refused on Premium; Premium Plus allows 4 lots (260).
        Long tier = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'tier'", Long.class);
        when(own.placeOrder(any())).thenReturn("T-1");
        OrderRequest four = new OrderRequest("NFO", "NIFTY26N0324150CE", "BUY", 260, "MIS", "LIMIT", 120.5, null);
        jdbc.update("UPDATE trading_control SET max_quantity_per_order = 1000");
        TradeOrder refused = orders.place(four, TradingMode.LIVE, "MANUAL", null, tier, false);
        assertThat(refused.getStatus()).isEqualTo(TradeOrder.Status.REJECTED);
        assertThat(refused.getMessage()).contains("Premium plan trades live up to 2 lots");
        jdbc.update("UPDATE app_user SET plan = 'PREMIUM_PLUS' WHERE username = 'tier'");
        assertThat(orders.place(four, TradingMode.LIVE, "MANUAL", null, tier, false).getStatus()).isEqualTo(TradeOrder.Status.OPEN);

        // A downgrade with more LIVE strategies than the plan allows: the later ones stop entering.
        jdbc.update("UPDATE app_user SET plan = 'PREMIUM' WHERE username = 'tier'");
        jdbc.update("UPDATE strategy_user_mode SET mode = 'LIVE' WHERE strategy_id = ?", ids.get(7));
        jdbc.update("INSERT INTO strategy_user_mode (strategy_id, user_id, mode, product, updated_at) "
                + "SELECT ?, id, 'LIVE', 'MIS', CURRENT_TIMESTAMP FROM app_user WHERE username = 'tier'", big);
        OrderRequest two = new OrderRequest("NFO", "NIFTY26N0324150CE", "BUY", 130, "MIS", "LIMIT", 120.5, null);
        TradeOrder ninth = orders.place(two, TradingMode.LIVE, "STRATEGY", big, tier, false);
        assertThat(ninth.getStatus()).isEqualTo(TradeOrder.Status.REJECTED);
        assertThat(ninth.getMessage()).contains("allows 8 strategies trading live");
        assertThat(orders.place(two, TradingMode.LIVE, "STRATEGY", ids.get(0), tier, false).getStatus()).isEqualTo(TradeOrder.Status.OPEN);
    }

    @Autowired LiveOrderBilling billing;

    @Test
    void liveTradingNeedsCreditAndStopsWhenItRunsOut() throws Exception {
        plan("payer", "PREMIUM");
        call(put("/api/trading/kite-account/app").content("{\"apiKey\":\"pay12key\",\"apiSecret\":\"paysecret\"}"), "payer", 200);
        doReturn(Map.of("access_token", "tok-y", "user_id", "PY1111", "user_name", "Payer"))
                .when(accounts).createSession("pay12key", "req-y", "paysecret");
        call(post("/api/trading/kite-account/session").content("{\"requestToken\":\"req-y\"}"), "payer", 200);
        call(put("/api/trading/day-loss").content("{\"maxDayLoss\":20000}"), "payer", 200);
        Long payer = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'payer'", Long.class);
        jdbc.update("UPDATE app_user SET email = 'payer@example.com' WHERE id = ?", payer);   // so the notice can be emailed
        jdbc.update("UPDATE app_user SET live_orders_left = 32, live_orders_granted = 100 WHERE id = ?", payer);   // 68 of 100 used
        when(own.placeOrder(any())).thenReturn("P-1");
        OrderRequest entry = new OrderRequest("NFO", "NIFTY26N0324150CE", "BUY", 65, "MIS", "LIMIT", 120.5, null);

        // Each accepted entry uses an included order; the 70% mark (70 used) emails the user once.
        orders.place(entry, TradingMode.LIVE, "MANUAL", null, payer, false);
        assertThat(billing.balance(payer).liveOrdersLeft()).isEqualTo(31);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_notification WHERE user_id = ? AND kind = 'LIVE_ORDERS_LOW'", Integer.class, payer)).isZero();
        orders.place(entry, TradingMode.LIVE, "MANUAL", null, payer, false);
        assertThat(billing.balance(payer).low()).isTrue();
        orders.place(entry, TradingMode.LIVE, "MANUAL", null, payer, false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_notification WHERE user_id = ? AND kind = 'LIVE_ORDERS_LOW'", Integer.class, payer)).isEqualTo(1);
        // Exits and paper orders are never charged.
        orders.place(new OrderRequest("NFO", "NIFTY26N0324150CE", "SELL", 65, "MIS", "LIMIT", 125.0, null), TradingMode.LIVE, "MANUAL", null, payer, true);
        assertThat(billing.balance(payer).liveOrdersLeft()).isEqualTo(29);

        // Nothing left: new live orders are refused, a strategy can't go LIVE, and the status says so.
        jdbc.update("UPDATE app_user SET live_orders_left = 0, credit_paise = 500 WHERE id = ?", payer);
        TradeOrder refused = orders.place(entry, TradingMode.LIVE, "MANUAL", null, payer, false);
        assertThat(refused.getStatus()).isEqualTo(TradeOrder.Status.REJECTED);
        assertThat(refused.getMessage()).startsWith("No live orders left");
        call(put("/api/trading/strategies/" + ownBreakout("payer", "payer CE")).content("{\"mode\":\"LIVE\"}"), "payer", 402);
        assertThat(call(get("/api/trading/billing"), "payer", 200).get("canTradeLive").asBoolean()).isFalse();

        // With credits, each order costs ₹10 from them; an order Kite rejects later is refunded.
        jdbc.update("UPDATE app_user SET credit_paise = 2000 WHERE id = ?", payer);
        TradeOrder paid = orders.place(entry, TradingMode.LIVE, "MANUAL", null, payer, false);
        assertThat(billing.balance(payer).creditPaise()).isEqualTo(1000);
        when(own.order("P-1")).thenReturn(new KiteApi.BrokerOrder("P-1", "REJECTED", "NIFTY26N0324150CE", "NFO", "BUY", 65, 0,
                0, 120.5, "LIMIT", "MIS", null, "TAPM", "Insufficient margin"));
        jdbc.update("UPDATE trade_order SET status = 'COMPLETE' WHERE user_id = ? AND id <> ? AND status = 'OPEN'", payer, paid.getId());
        orders.syncLiveOrders();
        assertThat(billing.balance(payer).creditPaise()).isEqualTo(2000);

        // Admins add orders or credits by hand; others can't.
        call(post("/api/trading/billing/users/" + payer).content("{\"orders\":10}"), "payer", 403);
        call(get("/api/trading/kite-account"), "billadmin", 200);
        jdbc.update("INSERT INTO app_user_role (user_id, role_id) SELECT u.id, r.id FROM app_user u, app_role r "
                + "WHERE u.username = 'billadmin' AND r.name = 'ADMIN'");
        JsonNode adj = call(post("/api/trading/billing/users/" + payer).content("{\"orders\":10,\"rupees\":50,\"note\":\"goodwill\"}"), "billadmin", 200);
        assertThat(adj.get("balance").get("liveOrdersLeft").asInt()).isEqualTo(10);
        assertThat(adj.get("balance").get("creditPaise").asLong()).isEqualTo(7000);
        call(post("/api/trading/billing/users/" + payer).content("{\"orders\":-50}"), "billadmin", 409);   // below zero

        // Setting the plan again (any number of times) tops included orders up to 30; it never stacks.
        jdbc.update("UPDATE app_user SET live_orders_left = 4 WHERE id = ?", payer);
        for (int i = 0; i < 3; i++)
            call(put("/api/users/" + payer + "/plan").content("{\"plan\":\"PREMIUM\",\"expiresAt\":\"" + java.time.Instant.now().plusSeconds(86400L * (30 + i)) + "\"}"), "billadmin", 200);
        JsonNode after = call(get("/api/trading/billing/users/" + payer), "billadmin", 200).get("balance");
        assertThat(after.get("liveOrdersLeft").asInt()).isEqualTo(30);
        assertThat(after.get("liveOrdersGranted").asInt()).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM live_order_charge WHERE user_id = ? AND source = 'GRANT'", Integer.class, payer)).isEqualTo(1);
    }

    @Test
    void todayListsMyTradingStrategiesAndStopMineTurnsThemOff() throws Exception {
        plan("cockpit", "GOLD");
        call(put("/api/trading/day-loss").content("{\"maxDayLoss\":5000}"), "cockpit", 200);
        long mine = ownBreakout("cockpit", "cockpit CE");
        long idle = ownBreakout("cockpit", "cockpit idle");
        call(put("/api/trading/strategies/" + mine).content("{\"mode\":\"PAPER\"}"), "cockpit", 200);

        JsonNode today = call(get("/api/trading/today"), "cockpit", 200);
        assertThat(today.get("index").asText()).isEqualTo("NIFTY 50");
        assertThat(today.get("strategies")).hasSize(1);   // only PAPER/LIVE ones
        JsonNode st = today.get("strategies").get(0);
        assertThat(st.get("strategyId").asLong()).isEqualTo(mine);
        assertThat(st.get("mode").asText()).isEqualTo("PAPER");
        assertThat(st.get("underlying").asText()).isEqualTo("NIFTY 50");
        assertThat(st.get("state").asText()).isNotBlank();
        assertThat(idle).isNotEqualTo(mine);

        JsonNode stop = call(post("/api/trading/stop-mine"), "cockpit", 200);
        assertThat(stop.get("strategiesStopped").asInt()).isEqualTo(1);
        assertThat(call(get("/api/trading/today"), "cockpit", 200).get("strategies")).isEmpty();
    }
}
