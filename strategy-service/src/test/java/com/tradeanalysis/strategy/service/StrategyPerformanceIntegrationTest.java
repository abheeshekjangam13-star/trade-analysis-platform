package com.tradeanalysis.strategy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.strategy.auth.TokenService;
import com.tradeanalysis.strategy.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Latest 30/90/180/360-day result per strategy, only from the caller's own backtests. */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false",
        "spring.datasource.url=jdbc:h2:mem:perf-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@DirtiesContext
class StrategyPerformanceIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    private JsonNode call(MockHttpServletRequestBuilder req, String asUser, int expected) throws Exception {
        if (asUser != null)
            req.header("Authorization", "Bearer " + tokens.issue(users.findByUsernameIgnoreCase(asUser).orElseThrow()).token());
        String body = mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    private long id(String u) { return users.findByUsernameIgnoreCase(u).orElseThrow().getId(); }

    /** A NIFTY_OPENING_BREAKOUT strategy owned by this user (master strategies are admin-only). */
    private long ownBreakout(String username, String name) {
        jdbc.update("INSERT INTO strategy (name, type, created_at, created_by_user_id) "
                + "SELECT ?, 'NIFTY_OPENING_BREAKOUT', CURRENT_TIMESTAMP, id FROM app_user WHERE username = ?", name, username);
        return jdbc.queryForObject("SELECT id FROM strategy WHERE name = ?", Long.class, name);
    }

    private void backtest(long strategyId, String user, int window, double returnPct, String createdAt) {
        double capital = 500_000;
        jdbc.update("""
                INSERT INTO backtest_result (strategy_id, symbol, from_date, to_date, initial_capital, final_capital, total_return,
                    max_drawdown, total_trades, winning_trades, sharpe_ratio, run_by_user_id, lookback_days, created_at)
                VALUES (?, 'NIFTY 50', ?, ?, ?, ?, ?, 1.5, 10, 6, 0, ?, ?, ?)""",
                strategyId, Timestamp.from(Instant.parse(createdAt)), Timestamp.from(Instant.parse(createdAt)),
                capital, capital * (1 + returnPct / 100), returnPct, id(user), window, Timestamp.from(Instant.parse(createdAt)));
    }

    @Test
    void latestPerWindowFromTheCallersOwnBacktests() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\"}"), null, 200);
        for (String u : new String[]{"ravi", "sita"}) {
            call(post("/api/users").content("{\"username\":\"" + u + "\",\"password\":\"Pass1234\"}"), "admin", 201);
            call(put("/api/users/" + id(u) + "/plan").content("{\"plan\":\"GOLD\"}"), "admin", 200);
        }
        long ce = ownBreakout("ravi", "ravi CE"), pe = ownBreakout("ravi", "ravi PE"), sitas = ownBreakout("sita", "sita CE");
        backtest(ce, "ravi", 30, 2.0, "2026-11-01T10:00:00Z");
        backtest(ce, "ravi", 30, 4.6, "2026-11-05T10:00:00Z");     // newer: wins
        backtest(ce, "ravi", 360, 14.2, "2026-11-05T10:00:01Z");
        backtest(ce, "ravi", 730, 30.0, "2026-11-05T10:00:02Z");    // not one of the windows
        backtest(pe, "ravi", 360, -3.0, "2026-11-05T10:00:03Z");
        backtest(sitas, "sita", 90, 99.0, "2026-11-05T10:00:04Z");  // someone else's

        JsonNode r = call(get("/api/strategies/performance"), "ravi", 200);
        assertThat(r).hasSize(2);
        JsonNode s56 = null;
        for (JsonNode n : r) if (n.get("strategyId").asLong() == ce) s56 = n;
        assertThat(s56).isNotNull();
        assertThat(s56.get("windows").fieldNames()).toIterable().containsExactly("30", "360");
        assertThat(s56.get("windows").get("30").get("returnPct").asDouble()).isEqualTo(4.6);
        assertThat(s56.get("windows").get("30").get("netPnl").asDouble()).isEqualTo(23_000.0);
        assertThat(s56.get("windows").get("360").get("trades").asInt()).isEqualTo(10);

        JsonNode sita = call(get("/api/strategies/performance"), "sita", 200);
        assertThat(sita).hasSize(1);
        assertThat(sita.get(0).get("windows").get("90").get("returnPct").asDouble()).isEqualTo(99.0);

        assertThat(call(get("/api/strategies/performance"), "admin", 200).toString()).contains("99.0", "14.2");   // admin: everyone's
    }
}
