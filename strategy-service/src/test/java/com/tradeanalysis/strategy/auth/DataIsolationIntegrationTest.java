package com.tradeanalysis.strategy.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.strategy.user.AppUserRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Each user sees only their own strategies, backtests, trades, portfolios and paper trading; admins see everything. */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false",
        "spring.datasource.url=jdbc:h2:mem:isolation-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class DataIsolationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;

    private JsonNode call(MockHttpServletRequestBuilder req, String asUser, int expected) throws Exception {
        if (asUser != null)
            req.header("Authorization", "Bearer " + tokens.issue(users.findByUsernameIgnoreCase(asUser).orElseThrow()).token());
        var res = mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(status().is(expected)).andReturn().getResponse();
        String body = res.getContentAsString();
        if (body.isEmpty() && res.getErrorMessage() != null) return json.createObjectNode().put("message", res.getErrorMessage());
        return body.isEmpty() ? null : json.readTree(body);
    }

    private long id(String username) { return users.findByUsernameIgnoreCase(username).orElseThrow().getId(); }

    private static List<Long> ids(JsonNode list, String field) {
        List<Long> out = new ArrayList<>();
        list.forEach(n -> out.add(n.get(field).asLong()));
        return out;
    }

    private long strategy(String user, String name) throws Exception {
        return call(post("/api/strategies").content("""
                {"name":"%s","type":"NIFTY_OPENING_BREAKOUT","description":"d","parameters":{"directions":"CE"}}""".formatted(name)), user, 201)
                .get("id").asLong();
    }

    /** A stored backtest with one trade (inserted directly: the backtest engine needs market data). */
    private long backtest(String user, long strategyId) {
        jdbc.update("INSERT INTO backtest_result (strategy_id, symbol, initial_capital, final_capital, total_return, max_drawdown, total_trades, "
                + "winning_trades, sharpe_ratio, run_by_user_id) VALUES (?, 'NIFTY 50', 50000, 51000, 2, 1, 1, 1, 1, ?)", strategyId, id(user));
        long bt = jdbc.queryForObject("SELECT MAX(id) FROM backtest_result", Long.class);
        jdbc.update("INSERT INTO nifty_trade_log (backtest_result_id, strategy_id, symbol, trade_date, trade_number, reference_high, reference_low, "
                + "entry_spot_price, option_type, strike_price, lots, lot_size, exit_spot_price, estimated_pnl) "
                + "VALUES (?, ?, 'NIFTY 50', DATE '2026-11-03', 1, 1, 1, 1, 'CE', 24000, 2, 65, 1, 1000)", bt, strategyId);
        return bt;
    }

    static long ravisStrategy, sitasStrategy, ravisBacktest, sitasBacktest;

    @Test @Order(1)
    void setUpUsers() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\"}"), null, 200);
        for (String u : new String[]{"ravi", "sita"}) {
            call(post("/api/auth/signup").content("{\"username\":\"" + u + "\",\"password\":\"Pass1234\",\"email\":\"" + u
                    + "@example.com\",\"phone\":\"98765" + (u.equals("ravi") ? "11111" : "22222") + "\",\"acceptTerms\":true,\"plan\":\"GOLD\"}"), null, 201);
            call(post("/api/users/" + id(u) + "/approve").content("{}"), "admin", 200);
        }
    }

    @Test @Order(2)
    void strategiesArePrivateAndMasterOnesAreAdminOnly() throws Exception {
        ravisStrategy = strategy("ravi", "my breakout");
        sitasStrategy = strategy("sita", "my breakout");          // same name is fine for another user
        call(post("/api/strategies").content("""
                {"name":"my breakout","type":"NIFTY_OPENING_BREAKOUT","parameters":{}}"""), "ravi", 409);   // but not twice for one user

        List<Long> ravis = ids(call(get("/api/strategies"), "ravi", 200), "id");
        assertThat(ravis).contains(ravisStrategy).doesNotContain(sitasStrategy, 55L, 56L);
        call(get("/api/strategies/" + sitasStrategy), "ravi", 404);  // as if it didn't exist
        call(get("/api/strategies/56"), "ravi", 404);                 // master strategies are admin-only
        call(post("/api/strategies/56/clone").content("{\"lots\":2}"), "ravi", 404);
        call(post("/api/backtests").param("strategyId", "56").param("symbol", "NIFTY 50").param("lookbackDays", "30"), "ravi", 404);
        assertThat(call(get("/api/strategies/shared"), "ravi", 200).toString()).isEqualTo("[]");
        assertThat(call(get("/api/strategies/shared"), "admin", 200).toString()).isEqualTo("[55,56]");
        assertThat(ids(call(get("/api/strategies"), "admin", 200), "id")).contains(ravisStrategy, sitasStrategy, 55L, 56L);
        call(get("/api/strategies/56"), "admin", 200);
    }

    @Test @Order(3)
    void backtestsReportsAndTradesArePrivate() throws Exception {
        ravisBacktest = backtest("ravi", ravisStrategy);
        sitasBacktest = backtest("sita", sitasStrategy);

        assertThat(ids(call(get("/api/backtests/" + ravisStrategy), "ravi", 200), "id")).containsExactly(ravisBacktest);
        assertThat(ids(call(get("/api/backtests/" + sitasStrategy), "admin", 200), "id")).containsExactly(sitasBacktest);
        call(get("/api/backtests/56"), "ravi", 404);                   // master strategy: admin-only
        call(get("/api/backtests/" + sitasBacktest + "/daily"), "ravi", 404);
        call(get("/api/backtests/" + sitasBacktest + "/monthly"), "ravi", 404);
        call(get("/api/nifty-trades/backtest/" + sitasBacktest), "ravi", 404);
        call(get("/api/backtests/" + sitasStrategy), "ravi", 404);     // another user's strategy

        assertThat(ids(call(get("/api/nifty-trades/date/2026-11-03"), "ravi", 200), "backtestResultId")).containsOnly(ravisBacktest);
        assertThat(ids(call(get("/api/nifty-trades/strategy/" + ravisStrategy), "ravi", 200), "backtestResultId")).containsOnly(ravisBacktest);
        assertThat(ids(call(get("/api/nifty-trades/date/2026-11-03"), "admin", 200), "backtestResultId")).contains(ravisBacktest, sitasBacktest);
        assertThat(ids(call(get("/api/nifty-trades"), "ravi", 200), "backtestResultId")).containsOnly(ravisBacktest);
        assertThat(ids(call(get("/api/nifty-trades?from=2026-11-01&to=2026-11-03"), "ravi", 200), "backtestResultId")).containsOnly(ravisBacktest);
        assertThat(ids(call(get("/api/nifty-trades?from=2026-11-04"), "ravi", 200), "backtestResultId")).isEmpty();
        assertThat(ids(call(get("/api/nifty-trades?to=2026-11-03"), "admin", 200), "backtestResultId")).contains(ravisBacktest, sitasBacktest);
        call(get("/api/nifty-trades?from=2026-11-05&to=2026-11-01"), "ravi", 400);
    }

    @Test @Order(4)
    void portfoliosArePrivate() throws Exception {
        long ravis = call(post("/api/portfolios").content("{\"name\":\"CE + PE\",\"strategyIds\":[" + ravisStrategy + "]}"), "ravi", 201).get("id").asLong();
        call(post("/api/portfolios").content("{\"name\":\"CE + PE\",\"strategyIds\":[" + sitasStrategy + "]}"), "sita", 201);   // same name, other user
        call(post("/api/portfolios").content("{\"name\":\"masters\",\"strategyIds\":[55,56]}"), "sita", 404);   // admin-only strategies
        call(post("/api/portfolios").content("{\"name\":\"spy\",\"strategyIds\":[" + ravisStrategy + "]}"), "sita", 404);   // not her strategy
        assertThat(call(get("/api/portfolios"), "sita", 200)).hasSize(1);
        call(get("/api/portfolios/" + ravis), "sita", 404);
        call(get("/api/portfolios/" + ravis + "/reports"), "sita", 404);
        call(post("/api/portfolios/" + ravis + "/reports?windows=30&capital=50000"), "sita", 404);
        assertThat(call(get("/api/portfolios"), "admin", 200)).hasSize(2);
    }

    @Test @Order(5)
    void paperTradingIsPerUser() throws Exception {
        call(put("/api/trading/day-loss").content("{\"maxDayLoss\":5000}"), "ravi", 200);
        call(put("/api/trading/strategies/" + ravisStrategy).content("{\"mode\":\"PAPER\"}"), "ravi", 200);
        call(put("/api/trading/strategies/56").content("{\"mode\":\"PAPER\"}"), "ravi", 404);   // admin-only strategy
        JsonNode sitasModes = call(get("/api/trading/strategies"), "sita", 200);
        sitasModes.forEach(m -> assertThat(m.get("mode").asText()).as("sita's #" + m.get("strategyId")).isEqualTo("OFF"));   // ravi's switch isn't hers
        assertThat(ids(sitasModes, "strategyId")).doesNotContain(ravisStrategy);

        String order = """
                {"tradingsymbol":"NIFTY26N0324150CE","transactionType":"BUY","quantity":65,"mode":"PAPER","confirm":true}""";
        long ravisOrder = call(post("/api/trading/orders").content(order), "ravi", 201).get("id").asLong();
        assertThat(call(get("/api/trading/orders"), "sita", 200)).isEmpty();
        assertThat(ids(call(get("/api/trading/orders"), "ravi", 200), "id")).containsExactly(ravisOrder);
        assertThat(ids(call(get("/api/trading/orders"), "admin", 200), "id")).contains(ravisOrder);
        call(post("/api/trading/orders/" + ravisOrder + "/cancel"), "sita", 404);
        assertThat(call(get("/api/trading/status"), "sita", 200).get("paper").get("ordersToday").asInt()).isZero();
        assertThat(call(get("/api/trading/status"), "ravi", 200).get("paper").get("ordersToday").asInt()).isEqualTo(1);
    }

    @Test @Order(6)
    void onlyAdminsSeeTheAccessLog() throws Exception {
        call(get("/api/access-log"), "ravi", 403);
        call(get("/api/access-log?username=ravi"), "ravi", 403);   // not even their own
        call(get("/api/access-log"), "admin", 200);
    }
}
