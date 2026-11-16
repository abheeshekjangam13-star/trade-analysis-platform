package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.strategy.engine.MarketDataClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketHolidaysTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Clock FRI_13_NOV = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), IST);

    /** Kite's daily candles are stamped at IST midnight. */
    private static Candle day(String d) {
        BigDecimal one = BigDecimal.ONE;
        return new Candle(LocalDate.parse(d).atStartOfDay(IST).toInstant(), one, one, one, one, 0);
    }

    private static MarketDataClient serving(List<Candle> candles) {
        return new MarketDataClient() {
            public PriceSeries getSeries(String symbol, Interval interval, int limit) { throw new UnsupportedOperationException(); }
            public PriceSeries getRange(String symbol, Interval interval, Instant from, Instant to) {
                return new PriceSeries(symbol, interval, candles.stream()
                        .filter(c -> !c.timestamp().isBefore(from) && !c.timestamp().isAfter(to)).toList());
            }
        };
    }

    private static final TradingDays NONE_LISTED = new TradingDays("");
    /** NSE's 2026 list near November: Diwali Balipratipada and Guru Nanak Jayanti. */
    private static final TradingDays LISTED = new TradingDays("2026-11-10,2026-11-24,2026-12-25");

    private static final List<Candle> NOV = Stream.of("2026-11-02", "2026-11-03", "2026-11-04", "2026-11-05", "2026-11-06",
            "2026-11-09", "2026-11-11", "2026-11-12", "2026-11-13").map(MarketHolidaysTest::day).toList();

    @Test
    void weekdaysWithoutADailyCandleAreHolidaysWeekendsAreNot() {
        List<LocalDate> closed = new MarketHolidays(serving(NOV), FRI_13_NOV, NONE_LISTED).between(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"));
        assertThat(closed).containsExactly(LocalDate.parse("2026-11-10"));   // 7-8 Nov are a weekend; nothing listed ahead
    }

    @Test
    void saysNothingWhenTheCandlesCannotBeHad() {
        MarketDataClient down = new MarketDataClient() {
            public PriceSeries getSeries(String s, Interval i, int l) { throw new IllegalStateException("down"); }
            public PriceSeries getRange(String s, Interval i, Instant f, Instant t) { throw new IllegalStateException("No data in range"); }
        };
        assertThat(new MarketHolidays(down, FRI_13_NOV, NONE_LISTED).between(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"))).isEmpty();
        assertThat(new MarketHolidays(serving(List.of()), FRI_13_NOV, NONE_LISTED).between(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"))).isEmpty();
    }

    @Test
    void refusesBackwardsOrHugeRanges() {
        MarketHolidays h = new MarketHolidays(serving(NOV), FRI_13_NOV, NONE_LISTED);
        assertThatThrownBy(() -> h.between(LocalDate.parse("2026-11-30"), LocalDate.parse("2026-11-01"))).isInstanceOf(TradingException.class);
        assertThatThrownBy(() -> h.between(LocalDate.parse("2015-01-01"), LocalDate.parse("2026-11-01"))).isInstanceOf(TradingException.class);
    }

    @Test
    void upcomingHolidaysComeFromNsesListSoTheCalendarShowsThemAhead() {
        List<LocalDate> closed = new MarketHolidays(serving(NOV), FRI_13_NOV, LISTED).between(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"));
        assertThat(closed).containsExactly(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-24"));   // past from candles, 24 Nov from the list

        MarketDataClient down = new MarketDataClient() {
            public PriceSeries getSeries(String symbol, Interval interval, int limit) { throw new IllegalStateException("down"); }
            public PriceSeries getRange(String symbol, Interval interval, Instant from, Instant to) { throw new IllegalStateException("down"); }
        };
        assertThat(new MarketHolidays(down, FRI_13_NOV, LISTED).between(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-31")))
                .containsExactly(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-24"), LocalDate.parse("2026-12-25"));   // no candles: the list
    }
}
