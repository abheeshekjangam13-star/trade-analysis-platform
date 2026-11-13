package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import com.tradeanalysis.strategy.model.Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A backtest built from stored days must be exactly the backtest a full simulation gives: same trades, P&L, day
 * reports and signals. Runs each cached engine three times over the same synthetic candles: with nothing stored (A),
 * with every past day stored (B), and with some days in the middle removed again (C).
 */
@SpringBootTest(properties = {"trading.runner.cron=-", "trading.exit-watch.initial-delay-ms=3600000"})
@ActiveProfiles("it")
class BacktestDayCacheEquivalenceTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String NIFTY = "NIFTY 50";
    static final int LOOKBACK = 45;
    static final double CAPITAL = 100_000_000;   // never short of capital: the capital limit doesn't hide differences

    @Autowired BacktestEngine engine;
    @Autowired BacktestDayCacheRepository cacheRows;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MarketDataClient marketData;

    static final Map<Interval, List<Candle>> CANDLES = syntheticNifty(LocalDate.now(IST), 48);
    static final List<Candle> VIX = CANDLES.get(Interval.DAY_1).stream()
            .map(c -> new Candle(c.timestamp(), BigDecimal.valueOf(14), BigDecimal.valueOf(14), BigDecimal.valueOf(14), BigDecimal.valueOf(14), 0)).toList();

    /**
     * A seeded random walk: `weekdays` full sessions (09:15–15:29 one-minute bars) before today, with overnight gaps,
     * and the 5-minute and daily candles built from them. Nothing for today, so every simulated day is a past day.
     */
    static Map<Interval, List<Candle>> syntheticNifty(LocalDate today, int weekdays) {
        Random rnd = new Random(20261113);
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = today.minusDays(1); days.size() < weekdays; d = d.minusDays(1))
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) days.add(0, d);
        List<Candle> m1 = new ArrayList<>(), m5 = new ArrayList<>(), d1 = new ArrayList<>();
        double price = 25_000;
        for (LocalDate d : days) {
            price *= 1 + (rnd.nextDouble() - 0.5) * 0.016;                 // overnight gap up to ±0.8%
            double drift = (rnd.nextDouble() - 0.5) * 0.6;                 // the day's trend, points per minute
            List<Candle> day = new ArrayList<>();
            for (int i = 0; i < 375; i++) {
                double open = price, close = open + drift + rnd.nextGaussian() * 7;
                double high = Math.max(open, close) + rnd.nextDouble() * 4, low = Math.min(open, close) - rnd.nextDouble() * 4;
                day.add(candle(d.atTime(9, 15).plusMinutes(i).atZone(IST).toInstant(), open, high, low, close));
                price = close;
            }
            m1.addAll(day);
            for (int i = 0; i < 375; i += 5) m5.add(merge(day.subList(i, i + 5)));
            d1.add(merge(day, d.atStartOfDay(IST).toInstant()));
        }
        return Map.of(Interval.MINUTE_1, m1, Interval.MINUTE_5, m5, Interval.DAY_1, d1);
    }

    static Candle candle(Instant t, double o, double h, double l, double c) {
        return new Candle(t, round(o), round(h), round(l), round(c), 0);
    }
    static BigDecimal round(double v) { return BigDecimal.valueOf(Math.round(v * 100) / 100.0); }
    static Candle merge(List<Candle> bars) { return merge(bars, bars.get(0).timestamp()); }
    static Candle merge(List<Candle> bars, Instant t) {
        BigDecimal hi = bars.stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal lo = bars.stream().map(Candle::low).min(BigDecimal::compareTo).orElseThrow();
        return new Candle(t, bars.get(0).open(), hi, lo, bars.get(bars.size() - 1).close(), 0);
    }

    @BeforeEach
    void setUp() {
        cacheRows.deleteAll();
        // Like market-data: the candles in [from, to], and an error for a range with none.
        when(marketData.getRange(anyString(), any(), any(), any())).thenAnswer(inv -> {
            String symbol = inv.getArgument(0);
            Interval iv = inv.getArgument(1);
            Instant from = inv.getArgument(2), to = inv.getArgument(3);
            List<Candle> src = "INDIA VIX".equals(symbol) ? (iv == Interval.DAY_1 ? VIX : List.of()) : CANDLES.getOrDefault(iv, List.of());
            List<Candle> in = src.stream().filter(c -> !c.timestamp().isBefore(from) && !c.timestamp().isAfter(to)).toList();
            if (in.isEmpty()) throw new IllegalStateException("No data in range for symbol: " + symbol);
            return new PriceSeries(symbol, iv, in);
        });
    }

    static Strategy strategy(String... kv) {
        Map<String, String> p = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put(kv[i], kv[i + 1]);
        return new Strategy("cache test", "", "NIFTY_OPENING_BREAKOUT", p);
    }

    BacktestEngine.BacktestOutput run(Strategy s) { return engine.run(s, NIFTY, LOOKBACK, CAPITAL); }

    static void assertSame(BacktestEngine.BacktestOutput actual, BacktestEngine.BacktestOutput expected) {
        assertThat(actual.tradeLogs())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("id", "createdAt", "backtestResultId")
                .containsExactlyElementsOf(expected.tradeLogs());
        assertThat(actual.days()).containsExactlyElementsOf(expected.days());
        assertThat(actual.signals()).containsExactlyElementsOf(expected.signals());
        assertThat(actual.finalCapital()).isEqualTo(expected.finalCapital());
        assertThat(actual.maxDrawdown()).isEqualTo(expected.maxDrawdown());
        assertThat(actual.totalTrades()).isEqualTo(expected.totalTrades());
    }

    /** A, B and C for one strategy; returns A. */
    BacktestEngine.BacktestOutput checkEquivalent(Strategy s) {
        BacktestEngine.BacktestOutput a = run(s);                                       // nothing stored: full simulation
        long stored = cacheRows.count();
        assertThat(stored).as("past days stored after the first run").isGreaterThan(20);

        clearInvocations(marketData);
        BacktestEngine.BacktestOutput b = run(s);                                       // every past day stored
        assertSame(b, a);
        assertThat(cacheRows.count()).isEqualTo(stored);
        LocalDate tradeFrom = LocalDate.now(IST).minusDays(LOOKBACK);
        verify(marketData, never()).getRange(eq(NIFTY), eq(Interval.MINUTE_1), eq(tradeFrom.atStartOfDay(IST).toInstant()), any());

        List<LocalDate> dates = jdbc.queryForList("SELECT trade_date FROM backtest_day_cache ORDER BY trade_date", LocalDate.class);
        for (LocalDate d : List.of(dates.get(5), dates.get(6), dates.get(15)))           // two gaps in the middle
            jdbc.update("DELETE FROM backtest_day_cache WHERE trade_date = ?", d);
        BacktestEngine.BacktestOutput c = run(s);
        assertSame(c, a);
        assertThat(cacheRows.count()).isEqualTo(stored);                                 // the removed days are stored again
        return a;
    }

    @Test
    void openingBreakout() {
        BacktestEngine.BacktestOutput a = checkEquivalent(strategy("lots", "2", "lotSize", "65"));
        assertThat(a.tradeLogs()).as("the synthetic market makes the default breakout trade").isNotEmpty();
    }

    @Test
    void previousDayRange() {
        checkEquivalent(strategy("referenceMode", "PREVIOUS_DAY", "directions", "PE", "entryUntil", "14:00", "targetPoints", "30"));
    }

    @Test
    void cpr() {
        checkEquivalent(strategy("referenceMode", "CPR", "targetPoints", "80"));
    }

    @Test
    void gapFill() {
        checkEquivalent(strategy("referenceMode", "GAP_FILL", "gapPct", "0.2", "maxGapPct", "2", "gapFillTarget", "0.5"));
    }

    @Test
    void emaCrossoverIsNeverStored() {
        Strategy s = strategy("referenceMode", "EMA_CROSS");
        BacktestEngine.BacktestOutput a = run(s);
        assertThat(cacheRows.count()).isZero();
        assertSame(run(s), a);
    }

    @Test
    void tradesFromStoredDaysAreNewRows() {
        Strategy s = strategy("lots", "2", "lotSize", "65");
        run(s);
        List<NiftyTradeLog> again = run(s).tradeLogs();
        assertThat(again).isNotEmpty().allSatisfy(t -> {
            assertThat(t.getId()).isNull();
            assertThat(t.getBacktestResultId()).isNull();
        });
    }
}
