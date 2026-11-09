package com.tradeanalysis.strategy.portfolio;

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

/** Editing a portfolio's strategies starts it afresh (old reports deleted); deleting hides it (its reports are kept). */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false",
        "spring.datasource.url=jdbc:h2:mem:portfolio-edit-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class PortfolioEditDeleteIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    private static final String SMA = "{\"name\":\"%s\",\"type\":\"SMA_CROSSOVER\",\"parameters\":{\"fast\":\"10\",\"slow\":\"30\"}}";
    private static long a, b, portfolio;

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

    /** A stored report with one item per strategy, each with its own backtest (as a report run makes). Returns the backtest ids. */
    private java.util.List<Long> report(long portfolioId, long... strategyIds) {
        jdbc.update("INSERT INTO portfolio_report (portfolio_id, window_days, symbol, from_date, to_date, capital, trades, wins, gross_pnl, "
                + "charges, net_pnl, end_capital, return_pct, max_drawdown, created_at) VALUES (?, 30, 'NIFTY 50', CURRENT_DATE, CURRENT_DATE, "
                + "100000, 1, 1, 10, 0, 10, 100010, 0.01, 0, CURRENT_TIMESTAMP)", portfolioId);
        long reportId = jdbc.queryForObject("SELECT MAX(id) FROM portfolio_report", Long.class);
        java.util.List<Long> bts = new java.util.ArrayList<>();
        for (long s : strategyIds) {
            jdbc.update("INSERT INTO backtest_result (strategy_id, symbol, initial_capital, final_capital, total_return, max_drawdown, "
                    + "total_trades, winning_trades, sharpe_ratio, run_by_user_id, lookback_days, created_at) "
                    + "VALUES (?, 'NIFTY 50', 100000, 100010, 0.01, 0, 1, 1, 0, ?, 30, CURRENT_TIMESTAMP)", s, id("ravi"));
            long bt = jdbc.queryForObject("SELECT MAX(id) FROM backtest_result", Long.class);
            jdbc.update("INSERT INTO portfolio_report_item (portfolio_report_id, strategy_id, backtest_result_id, trades, wins, net_pnl, max_drawdown) "
                    + "VALUES (?, ?, ?, 1, 1, 10, 0)", reportId, s, bt);
            bts.add(bt);
        }
        return bts;
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    @Test @Order(1)
    void renamingKeepsReportsButChangingStrategiesDeletesThem() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\"}"), null, 200);
        user("ravi", "GOLD");
        a = call(post("/api/strategies").content(SMA.formatted("A")), "ravi", 201).get("id").asLong();
        b = call(post("/api/strategies").content(SMA.formatted("B")), "ravi", 201).get("id").asLong();
        portfolio = call(post("/api/portfolios").content("{\"name\":\"AB\",\"strategyIds\":[" + a + "]}"), "ravi", 201).get("id").asLong();
        java.util.List<Long> bts = report(portfolio, a);
        report(portfolio, a);

        JsonNode renamed = call(put("/api/portfolios/" + portfolio).content("{\"name\":\"AB renamed\",\"strategyIds\":[" + a + "]}"), "ravi", 200);
        assertThat(renamed.get("portfolio").get("name").asText()).isEqualTo("AB renamed");
        assertThat(renamed.get("strategiesChanged").asBoolean()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM portfolio_report WHERE portfolio_id = ?", portfolio)).isEqualTo(2);

        JsonNode added = call(put("/api/portfolios/" + portfolio).content("{\"strategyIds\":[" + a + "," + b + "]}"), "ravi", 200);
        assertThat(added.get("strategiesChanged").asBoolean()).isTrue();
        assertThat(added.get("reportsDeleted").asInt()).isEqualTo(2);
        assertThat(added.get("portfolio").get("strategyIds").toString()).contains(String.valueOf(a), String.valueOf(b));
        assertThat(count("SELECT COUNT(*) FROM portfolio_report WHERE portfolio_id = ?", portfolio)).isZero();
        assertThat(count("SELECT COUNT(*) FROM backtest_result WHERE id = ?", bts.get(0))).isZero();   // the report's backtests too
        assertThat(count("SELECT COUNT(*) FROM strategy WHERE id IN (?, ?)", a, b)).isEqualTo(2);       // strategies stay

        // removing one also starts afresh
        report(portfolio, a, b);
        JsonNode removed = call(put("/api/portfolios/" + portfolio).content("{\"strategyIds\":[" + b + "]}"), "ravi", 200);
        assertThat(removed.get("reportsDeleted").asInt()).isEqualTo(1);
        call(put("/api/portfolios/" + portfolio).content("{\"strategyIds\":[]}"), "ravi", 400);
        call(put("/api/portfolios/" + portfolio).content("{\"strategyIds\":[999999]}"), "ravi", 404);
    }

    @Test @Order(2)
    void othersCantEditOrDeleteIt() throws Exception {
        user("sita", "GOLD");
        call(put("/api/portfolios/" + portfolio).content("{\"name\":\"mine\"}"), "sita", 404);
        call(delete("/api/portfolios/" + portfolio), "sita", 404);
    }

    @Test @Order(3)
    void deleteHidesPortfolioKeepsItsReportsAndFreesItsStrategies() throws Exception {
        java.util.List<Long> bts = report(portfolio, b);
        call(delete("/api/strategies/" + b), "ravi", 409);   // still in the portfolio
        call(delete("/api/portfolios/" + portfolio), "ravi", 204);
        call(get("/api/portfolios/" + portfolio), "ravi", 404);
        assertThat(call(get("/api/portfolios"), "ravi", 200).findValuesAsText("id")).doesNotContain(String.valueOf(portfolio));
        // soft delete: the portfolio row and its stored reports stay
        assertThat(count("SELECT COUNT(*) FROM portfolio WHERE id = ? AND deleted_at IS NOT NULL", portfolio)).isOne();
        assertThat(count("SELECT COUNT(*) FROM portfolio_report WHERE portfolio_id = ?", portfolio)).isPositive();
        assertThat(count("SELECT COUNT(*) FROM backtest_result WHERE id = ?", bts.get(0))).isOne();
        call(delete("/api/strategies/" + b), "ravi", 204);   // now it can go
    }

    @Test @Order(4)
    void anExpiredPlanCanStillDeleteButNotEdit() throws Exception {
        long p = call(post("/api/portfolios").content("{\"name\":\"Old\",\"strategyIds\":[" + a + "]}"), "ravi", 201).get("id").asLong();
        call(put("/api/users/" + id("ravi") + "/plan").content("{\"plan\":\"FREE\"}"), "admin", 200);
        call(put("/api/portfolios/" + p).content("{\"name\":\"New\"}"), "ravi", 403);
        call(delete("/api/portfolios/" + p), "ravi", 204);
    }
}
