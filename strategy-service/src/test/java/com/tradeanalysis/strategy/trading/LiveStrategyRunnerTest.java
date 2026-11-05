package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.strategy.engine.MarketDataClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * The live runner with the real engine and master strategy #56 (CE gap-up) on made-up candles, in PAPER mode,
 * Kite and market data mocked. Tue 3 Nov 2026: +0.42% gap-up, 09:20 candle breaks above the 09:15 high → BUY;
 * the 09:27 one-minute low hits the 24,115 midpoint stop → SELL.
 */
@SpringBootTest(properties = {"trading.runner.enabled=true", "trading.runner.initial-delay-ms=3600000"})
@ActiveProfiles("it")
class LiveStrategyRunnerTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate MON = LocalDate.of(2026, 11, 2), TUE = LocalDate.of(2026, 11, 3);
    static final MutableClock CLOCK = new MutableClock(at(TUE, 9, 26));

    @TestConfiguration
    static class Clocks { @Bean @org.springframework.context.annotation.Primary Clock testClock() { return CLOCK; } }

    @Autowired LiveStrategyRunner runner;
    @Autowired TradingService trading;
    @Autowired LivePositionRepository positions;
    @Autowired TradeOrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired StrategyLiveConfigRepository configs;
    @MockitoBean KiteApi kite;
    @MockitoBean MarketDataClient marketData;

    static Instant at(LocalDate d, int h, int m) { return d.atTime(h, m).atZone(IST).toInstant(); }
    static Candle c(LocalDate d, int h, int m, double o, double hi, double lo, double cl) {
        return new Candle(at(d, h, m), BigDecimal.valueOf(o), BigDecimal.valueOf(hi), BigDecimal.valueOf(lo), BigDecimal.valueOf(cl), 0);
    }

    final List<Candle> five = List.of(
            c(MON, 15, 25, 24000, 24010, 23990, 24000),                 // previous close 24,000
            c(TUE, 9, 15, 24100, 24150, 24080, 24120),                  // gap +0.42%, ref high 24,150 / low 24,080
            c(TUE, 9, 20, 24120, 24200, 24110, 24190),                  // fresh close above 24,150 → CE 24150 (ATM 24,200 − 50)
            c(TUE, 9, 25, 24190, 24195, 24100, 24110));
    final List<Candle> one = List.of(
            c(TUE, 9, 25, 24190, 24195, 24170, 24180), c(TUE, 9, 26, 24180, 24185, 24160, 24170),
            c(TUE, 9, 27, 24170, 24172, 24100, 24110),                  // low 24,100 ≤ stop 24,115
            c(TUE, 9, 28, 24110, 24120, 24100, 24115), c(TUE, 9, 29, 24115, 24120, 24105, 24110));

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM live_position");
        jdbc.update("DELETE FROM trade_order");
        jdbc.update("UPDATE trading_control SET kill_switch = FALSE, max_orders_per_day = 10, max_quantity_per_order = 130, daily_loss_limit = 5000");
        when(kite.niftyOptions()).thenReturn(List.of(
                new KiteApi.OptionInstrument("NIFTY26N0324150CE", TUE, 24150, "CE", 65, 0.05),
                new KiteApi.OptionInstrument("NIFTY26N1024150CE", TUE.plusDays(7), 24150, "CE", 65, 0.05)));
        when(marketData.getRange(anyString(), any(), any(), any())).thenAnswer(inv -> {
            Interval iv = inv.getArgument(1);
            Instant to = inv.getArgument(3);
            List<Candle> src = iv == Interval.MINUTE_5 ? five : one;
            List<Candle> upto = new ArrayList<>(src.stream().filter(x -> !x.timestamp().isAfter(to)).toList());
            return new PriceSeries("NIFTY 50", iv, upto);
        });
        jdbc.update("DELETE FROM strategy_user_mode");
        paper(56L, user("runner-a"));
    }

    private long user(String name) {
        jdbc.update("INSERT INTO app_user (username, status, created_at) SELECT ?, 'ACTIVE', CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = ?)", name, name);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, name);
    }

    private void paper(long strategyId, long userId) {
        StrategyLiveConfig c = new StrategyLiveConfig(strategyId, userId);
        c.set(TradingMode.PAPER, "MIS");
        configs.save(c);
    }

    @Test
    void opensOnTheBreakoutAndClosesOnTheStopWithRealOptionPnl() {
        when(kite.ltp(anyList())).thenReturn(Map.of("NFO:NIFTY26N0324150CE", 120.0));
        CLOCK.set(at(TUE, 9, 26));
        assertThat(runner.runAll()).anyMatch(l -> l.contains("BUY 130 NIFTY26N0324150CE"));

        LivePosition p = positions.findByTradeDateOrderByOpenedAtDesc(TUE).get(0);
        assertThat(p.getStatus()).isEqualTo(LivePosition.Status.OPEN);
        assertThat(p.getQuantity()).isEqualTo(130);                   // 2 lots × 65 (from the instrument list)
        assertThat(p.getEntryPrice()).isEqualTo(120.0);
        assertThat(p.getTradingsymbol()).isEqualTo("NIFTY26N0324150CE");   // nearest expiry, not next week's

        assertThat(runner.runAll()).anyMatch(l -> l.contains("no change"));    // nothing new: no duplicate entry

        when(kite.ltp(anyList())).thenReturn(Map.of("NFO:NIFTY26N0324150CE", 100.0));
        CLOCK.set(at(TUE, 9, 31));
        assertThat(runner.runAll()).anyMatch(l -> l.contains("SELL 130 NIFTY26N0324150CE (SL_HIT)"));
        p = positions.findById(p.getId()).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(LivePosition.Status.CLOSED);
        assertThat(p.getExitReason()).isEqualTo("SL_HIT");
        assertThat(p.getPnl()).isEqualTo((100.0 - 120.0) * 130 - 85);    // real option prices minus charges
        assertThat(orders.findByTradeDateOrderByCreatedAtDesc(TUE)).extracting(TradeOrder::getTransactionType).containsExactly("SELL", "BUY");
    }

    @Test
    void doesNotEnterAStaleSignalOrWhileTheKillSwitchIsOn() {
        when(kite.ltp(anyList())).thenReturn(Map.of("NFO:NIFTY26N0324150CE", 120.0));
        five.size();
        CLOCK.set(at(TUE, 9, 26));
        jdbc.update("UPDATE trading_control SET kill_switch = TRUE");
        assertThat(runner.runAll()).anyMatch(l -> l.contains("kill switch"));
        assertThat(positions.findByTradeDateOrderByOpenedAtDesc(TUE)).isEmpty();
        jdbc.update("UPDATE trading_control SET kill_switch = FALSE");
        // Same trade but seen at 09:31 is gone by then; a breakout seen 6+ minutes late is skipped:
        CLOCK.set(at(TUE, 9, 32));
        runner.runAll();
        assertThat(positions.findByTradeDateOrderByOpenedAtDesc(TUE)).isEmpty();
    }

    @Test
    void marketHours() {
        assertThat(LiveStrategyRunner.marketOpen(at(TUE, 9, 19))).isFalse();
        assertThat(LiveStrategyRunner.marketOpen(at(TUE, 9, 20))).isTrue();
        assertThat(LiveStrategyRunner.marketOpen(at(TUE, 15, 32))).isFalse();
        assertThat(LiveStrategyRunner.marketOpen(at(LocalDate.of(2026, 11, 7), 11, 0))).isFalse();   // Saturday
    }

    @Test
    void everyUserTradesTheirOwnCopyOfAStrategy() {
        long a = user("runner-a"), b = user("runner-b");
        paper(56L, b);
        when(kite.ltp(anyList())).thenReturn(Map.of("NFO:NIFTY26N0324150CE", 120.0));
        CLOCK.set(at(TUE, 9, 26));
        List<String> report = runner.runAll();
        assertThat(report).anyMatch(l -> l.contains("user " + a) && l.contains("BUY 130"))
                          .anyMatch(l -> l.contains("user " + b) && l.contains("BUY 130"));
        assertThat(positions.findByTradeDateAndUserIdOrderByOpenedAtDesc(TUE, a)).hasSize(1);
        assertThat(positions.findByTradeDateAndUserIdOrderByOpenedAtDesc(TUE, b)).hasSize(1);
        assertThat(orders.findByTradeDateAndUserIdOrderByCreatedAtDesc(TUE, a)).extracting(TradeOrder::getTransactionType).containsExactly("BUY");
        assertThat(orders.findByTradeDateAndUserIdOrderByCreatedAtDesc(TUE, b)).extracting(TradeOrder::getTransactionType).containsExactly("BUY");
    }
}
