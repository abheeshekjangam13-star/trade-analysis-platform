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
import static org.mockito.Mockito.*;

/**
 * The live runner with the real engine and master strategy #56 (CE gap-up) on made-up candles, in PAPER mode,
 * Kite and market data mocked. Tue 3 Nov 2026: +0.42% gap-up, 09:20 candle breaks above the 09:15 high → BUY;
 * the 09:27 one-minute low hits the 24,115 midpoint stop → SELL.
 */
// The schedules are off ("-", an hour's delay): the tests call runAll / check themselves.
@SpringBootTest(properties = {"trading.runner.enabled=true", "trading.runner.cron=-", "trading.exit-watch.initial-delay-ms=3600000"})
@ActiveProfiles("it")
class LiveStrategyRunnerTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate MON = LocalDate.of(2026, 11, 2), TUE = LocalDate.of(2026, 11, 3);
    static final MutableClock CLOCK = new MutableClock(at(TUE, 9, 26));

    @TestConfiguration
    static class Clocks { @Bean @org.springframework.context.annotation.Primary Clock testClock() { return CLOCK; } }

    @Autowired LiveStrategyRunner runner;
    @Autowired LiveExitWatcher watcher;
    @Autowired QuoteCache quoteCache;
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
        quoteCache.clear();
        jdbc.update("DELETE FROM live_position");
        jdbc.update("DELETE FROM trade_order");
        jdbc.update("UPDATE trading_control SET kill_switch = FALSE, max_orders_per_day = 10, max_quantity_per_order = 130, daily_loss_limit = 5000");
        when(kite.optionsOf(com.tradeanalysis.common.market.Underlying.NIFTY)).thenReturn(List.of(
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
        jdbc.update("UPDATE app_user SET max_day_loss = NULL");
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
    void theUsersMaxDayLossClosesOpenPositionsAndBlocksNewEntries() {
        long a = user("runner-a");
        jdbc.update("UPDATE app_user SET max_day_loss = 3000 WHERE id = ?", a);
        when(kite.ltp(anyList())).thenReturn(Map.of("NFO:NIFTY26N0324150CE", 120.0));
        CLOCK.set(at(TUE, 9, 26));
        assertThat(runner.runAll()).anyMatch(l -> l.contains("BUY 130 NIFTY26N0324150CE"));

        // The option falls to 90: (90 − 120) × 130 = −3,900 open loss, past the 3,000 limit → closed.
        when(kite.ltp(anyList())).thenReturn(Map.of("NFO:NIFTY26N0324150CE", 90.0));
        assertThat(runner.enforceDayLoss()).anyMatch(l -> l.contains("SELL 130 NIFTY26N0324150CE (MAX_DAY_LOSS)"));
        LivePosition p = positions.findByTradeDateOrderByOpenedAtDesc(TUE).get(0);
        assertThat(p.getStatus()).isEqualTo(LivePosition.Status.CLOSED);
        assertThat(p.getExitReason()).isEqualTo("MAX_DAY_LOSS");
        assertThat(p.getPnl()).isEqualTo(-3900.0 - 85);   // minus strategy #56's charges per trade

        // The rest of the day: a new entry for any of the user's strategies is refused.
        positions.deleteAll();
        jdbc.update("INSERT INTO live_position (strategy_id, user_id, trade_date, mode, option_type, tradingsymbol, quantity, entry_candle, entry_spot, status, pnl, opened_at, closed_at) "
                + "VALUES (56, ?, ?, 'PAPER', 'CE', 'X', 130, ?, 24000, 'CLOSED', -3900, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", a, TUE, at(TUE, 9, 0));
        assertThat(runner.runAll()).anyMatch(l -> l.contains("entry blocked: Your max day loss is reached"));
        assertThat(positions.findByTradeDateOrderByOpenedAtDesc(TUE)).hasSize(1);   // only the old closed one
    }

    @Test
    void oneMinuteRunReadsTheIndexCandlesOncePerIntervalForAllItsStrategies() {
        paper(55L, user("runner-b"));   // a second strategy on the same index
        when(kite.ltp(anyList())).thenReturn(Map.of("NFO:NIFTY26N0324150CE", 120.0));
        CLOCK.set(at(TUE, 9, 26));
        clearInvocations(marketData);
        runner.runAll();
        verify(marketData, times(1)).getRange(anyString(), eq(Interval.MINUTE_1), any(), any());
        verify(marketData, atMost(2)).getRange(anyString(), eq(Interval.MINUTE_5), any(), any());   // widened once if the warm-ups differ
    }

    @Test
    void anotherUserBelowTheirLimitKeepsTrading() {
        long a = user("runner-a");
        jdbc.update("UPDATE app_user SET max_day_loss = 50000 WHERE id = ?", a);
        when(kite.ltp(anyList())).thenReturn(Map.of("NFO:NIFTY26N0324150CE", 120.0));
        CLOCK.set(at(TUE, 9, 26));
        runner.runAll();
        when(kite.ltp(anyList())).thenReturn(Map.of("NFO:NIFTY26N0324150CE", 90.0));
        assertThat(runner.enforceDayLoss()).isEmpty();
        assertThat(positions.findByTradeDateOrderByOpenedAtDesc(TUE).get(0).getStatus()).isEqualTo(LivePosition.Status.OPEN);
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

    /** Kite prices: the option, and the index the exit watcher reads. */
    private void prices(double option, double nifty) {
        when(kite.ltp(anyList())).thenAnswer(inv -> {
            Map<String, Double> out = new java.util.HashMap<>();
            for (String sym : inv.<List<String>>getArgument(0))
                if (sym.startsWith("NFO:")) out.put(sym, option); else if (sym.equals("NSE:NIFTY 50")) out.put(sym, nifty);
            return out;
        });
    }

    @Test
    void theExitWatcherSellsTheMomentTheIndexTouchesTheStopAndTheMinuteRunDoesNotSellAgain() {
        prices(120.0, 24180);
        CLOCK.set(at(TUE, 9, 26));
        assertThat(runner.runAll()).anyMatch(l -> l.contains("BUY 130 NIFTY26N0324150CE"));   // CE from 24,190, stop 24,115

        assertThat(watcher.check()).isEmpty();                                     // 24,180: between stop and target

        prices(100.0, 24112);                                                      // 09:26:40 the index trades through the stop
        CLOCK.set(at(TUE, 9, 26).plusSeconds(40));
        assertThat(watcher.check()).anyMatch(l -> l.contains("SELL 130 NIFTY26N0324150CE (SL_HIT)"));
        LivePosition p = positions.findByTradeDateOrderByOpenedAtDesc(TUE).get(0);
        assertThat(p.getStatus()).isEqualTo(LivePosition.Status.CLOSED);
        assertThat(p.getExitReason()).isEqualTo("SL_HIT");
        assertThat(p.getExitSpot()).isEqualTo(24112.0);
        assertThat(p.getPnl()).isEqualTo((100.0 - 120.0) * 130 - 85);

        CLOCK.set(at(TUE, 9, 26).plusSeconds(42));
        assertThat(watcher.check()).isEmpty();                                     // closed: nothing more to watch
        CLOCK.set(at(TUE, 9, 31));                                                 // the minute run sees the engine's stop too
        runner.runAll();
        assertThat(orders.findByTradeDateOrderByCreatedAtDesc(TUE)).extracting(TradeOrder::getTransactionType).containsExactly("SELL", "BUY");
    }

    @Test
    void theExitWatcherCatchesATouchOnlyTheMinuteCandleSaw() {
        prices(120.0, 24180);
        CLOCK.set(at(TUE, 9, 26));
        runner.runAll();                                                           // CE from 24,190, stop 24,115; entered 09:25
        watcher.readCandlesNextCheck();
        // Every price the watcher saw was 24,180, but the forming 09:26 candle dipped to 24,114 between two of them.
        // The 09:25 candle went lower still, but that was the entry minute: it doesn't count.
        when(kite.minuteBars(eq("NIFTY 50"), any(), any())).thenReturn(List.of(
                new KiteApi.MinuteBar(at(TUE, 9, 25), 24195, 24100),
                new KiteApi.MinuteBar(at(TUE, 9, 26), 24185, 24114)));
        CLOCK.set(at(TUE, 9, 26).plusSeconds(20));
        assertThat(watcher.check()).anyMatch(l -> l.contains("SELL 130 NIFTY26N0324150CE (SL_HIT)"));
        LivePosition p = positions.findByTradeDateOrderByOpenedAtDesc(TUE).get(0);
        assertThat(p.getExitReason()).isEqualTo("SL_HIT");
        assertThat(p.getExitSpot()).isEqualTo(24115.0);                            // at the stop, as the backtests do
    }

    @Test
    void theEntryMinutesCandleNeverClosesAPosition() {
        prices(120.0, 24180);
        CLOCK.set(at(TUE, 9, 26));
        runner.runAll();
        watcher.readCandlesNextCheck();
        when(kite.minuteBars(eq("NIFTY 50"), any(), any())).thenReturn(List.of(new KiteApi.MinuteBar(at(TUE, 9, 25), 24195, 24100)));
        CLOCK.set(at(TUE, 9, 26).plusSeconds(20));
        assertThat(watcher.check()).isEmpty();
        assertThat(positions.findByTradeDateOrderByOpenedAtDesc(TUE).get(0).getStatus()).isEqualTo(LivePosition.Status.OPEN);
    }

    @Test
    void theExitWatcherTakesTheTargetToo() {
        prices(120.0, 24180);
        CLOCK.set(at(TUE, 9, 26));
        runner.runAll();
        prices(175.0, 24271);                                                      // entry 24,190 + 80 target points = 24,270
        CLOCK.set(at(TUE, 9, 26).plusSeconds(30));
        assertThat(watcher.check()).anyMatch(l -> l.contains("(TARGET_HIT)"));
        assertThat(positions.findByTradeDateOrderByOpenedAtDesc(TUE).get(0).getPnl()).isEqualTo((175.0 - 120.0) * 130 - 85);
    }

    @Test
    void theExitWatcherDoesNothingWithoutAnIndexPrice() {
        prices(120.0, 24180);
        CLOCK.set(at(TUE, 9, 26));
        runner.runAll();
        when(kite.ltp(anyList())).thenReturn(Map.of("NFO:NIFTY26N0324150CE", 100.0));   // Kite gives no index price
        CLOCK.set(at(TUE, 9, 26).plusSeconds(30));
        assertThat(watcher.check()).isEmpty();
        assertThat(positions.findByTradeDateOrderByOpenedAtDesc(TUE).get(0).getStatus()).isEqualTo(LivePosition.Status.OPEN);
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
    void runsThreeSecondsAfterEachMinuteClosesInIst() throws Exception {
        var scheduled = LiveStrategyRunner.class.getMethod("tick").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("${trading.runner.cron:3 * * * * *}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Kolkata");
        var next = org.springframework.scheduling.support.CronExpression.parse("3 * * * * *").next(at(TUE, 10, 5).atZone(IST));
        assertThat(next.toLocalTime()).isEqualTo(LocalTime.of(10, 5, 3));   // the 10:00 candle closed at 10:05:00
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

    @Test
    void muhuratTradingOnSunday8Nov2026CountsAsMarketHours() {
        LocalDate muhurat = LocalDate.of(2026, 11, 8);
        assertThat(LiveStrategyRunner.marketOpen(at(muhurat, 14, 4))).isFalse();
        assertThat(LiveStrategyRunner.marketOpen(at(muhurat, 14, 20))).isTrue();
        assertThat(LiveStrategyRunner.marketOpen(at(muhurat, 15, 39))).isTrue();
        assertThat(LiveStrategyRunner.marketOpen(at(muhurat, 15, 40))).isFalse();
        assertThat(LiveStrategyRunner.marketOpen(at(LocalDate.of(2026, 11, 15), 14, 30))).isFalse();   // a normal Sunday

        // The banner's lookup: the whole day has the session, with its trading hours
        var s = com.tradeanalysis.common.market.SpecialSessions.on(muhurat).orElseThrow();
        assertThat(s.name()).isEqualTo("Muhurat trading");
        assertThat(s.tradingFrom()).hasToString("14:20");
        assertThat(s.tradingTo()).hasToString("15:30");
        assertThat(com.tradeanalysis.common.market.SpecialSessions.on(LocalDate.of(2026, 11, 9))).isEmpty();
        assertThat(com.tradeanalysis.common.market.SpecialSessions.at(at(muhurat, 12, 16))).isEmpty();   // before pre-open
    }
}
