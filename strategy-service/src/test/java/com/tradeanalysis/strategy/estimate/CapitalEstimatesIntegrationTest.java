package com.tradeanalysis.strategy.estimate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.strategy.auth.TokenService;
import com.tradeanalysis.strategy.engine.MarketDataClient;
import com.tradeanalysis.strategy.user.AppUserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Capital used per trade (vs the backtest's capital) and the strategy preview: profit at target, loss at stop. */
@SpringBootTest(properties = {"auth.auto-register-users=false", "auth.trust-user-header=false",
        "spring.datasource.url=jdbc:h2:mem:capital-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class CapitalEstimatesIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;
    @MockitoBean MarketDataClient marketData;

    static long strategy, backtest;

    private static Candle candle(Instant t, double price) {
        BigDecimal p = BigDecimal.valueOf(price);
        return new Candle(t, p, p, p, p, 0);
    }

    @BeforeEach
    void prices() {
        Instant now = Instant.now();
        when(marketData.getSeries(eq("NIFTY 50"), any(), anyInt()))
                .thenReturn(new PriceSeries("NIFTY 50", Interval.MINUTE_5, List.of(candle(now, 25800))));
        when(marketData.getSeries(eq("INDIA VIX"), any(), anyInt()))
                .thenReturn(new PriceSeries("INDIA VIX", Interval.DAY_1, List.of(candle(now, 13))));
        when(marketData.getRange(eq("INDIA VIX"), eq(Interval.DAY_1), any(), any()))
                .thenReturn(new PriceSeries("INDIA VIX", Interval.DAY_1,
                        List.of(candle(LocalDate.of(2026, 11, 3).atStartOfDay(OptionEstimates.IST).toInstant(), 13))));
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String asUser, int expected) throws Exception {
        if (asUser != null)
            req.header("Authorization", "Bearer " + tokens.issue(users.findByUsernameIgnoreCase(asUser).orElseThrow()).token());
        String body = mvc.perform(req.contentType(MediaType.APPLICATION_JSON)).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    private long id(String u) { return users.findByUsernameIgnoreCase(u).orElseThrow().getId(); }

    @Test @Order(1)
    void setUp() throws Exception {
        call(post("/api/auth/setup").content("{\"username\":\"admin\",\"password\":\"Secret123\"}"), null, 200);
        for (String u : new String[]{"cap_a", "cap_b"}) {
            call(post("/api/auth/signup").content("{\"username\":\"" + u + "\",\"password\":\"Pass1234\",\"email\":\"" + u
                    + "@example.com\",\"phone\":\"98765" + (u.equals("cap_a") ? "33333" : "44444") + "\",\"plan\":\"FREE\"}"), null, 201);
            call(post("/api/users/" + id(u) + "/approve").content("{\"plan\":\"GOLD\"}"), "admin", 200);
        }
        strategy = call(post("/api/strategies").content("""
                {"name":"cap test","type":"NIFTY_OPENING_BREAKOUT","parameters":{"directions":"CE","lots":"1","lotSize":"65",
                 "targetPoints":"35","maxStopPoints":"20"}}"""), "cap_a", 201).get("id").asLong();
        jdbc.update("INSERT INTO backtest_result (strategy_id, symbol, initial_capital, final_capital, total_return, max_drawdown, total_trades, "
                + "winning_trades, sharpe_ratio, run_by_user_id) VALUES (?, 'NIFTY 50', 50000, 51000, 2, 1, 1, 1, 1, ?)", strategy, id("cap_a"));
        backtest = jdbc.queryForObject("SELECT MAX(id) FROM backtest_result", Long.class);
        // Tue 3 Nov 2026 10:00 IST: CE strike 25,750 at NIFTY 25,800, expiring the same day at 15:30
        jdbc.update("INSERT INTO nifty_trade_log (backtest_result_id, strategy_id, symbol, trade_date, trade_number, reference_high, reference_low, "
                + "entry_timestamp, entry_spot_price, option_type, strike_price, lots, lot_size, exit_spot_price, estimated_pnl) "
                + "VALUES (?, ?, 'NIFTY 50', DATE '2026-11-03', 1, 25790, 25760, TIMESTAMP WITH TIME ZONE '2026-11-03 10:00:00+05:30', "
                + "25800, 'CE', 25750, 1, 65, 25835, 1137)", backtest, strategy);
    }

    @Test @Order(2)
    void tradesShowTheCapitalTheyUsedOfTheBacktestCapital() throws Exception {
        JsonNode t = call(get("/api/nifty-trades/backtest/" + backtest), "cap_a", 200).get(0);
        double premium = t.get("estEntryPremium").asDouble();
        assertThat(premium).isBetween(50.0, 80.0);                     // 50 in the money, 5½ hours of time value at VIX 13
        assertThat(t.get("capitalUsed").asDouble()).isCloseTo(premium * 65, within(1.0));
        assertThat(t.get("capitalUsedPct").asDouble()).isCloseTo(premium * 65 / 50000 * 100, within(0.1));
        assertThat(t.get("estIv").asDouble()).isEqualTo(0.13);
        assertThat(t.get("ivAssumed").asBoolean()).isFalse();
        // The same estimates on the other trade lists
        assertThat(call(get("/api/nifty-trades/strategy/" + strategy), "cap_a", 200).get(0).get("capitalUsed").asDouble())
                .isEqualTo(t.get("capitalUsed").asDouble());
    }

    @Test @Order(3)
    void previewShowsCapitalUsedProfitAtTargetAndLossAtStop() throws Exception {
        JsonNode p = call(get("/api/strategies/" + strategy + "/preview").param("capital", "50000"), "cap_a", 200);
        assertThat(p.get("spot").asDouble()).isEqualTo(25800);
        assertThat(p.get("iv").asDouble()).isEqualTo(0.13);
        JsonNode leg = p.get("legs").get(0);
        assertThat(p.get("legs")).hasSize(1);
        assertThat(leg.get("optionType").asText()).isEqualTo("CE");
        assertThat(leg.get("strike").asInt()).isEqualTo(25750);
        assertThat(leg.get("quantity").asInt()).isEqualTo(65);
        double used = leg.get("capitalUsed").asDouble(), profit = leg.get("profitAtTarget").asDouble(), loss = leg.get("lossAtStop").asDouble();
        assertThat(used).isCloseTo(leg.get("premium").asDouble() * 65, within(1.0));
        assertThat(leg.get("capitalUsedPct").asDouble()).isCloseTo(used / 50000 * 100, within(0.1));
        assertThat(profit).isPositive().isLessThan(35 * 65);           // the option moves less than NIFTY
        assertThat(loss).isPositive().isLessThanOrEqualTo(20 * 65);    // the 20-point cap
        assertThat(leg.get("stopBasis").asText()).contains("Max SL of 20 points");
        assertThat(leg.get("riskReward").asDouble()).isCloseTo(profit / loss, within(0.01));
        assertThat(p.get("notes").toString()).contains("India VIX 13");

        // Lots the capital pays for (1 lot of 65 here)
        double perLot = leg.get("premium").asDouble() * 65;
        assertThat(p.get("lots").asInt()).isEqualTo(1);
        assertThat(p.get("capitalPerLot").asDouble()).isCloseTo(perLot, within(1.0));
        assertThat(p.get("maxLotsForCapital").asInt()).isEqualTo((int) Math.floor(50000 / perLot));
        JsonNode small = call(get("/api/strategies/" + strategy + "/preview").param("capital", String.valueOf((int) (perLot * 0.5))), "cap_a", 200);
        assertThat(small.get("maxLotsForCapital").asInt()).isZero();
        assertThat(small.get("notes").toString()).contains("doesn't cover even 1 lot");

        // A max loss in ₹ instead of points: the stop that loses about that much today (not saved)
        JsonNode r = call(get("/api/strategies/" + strategy + "/preview").param("maxLossAmount", "1000"), "cap_a", 200);
        JsonNode rl = r.get("legs").get(0);
        assertThat(r.get("maxLossAmount").asDouble()).isEqualTo(1000);
        assertThat(r.get("stopPointsForAmount").asDouble()).isEqualTo(rl.get("stopPoints").asDouble()).isPositive();
        assertThat(rl.get("lossAtStop").asDouble()).isBetween(900.0, 1001.0);
        assertThat(rl.get("stopBasis").asText()).startsWith("Max loss ₹1000 ≈");
        assertThat(r.get("notes").get(0).asText()).contains("is about").contains("NIFTY points");
        JsonNode big = call(get("/api/strategies/" + strategy + "/preview").param("maxLossAmount", "500000"), "cap_a", 200);
        assertThat(big.get("stopPointsForAmount").isNull()).isTrue();
        assertThat(big.get("notes").get(0).asText()).contains("more than the whole premium");
        call(get("/api/strategies/" + strategy + "/preview").param("maxLossAmount", "0"), "cap_a", 400);
    }

    @Test @Order(4)
    void withoutAStopCapOrBacktestsTheLossIsUnknownAndOthersCannotPreview() throws Exception {
        long other = call(post("/api/strategies").content("""
                {"name":"no cap","type":"NIFTY_OPENING_BREAKOUT","parameters":{"directions":"BOTH","targetPoints":"35"}}"""), "cap_a", 201)
                .get("id").asLong();
        // Even with backtest trades, no Max SL means no maximum loss is shown
        jdbc.update("INSERT INTO backtest_result (strategy_id, symbol, initial_capital, final_capital, total_return, max_drawdown, total_trades, "
                + "winning_trades, sharpe_ratio, run_by_user_id) VALUES (?, 'NIFTY 50', 50000, 50000, 0, 0, 1, 0, 0, ?)", other, id("cap_a"));
        jdbc.update("INSERT INTO nifty_trade_log (backtest_result_id, strategy_id, symbol, trade_date, trade_number, reference_high, reference_low, "
                + "entry_spot_price, option_type, strike_price, lots, lot_size, initial_stop_loss, exit_spot_price, estimated_pnl) "
                + "VALUES ((SELECT MAX(id) FROM backtest_result), ?, 'NIFTY 50', DATE '2026-11-04', 1, 1, 1, 25800, 'CE', 25750, 2, 65, 25589, 25589, -6800)", other);
        JsonNode p = call(get("/api/strategies/" + other + "/preview"), "cap_a", 200);
        assertThat(p.get("legs")).hasSize(2);                          // CE and PE
        assertThat(p.get("legs").get(0).get("quantity").asInt()).isEqualTo(130);   // default 2 lots × 65
        // CE and PE: the dearer option sets how many lots the capital covers
        double dearer = Math.max(p.get("legs").get(0).get("premium").asDouble(), p.get("legs").get(1).get("premium").asDouble()) * 65;
        assertThat(p.get("maxLotsForCapital").asInt()).isEqualTo((int) Math.floor(50000 / dearer));
        assertThat(p.get("legs").get(0).get("lossAtStop").isNull()).isTrue();
        assertThat(p.get("legs").get(0).get("stopPoints").isNull()).isTrue();
        assertThat(p.get("legs").get(0).get("riskReward").isNull()).isTrue();
        assertThat(p.get("legs").get(0).get("stopBasis").asText()).contains("No fixed stop-loss is set").contains("Max SL (points)");
        // Trying a Max SL shows the capped loss and reward : risk, without saving it to the strategy
        JsonNode tried = call(get("/api/strategies/" + other + "/preview").param("maxStopPoints", "25"), "cap_a", 200).get("legs").get(0);
        assertThat(tried.get("stopPoints").asDouble()).isEqualTo(25);
        assertThat(tried.get("lossAtStop").asDouble()).isPositive().isLessThanOrEqualTo(25 * 130 + 1);
        assertThat(tried.get("riskReward").isNull()).isFalse();
        assertThat(tried.get("stopBasis").asText()).contains("Max SL of 25 points");
        assertThat(call(get("/api/strategies/" + other), "cap_a", 200).get("parameters").has("maxStopPoints")).isFalse();
        call(get("/api/strategies/" + other + "/preview").param("maxStopPoints", "600"), "cap_a", 400);
        call(get("/api/strategies/" + strategy + "/preview"), "cap_b", 404);       // someone else's strategy
    }

    @Test @Order(5)
    void backtestReportsShowAverageAndLargestCapitalUsedPerTrade() throws Exception {
        JsonNode u = call(get("/api/backtests/capital-usage").param("ids", String.valueOf(backtest)), "cap_a", 200).get(0);
        assertThat(u.get("trades").asInt()).isEqualTo(1);
        assertThat(u.get("capital").asDouble()).isEqualTo(50000);
        assertThat(u.get("maxCapitalUsed").asDouble()).isEqualTo(u.get("avgCapitalUsed").asDouble());   // one trade
        assertThat(u.get("avgPct").asDouble()).isCloseTo(u.get("avgCapitalUsed").asDouble() / 500, within(0.1));
        call(get("/api/backtests/capital-usage").param("ids", String.valueOf(backtest)), "cap_b", 404);   // not theirs
    }

    @Test @Order(6)
    void aPortfolioWindowShowsThePeakCapitalInUseAtOnce() throws Exception {
        long other = call(post("/api/strategies").content("""
                {"name":"cap pe","type":"NIFTY_OPENING_BREAKOUT","parameters":{"directions":"PE","lots":"1","lotSize":"65"}}"""), "cap_a", 201)
                .get("id").asLong();
        jdbc.update("INSERT INTO backtest_result (strategy_id, symbol, initial_capital, final_capital, total_return, max_drawdown, total_trades, "
                + "winning_trades, sharpe_ratio, run_by_user_id) VALUES (?, 'NIFTY 50', 50000, 50000, 0, 0, 2, 1, 0, ?)", other, id("cap_a"));
        long bt2 = jdbc.queryForObject("SELECT MAX(id) FROM backtest_result", Long.class);
        String insert = "INSERT INTO nifty_trade_log (backtest_result_id, strategy_id, symbol, trade_date, trade_number, reference_high, reference_low, "
                + "entry_timestamp, exit_timestamp, entry_spot_price, option_type, strike_price, lots, lot_size, exit_spot_price, estimated_pnl) "
                + "VALUES (?, ?, 'NIFTY 50', DATE '2026-11-03', ?, 25790, 25760, ?, ?, 25800, 'PE', 25850, 1, 65, 25790, 300)";
        // Overlaps the CE trade (10:00, no exit time = one minute), then one alone in the afternoon
        jdbc.update(insert, bt2, other, 1, java.sql.Timestamp.from(java.time.Instant.parse("2026-11-03T04:30:30Z")),
                java.sql.Timestamp.from(java.time.Instant.parse("2026-11-03T05:00:00Z")));
        jdbc.update(insert, bt2, other, 2, java.sql.Timestamp.from(java.time.Instant.parse("2026-11-03T08:00:00Z")),
                java.sql.Timestamp.from(java.time.Instant.parse("2026-11-03T08:30:00Z")));

        JsonNode c = call(get("/api/backtests/capital-usage/combined").param("ids", backtest + "," + bt2).param("capital", "100000"), "cap_a", 200);
        List<JsonNode> each = new java.util.ArrayList<>();
        call(get("/api/nifty-trades/backtest/" + backtest), "cap_a", 200).forEach(each::add);
        call(get("/api/nifty-trades/backtest/" + bt2), "cap_a", 200).forEach(each::add);
        double ce = each.get(0).get("capitalUsed").asDouble(), peOverlap = each.stream()
                .filter(n -> n.get("tradeNumber").asInt() == 1 && n.get("optionType").asText().equals("PE")).findFirst().orElseThrow()
                .get("capitalUsed").asDouble();
        assertThat(c.get("trades").asInt()).isEqualTo(3);
        assertThat(c.get("peakCapitalUsed").asDouble()).isCloseTo(ce + peOverlap, within(1.0));   // both open at 10:00:30
        assertThat(c.get("peakPct").asDouble()).isCloseTo((ce + peOverlap) / 1000, within(0.1));    // of ₹1,00,000
        assertThat(c.get("peakOn").asText()).isEqualTo("2026-11-03");
        assertThat(c.get("maxCapitalUsed").asDouble()).isLessThan(c.get("peakCapitalUsed").asDouble());
        call(get("/api/backtests/capital-usage/combined").param("ids", String.valueOf(bt2)).param("capital", "100000"), "cap_b", 404);
    }
}
