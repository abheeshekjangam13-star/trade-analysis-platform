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

/**
 * Paid plans may edit their own strategies (Free may not); every plan may delete them (the row is hidden, its backtests
 * and reports are deleted; its paper/live trades stay, for admins only), Free up to 4 times. Master strategies are admin-only.
 */
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
    void freeUserCantEditButAdminCan() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\"}"), null, 200);
        user("sita", "FREE");
        long s = create("sita", "Mine");

        JsonNode refused = call(put("/api/strategies/" + s).content("{\"name\":\"Mine renamed\"}"), "sita", 403);
        assertThat(refused.get("message").asText()).contains("Upgrade to GOLD");
        assertThat(call(get("/api/users/me/usage"), "sita", 200).get("editStrategies").asBoolean()).isFalse();

        JsonNode e = call(put("/api/strategies/" + s).content("{\"name\":\"Mine renamed\",\"description\":\"faster\","
                + "\"parameters\":{\"fast\":\"5\",\"slow\":\"30\"}}"), "admin", 200);
        assertThat(e.get("name").asText()).isEqualTo("Mine renamed");
        assertThat(e.get("description").asText()).isEqualTo("faster");
        assertThat(e.get("parameters").get("fast").asText()).isEqualTo("5");
        assertThat(e.get("type").asText()).isEqualTo("SMA_CROSSOVER");

        // omitted fields stay as they are
        JsonNode onlyName = call(put("/api/strategies/" + s).content("{\"name\":\"Mine again\"}"), "admin", 200);
        assertThat(onlyName.get("parameters").get("fast").asText()).isEqualTo("5");
        assertThat(onlyName.get("description").asText()).isEqualTo("faster");

        create("sita", "Other");
        call(put("/api/strategies/" + s).content("{\"name\":\"Other\"}"), "admin", 409);   // the owner's names stay unique
        call(put("/api/strategies/" + s).content("{\"name\":\"  \"}"), "admin", 400);
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
    void deleteHidesTheStrategyDeletesItsHistoryAndFreesAPlace() throws Exception {
        long s = jdbc.queryForObject("SELECT id FROM strategy WHERE name = 'Mine again'", Long.class);
        long bt = backtestRow(s, "sita");
        jdbc.update("INSERT INTO backtest_daily_report (backtest_result_id, trade_date, day_of_week, status, trades, net_pnl) "
                + "VALUES (?, CURRENT_DATE, 'MON', 'NO_TRADE', 0, 0)", bt);
        jdbc.update("INSERT INTO nifty_trade_log (backtest_result_id, strategy_id, trade_date, trade_number, reference_high, reference_low, "
                + "entry_spot_price, strike_price, lots, lot_size, exit_spot_price, estimated_pnl) "
                + "VALUES (?, ?, CURRENT_DATE, 1, 24100, 24000, 24110, 24100, 1, 65, 24160, 100)", bt, s);
        long other = jdbc.queryForObject("SELECT id FROM strategy WHERE name = 'Other'", Long.class);
        long otherBt = backtestRow(other, "sita");   // another strategy's history is not touched

        call(delete("/api/strategies/" + s), "sita", 204);
        call(get("/api/strategies/" + s), "sita", 404);
        assertThat(call(get("/api/strategies"), "sita", 200).findValuesAsText("id")).doesNotContain(String.valueOf(s));
        // the row stays hidden (it counts as a delete); its backtests, reports and trade logs are gone
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM strategy WHERE id = ? AND deleted_at IS NOT NULL", Integer.class, s)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM backtest_result WHERE strategy_id = ?", Integer.class, s)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM backtest_daily_report WHERE backtest_result_id = ?", Integer.class, bt)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nifty_trade_log WHERE strategy_id = ?", Integer.class, s)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM backtest_result WHERE id = ?", Integer.class, otherBt)).isOne();
        create("sita", "Mine again");   // the name is free again, and she's back under FREE's limit of 2
    }

    @Test @Order(4)
    void freeUsersMayDeleteFourTimes() throws Exception {
        for (int i = 2; i <= 4; i++) {
            long s = jdbc.queryForObject("SELECT id FROM strategy WHERE name = 'Mine again' AND deleted_at IS NULL", Long.class);
            call(delete("/api/strategies/" + s), "sita", 204);
            create("sita", "Mine again");
        }
        JsonNode usage = call(get("/api/users/me/usage"), "sita", 200);
        assertThat(usage.get("deletesUsed").asInt()).isEqualTo(4);
        assertThat(usage.get("maxDeletes").asInt()).isEqualTo(4);
        assertThat(usage.get("nextPlan").asText()).isEqualTo("GOLD");

        long fifth = jdbc.queryForObject("SELECT id FROM strategy WHERE name = 'Mine again' AND deleted_at IS NULL", Long.class);
        JsonNode refused = call(delete("/api/strategies/" + fifth), "sita", 403);
        assertThat(refused.get("message").asText()).contains("used all 4 deletes", "Upgrade to GOLD");
        call(get("/api/strategies/" + fifth), "sita", 200);
    }

    @Test @Order(5)
    void tradedOrPortfolioStrategiesCantBeDeletedAndParamsLockWhileTrading() throws Exception {
        JsonNode master = call(get("/api/strategies/56"), "admin", 200);                                // paper trading: Nifty breakout only
        var mine = json.createObjectNode().put("name", "Ravi paper").put("type", master.get("type").asText());
        mine.set("parameters", master.get("parameters"));
        JsonNode clone = call(post("/api/strategies").content(mine.toString()), "ravi", 201);
        long s = clone.get("id").asLong();
        com.fasterxml.jackson.databind.node.ObjectNode params = (com.fasterxml.jackson.databind.node.ObjectNode) clone.get("parameters").deepCopy();
        params.put("lots", "3");
        String newParams = "{\"parameters\":" + params + "}";
        call(put("/api/trading/day-loss").content("{\"maxDayLoss\":5000}"), "ravi", 200);
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
        call(delete("/api/strategies/" + s), "ravi", 204);   // its closed PAPER trades stay, for admins only
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM live_position WHERE strategy_id = ?", Integer.class, s)).isOne();

        long p = create("ravi", "In a portfolio");
        call(post("/api/portfolios").content("{\"name\":\"My P\",\"strategyIds\":[" + p + "]}"), "ravi", 201);
        JsonNode inP = call(delete("/api/strategies/" + p), "ravi", 409);
        assertThat(inP.get("code").asText()).isEqualTo("IN_PORTFOLIO");
        assertThat(inP.get("message").asText()).contains("'My P'");

        long t = create("ravi", "Has live trades");
        jdbc.update("INSERT INTO live_position (strategy_id, user_id, trade_date, mode, option_type, quantity, entry_candle, entry_spot, "
                + "status, opened_at) VALUES (?, ?, CURRENT_DATE, 'LIVE', 'CE', 65, CURRENT_TIMESTAMP, 24000, 'CLOSED', CURRENT_TIMESTAMP)", t, id("ravi"));
        call(delete("/api/strategies/" + t), "ravi", 204);   // its LIVE trades stay, for admins only
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM live_position WHERE strategy_id = ?", Integer.class, t)).isOne();
        // a deleted strategy's paper and live trades are shown to admins only
        String days = "/api/trading/positions/history?from=" + java.time.LocalDate.now().minusDays(2) + "&to=" + java.time.LocalDate.now().plusDays(2);
        assertThat(call(get(days), "ravi", 200).findValuesAsText("strategyId")).doesNotContain(String.valueOf(s), String.valueOf(t));
        assertThat(call(get(days), "admin", 200).findValuesAsText("strategyId")).contains(String.valueOf(s), String.valueOf(t));
        assertThat(call(get("/api/users/me/usage"), "ravi", 200).get("maxDeletes").asInt()).isEqualTo(-1);   // GOLD: no limit
    }

    @Test @Order(6)
    void anyPlanCanTurnTradingOffButOnlyGoldCanTurnItOn() throws Exception {
        long mine = jdbc.queryForObject("SELECT id FROM strategy WHERE name = 'Other'", Long.class);   // sita's, FREE
        call(put("/api/trading/strategies/" + mine).content("{\"mode\":\"OFF\"}"), "sita", 200);
        JsonNode paper = call(put("/api/trading/strategies/" + mine).content("{\"mode\":\"PAPER\"}"), "sita", 403);
        assertThat(paper.get("message").asText()).contains("GOLD");
    }

    @Test @Order(7)
    void backtestsLeftByAnEarlierDeleteAreForAdminsOnly() throws Exception {
        long s = create("ravi", "Deleted long ago");
        long bt = backtestRow(s, "ravi");
        jdbc.update("INSERT INTO nifty_trade_log (backtest_result_id, strategy_id, trade_date, trade_number, reference_high, reference_low, "
                + "entry_spot_price, strike_price, lots, lot_size, exit_spot_price, estimated_pnl) "
                + "VALUES (?, ?, DATE '2026-11-04', 1, 24100, 24000, 24110, 24100, 1, 65, 24160, 5115)", bt, s);
        String trades = "/api/nifty-trades?from=2026-11-01&to=2026-11-30";
        assertThat(call(get(trades), "ravi", 200).findValuesAsText("strategyId")).contains(String.valueOf(s));

        // deleted before deletes removed the history: the backtest and its trades are still stored
        jdbc.update("UPDATE strategy SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", s);
        assertThat(call(get(trades), "ravi", 200).findValuesAsText("strategyId")).doesNotContain(String.valueOf(s));
        call(get("/api/nifty-trades/backtest/" + bt), "ravi", 404);
        call(get("/api/backtests/" + bt + "/daily"), "ravi", 404);
        assertThat(call(get(trades), "admin", 200).findValuesAsText("strategyId")).contains(String.valueOf(s));
        call(get("/api/nifty-trades/backtest/" + bt), "admin", 200);
    }
}
