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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every user (any plan) may edit and delete their own strategies; master strategies are admin-only. */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false",
        "spring.datasource.url=jdbc:h2:mem:edit-delete-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class StrategyEditDeleteIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    private static final String SMA = "{\"name\":\"%s\",\"type\":\"SMA_CROSSOVER\",\"parameters\":{\"fast\":\"10\",\"slow\":\"30\"}}";

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

    private long create(String user, String name) throws Exception {
        return call(post("/api/strategies").content(SMA.formatted(name)), user, 201).get("id").asLong();
    }

    private long backtestRow(long strategyId, String user) {
        jdbc.update("INSERT INTO backtest_result (strategy_id, symbol, initial_capital, final_capital, total_return, max_drawdown, "
                + "total_trades, winning_trades, sharpe_ratio, run_by_user_id, lookback_days, created_at) "
                + "VALUES (?, 'NIFTY 50', 100000, 101000, 1, 0, 1, 1, 0, ?, 30, CURRENT_TIMESTAMP)", strategyId, id(user));
        return jdbc.queryForObject("SELECT MAX(id) FROM backtest_result WHERE strategy_id = ?", Long.class, strategyId);
    }

    @Test @Order(1)
    void freeUserEditsTheirOwnStrategy() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\"}"), null, 200);
        user("sita", "FREE");
        long s = create("sita", "Mine");

        JsonNode e = call(put("/api/strategies/" + s).content("{\"name\":\"Mine renamed\",\"description\":\"faster\","
                + "\"parameters\":{\"fast\":\"5\",\"slow\":\"30\"}}"), "sita", 200);
        assertThat(e.get("name").asText()).isEqualTo("Mine renamed");
        assertThat(e.get("description").asText()).isEqualTo("faster");
        assertThat(e.get("parameters").get("fast").asText()).isEqualTo("5");
        assertThat(e.get("type").asText()).isEqualTo("SMA_CROSSOVER");

        // omitted fields stay as they are
        JsonNode onlyName = call(put("/api/strategies/" + s).content("{\"name\":\"Mine again\"}"), "sita", 200);
        assertThat(onlyName.get("parameters").get("fast").asText()).isEqualTo("5");
        assertThat(onlyName.get("description").asText()).isEqualTo("faster");

        create("sita", "Other");
        call(put("/api/strategies/" + s).content("{\"name\":\"Other\"}"), "sita", 409);   // your names stay unique
        call(put("/api/strategies/" + s).content("{\"name\":\"  \"}"), "sita", 400);
    }

    @Test @Order(2)
    void nobodyElsesStrategyAndMasterOnesAreAdminOnly() throws Exception {
        user("ravi", "GOLD");
        long sitas = jdbc.queryForObject("SELECT id FROM strategy WHERE name = 'Other'", Long.class);   // sita's (FREE: 2 max)
        call(put("/api/strategies/" + sitas).content("{\"name\":\"hijack\"}"), "ravi", 404);
        call(delete("/api/strategies/" + sitas), "ravi", 404);

        call(put("/api/strategies/56").content("{\"name\":\"mine now\"}"), "ravi", 404);   // users can't see master strategies
        call(delete("/api/strategies/56"), "ravi", 404);

        call(put("/api/strategies/" + sitas).content("{\"description\":\"checked by admin\"}"), "admin", 200);
    }

    @Test @Order(3)
    void deleteRemovesBacktestsAndReportsAndFreesAPlace() throws Exception {
        long s = jdbc.queryForObject("SELECT id FROM strategy WHERE name = 'Mine again'", Long.class);
        long bt = backtestRow(s, "sita");
        jdbc.update("INSERT INTO backtest_daily_report (backtest_result_id, trade_date, day_of_week, status, trades, net_pnl) "
                + "VALUES (?, CURRENT_DATE, 'MON', 'NO_TRADE', 0, 0)", bt);

        call(delete("/api/strategies/" + s), "sita", 204);
        call(get("/api/strategies/" + s), "sita", 404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM backtest_result WHERE strategy_id = ?", Integer.class, s)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM backtest_daily_report WHERE backtest_result_id = ?", Integer.class, bt)).isZero();
        create("sita", "Room again");   // back under FREE's limit of 2
    }

    @Test @Order(4)
    void tradedOrPortfolioStrategiesCantBeDeletedAndParamsLockWhileTrading() throws Exception {
        JsonNode master = call(get("/api/strategies/56"), "admin", 200);                                // paper trading: Nifty breakout only
        var mine = json.createObjectNode().put("name", "Ravi paper").put("type", master.get("type").asText());
        mine.set("parameters", master.get("parameters"));
        JsonNode clone = call(post("/api/strategies").content(mine.toString()), "ravi", 201);
        long s = clone.get("id").asLong();
        com.fasterxml.jackson.databind.node.ObjectNode params = (com.fasterxml.jackson.databind.node.ObjectNode) clone.get("parameters").deepCopy();
        params.put("lots", "3");
        String newParams = "{\"parameters\":" + params + "}";
        call(put("/api/trading/strategies/" + s).content("{\"mode\":\"PAPER\"}"), "ravi", 200);
        call(put("/api/strategies/" + s).content("{\"name\":\"Ravi paper renamed\"}"), "ravi", 200);   // renaming is fine
        JsonNode locked = call(put("/api/strategies/" + s).content(newParams), "ravi", 409);
        assertThat(locked.get("code").asText()).isEqualTo("TRADING_ON");
        JsonNode trading = call(delete("/api/strategies/" + s), "ravi", 409);
        assertThat(trading.get("code").asText()).isEqualTo("TRADING_ON");
        assertThat(trading.get("message").asText()).contains("Turn its trading off");

        // an open trade comes first: trading must stay on until it exits
        jdbc.update("INSERT INTO live_position (strategy_id, user_id, trade_date, mode, option_type, quantity, entry_candle, entry_spot, "
                + "status, opened_at) VALUES (?, ?, CURRENT_DATE, 'PAPER', 'CE', 65, CURRENT_TIMESTAMP, 24000, 'OPEN', CURRENT_TIMESTAMP)", s, id("ravi"));
        assertThat(call(delete("/api/strategies/" + s), "ravi", 409).get("code").asText()).isEqualTo("OPEN_POSITION");
        jdbc.update("UPDATE live_position SET status = 'CLOSED' WHERE strategy_id = ?", s);

        call(put("/api/trading/strategies/" + s).content("{\"mode\":\"OFF\"}"), "ravi", 200);
        assertThat(call(put("/api/strategies/" + s).content(newParams), "ravi", 200).get("parameters").get("lots").asText()).isEqualTo("3");
        call(delete("/api/strategies/" + s), "ravi", 204);   // closed PAPER trades go with it
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM live_position WHERE strategy_id = ?", Integer.class, s)).isZero();

        long p = create("ravi", "In a portfolio");
        call(post("/api/portfolios").content("{\"name\":\"My P\",\"strategyIds\":[" + p + "]}"), "ravi", 201);
        JsonNode inP = call(delete("/api/strategies/" + p), "ravi", 409);
        assertThat(inP.get("code").asText()).isEqualTo("IN_PORTFOLIO");
        assertThat(inP.get("message").asText()).contains("'My P'");

        long t = create("ravi", "Has live trades");
        jdbc.update("INSERT INTO live_position (strategy_id, user_id, trade_date, mode, option_type, quantity, entry_candle, entry_spot, "
                + "status, opened_at) VALUES (?, ?, CURRENT_DATE, 'LIVE', 'CE', 65, CURRENT_TIMESTAMP, 24000, 'CLOSED', CURRENT_TIMESTAMP)", t, id("ravi"));
        assertThat(call(delete("/api/strategies/" + t), "ravi", 409).get("code").asText()).isEqualTo("HAS_LIVE_TRADES");
    }

    @Test @Order(5)
    void anyPlanCanTurnTradingOffButOnlyGoldCanTurnItOn() throws Exception {
        long mine = jdbc.queryForObject("SELECT id FROM strategy WHERE name = 'Other'", Long.class);   // sita's, FREE
        call(put("/api/trading/strategies/" + mine).content("{\"mode\":\"OFF\"}"), "sita", 200);
        JsonNode paper = call(put("/api/trading/strategies/" + mine).content("{\"mode\":\"PAPER\"}"), "sita", 403);
        assertThat(paper.get("message").asText()).contains("GOLD");
    }
}
