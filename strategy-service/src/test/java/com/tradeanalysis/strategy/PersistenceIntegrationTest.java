package com.tradeanalysis.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.strategy.engine.BacktestEngine;
import com.tradeanalysis.strategy.engine.NiftyOpeningBreakoutEngine.DayReport;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the real Flyway migrations (V1 + V2) on H2 in PostgreSQL mode with Hibernate validating every entity,
 * then exercises users → strategy → backtest → stored reports → portfolio report through the API.
 * The market-data call is replaced by a canned backtest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
class PersistenceIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean BacktestEngine engine;

    private static final LocalDate MON = LocalDate.of(2026, 11, 2), TUE = LocalDate.of(2026, 11, 3),
                                   WED = LocalDate.of(2026, 10, 28);

    private static NiftyTradeLog trade(LocalDate day, String side, double entry, double exit, String reason) {
        Instant t = day.atTime(4, 0).toInstant(java.time.ZoneOffset.UTC);
        NiftyTradeLog log = new NiftyTradeLog(1L, "NIFTY 50", day, 1, side.equals("CE") ? "BULLISH" : "BEARISH",
                entry + 10, entry - 10, t.minusSeconds(900), t, entry, side, 23550, 2, 65, entry - 40);
        log.setContext(0.5, 85);
        log.close(t.plusSeconds(600), exit, reason, exit);
        return log;
    }

    private void cannedBacktest() {
        List<NiftyTradeLog> trades = List.of(
                trade(WED, "CE", 22551.9, 22569.85, "FAILED_BREAKOUT"),
                trade(MON, "CE", 23298.15, 23306.35, "SL_HIT"),
                trade(TUE, "CE", 23620.25, 23647.65, "SL_HIT"));
        List<DayReport> days = List.of(
                new DayReport(WED, -0.32, "TRADED", null, 1),
                new DayReport(LocalDate.of(2026, 10, 29), -0.04, "NO_TRADE", "Gap -0.04% does not meet GAP_UP 0.20%", 0),
                new DayReport(LocalDate.of(2026, 10, 30), 0.3, "SKIPPED", "skipDays includes THURSDAY", 0),
                new DayReport(MON, 0.73, "TRADED", null, 1),
                new DayReport(TUE, 0.60, "TRADED", null, 1));
        double net = trades.stream().mapToDouble(NiftyTradeLog::getEstimatedPnl).sum();
        when(engine.run(any(), anyString(), anyInt(), anyDouble())).thenAnswer(inv -> new BacktestEngine.BacktestOutput(
                100000 + net, net / 1000, 1.0, 3, 2, 0, List.of(), trades.stream().map(t ->
                        trade(t.getTradeDate(), t.getOptionType(), t.getEntrySpotPrice(), t.getExitSpotPrice(), t.getExitReason())).toList(),
                days));
    }

    private JsonNode call(MockHttpServletRequestBuilder req, int expected) throws Exception {
        String body = mvc.perform(req.header("X-User", "abheeshek").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    @Test
    void usersStrategiesBacktestsReportsAndPortfolios() throws Exception {
        cannedBacktest();

        // First request with X-User registers the user (role TRADER) and records them as creator.
        JsonNode strategy = call(post("/api/strategies").content("""
                {"name":"IT CE strategy","type":"NIFTY_OPENING_BREAKOUT","description":"d",
                 "parameters":{"directions":"CE","skipDays":"THU"}}"""), 201);
        JsonNode me = call(get("/api/users/me"), 200);
        assertThat(me.get("username").asText()).isEqualTo("abheeshek");
        assertThat(me.get("roles").get(0).get("name").asText()).isEqualTo("TRADER");
        assertThat(strategy.get("createdByUserId").asLong()).isEqualTo(me.get("id").asLong());

        // Backtest: run-by user, lookback, and stored daily / monthly reports.
        JsonNode bt = call(post("/api/backtests").param("strategyId", strategy.get("id").asText())
                .param("symbol", "NIFTY 50").param("lookbackDays", "30"), 201);
        assertThat(bt.get("runByUserId").asLong()).isEqualTo(me.get("id").asLong());
        assertThat(bt.get("lookbackDays").asInt()).isEqualTo(30);

        JsonNode daily = call(get("/api/backtests/" + bt.get("id").asText() + "/daily"), 200);
        assertThat(daily).hasSize(5);
        assertThat(daily.get(0).get("tradeDate").asText()).isEqualTo("2026-10-28");
        assertThat(daily.get(2).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(daily.get(2).get("dayOfWeek").asText()).isEqualTo("FRI");
        assertThat(daily.get(3).get("trades").asInt()).isEqualTo(1);

        JsonNode monthly = call(get("/api/backtests/" + bt.get("id").asText() + "/monthly"), 200);
        assertThat(monthly).hasSize(2);
        assertThat(monthly.get(0).get("periodMonth").asText()).isEqualTo("2026-10");
        assertThat(monthly.get(0).get("failedBreakoutExits").asInt()).isEqualTo(1);
        assertThat(monthly.get(1).get("trades").asInt()).isEqualTo(2);
        assertThat(monthly.get(1).get("charges").asDouble()).isEqualTo(170.0);

        // Portfolio of the strategy, combined report per window, stored and listed.
        JsonNode portfolio = call(post("/api/portfolios").content(
                "{\"name\":\"IT portfolio\",\"strategyIds\":[" + strategy.get("id").asText() + "]}"), 201);
        JsonNode reports = call(post("/api/portfolios/" + portfolio.get("id").asText() + "/reports")
                .param("windows", "30,90").param("capital", "50000"), 201);
        assertThat(reports).hasSize(2);
        JsonNode r = reports.get(0);
        assertThat(r.get("windowDays").asInt()).isEqualTo(30);
        assertThat(r.get("trades").asInt()).isEqualTo(3);
        assertThat(r.get("endCapital").asDouble()).isEqualTo(50000 + r.get("netPnl").asDouble());
        assertThat(r.get("items")).hasSize(1);
        assertThat(call(get("/api/portfolios/" + portfolio.get("id").asText() + "/reports"), 200)).hasSize(2);

        // Users and roles APIs (managing users needs an ADMIN).
        call(post("/api/users").content("{\"username\":\"ravi\"}"), 403);
        jdbc.update("INSERT INTO app_user_role (user_id, role_id) SELECT u.id, r.id FROM app_user u, app_role r WHERE u.username = 'abheeshek' AND r.name = 'ADMIN'");
        call(post("/api/users").content("{\"username\":\"ravi\",\"email\":\"ravi@example.com\",\"roles\":[\"viewer\"]}"), 201);
        assertThat(call(get("/api/users"), 200)).hasSize(2);
        assertThat(call(get("/api/users/roles"), 200)).hasSize(4);   // ADMIN, TRADER, VIEWER, PREMIUM
        call(post("/api/users").content("{\"username\":\"ravi\"}"), 409);
        call(post("/api/users").content("{\"username\":\"bad name!\"}"), 400);
        assertThat(call(get("/api/access-log"), 200)).isEmpty();   // written by the gateway, not here
    }
}
