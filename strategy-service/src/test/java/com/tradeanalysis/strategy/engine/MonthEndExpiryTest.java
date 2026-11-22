package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.indicator.Params;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.tradeanalysis.strategy.engine.PreviousRangeEngineTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonthEndExpiryTest {

    @AfterEach
    void noHolidays() { MonthEndExpiry.useHolidays(Set.of()); }

    @Test
    void lastTuesdayMovedBeforeAHolidayAndLastThursdayBeforeSeptember2025() {
        MonthEndExpiry.useHolidays(Set.of(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 3, 31)));
        assertThat(MonthEndExpiry.of(LocalDate.of(2026, 11, 2))).isEqualTo(LocalDate.of(2026, 11, 23));   // 24 Nov: Guru Nanak Jayanti
        assertThat(MonthEndExpiry.of(LocalDate.of(2026, 3, 5))).isEqualTo(LocalDate.of(2026, 3, 30));     // 31 Mar: holiday
        assertThat(MonthEndExpiry.of(LocalDate.of(2026, 10, 1))).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(MonthEndExpiry.of(LocalDate.of(2025, 8, 1))).isEqualTo(LocalDate.of(2025, 8, 28));     // Thursday era
        assertThat(MonthEndExpiry.of(LocalDate.of(2025, 9, 1))).isEqualTo(LocalDate.of(2025, 9, 30));     // Tuesday from Sept 2025
        assertThat(MonthEndExpiry.is(LocalDate.of(2026, 11, 23))).isTrue();
        assertThat(MonthEndExpiry.is(LocalDate.of(2026, 11, 17))).isFalse();                              // a weekly expiry
    }

    @Test
    void theSettingIsTrueOrFalseAndOffByDefault() {
        assertThat(MonthEndExpiry.enabled(new Params(Map.of()))).isFalse();
        assertThat(MonthEndExpiry.enabled(new Params(Map.of("skipMonthEndExpiry", "true")))).isTrue();
        assertThat(MonthEndExpiry.enabled(new Params(Map.of("skipMonthEndExpiry", "FALSE")))).isFalse();
        assertThatThrownBy(() -> MonthEndExpiry.enabled(new Params(Map.of("skipMonthEndExpiry", "maybe"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("skipMonthEndExpiry");
    }

    @Test
    void anEngineTakesNoTradeOnTheMonthEndExpiryWhenAsked() {
        LocalDate prev = LocalDate.of(2026, 10, 26), day = LocalDate.of(2026, 10, 27);   // 27 Oct 2026: month-end expiry
        List<Candle> fiveMin = List.of(
                c(prev, 9, 15, 24050, 24200, 24000, 24100), c(prev, 15, 25, 24100, 24150, 24080, 24100),
                c(day, 9, 15, 24100, 24120, 24050, 24080), c(day, 9, 20, 24080, 24090, 24060, 24070),
                c(day, 9, 25, 24070, 24075, 23985, 23990), c(day, 9, 30, 23990, 24000, 23960, 23970));
        PriceSeries series = new PriceSeries("NIFTY 50", Interval.MINUTE_5, fiveMin);
        PriceSeries minutes = new PriceSeries("NIFTY 50", Interval.MINUTE_1, List.of(c(day, 9, 30, 23990, 23992, 23950, 23955)));
        java.time.Instant after = day.plusDays(1).atTime(10, 0).atZone(IST).toInstant();

        assertThat(new PreviousRangeEngine().run(series, minutes, params(Map.of()), 7L, after, null).tradeLogs()).hasSize(1);

        var skipped = new PreviousRangeEngine().run(series, minutes, params(Map.of("skipMonthEndExpiry", "true")), 7L, after, null);
        assertThat(skipped.tradeLogs()).isEmpty();
        assertThat(skipped.days()).anySatisfy(d -> {
            assertThat(d.date()).isEqualTo(day);
            assertThat(d.status()).isEqualTo("SKIPPED");
            assertThat(d.reason()).isEqualTo(MonthEndExpiry.REASON);
        });
    }

    @Test
    void onlyMonthEndExpiryTradesThatDayAndNoOther() {
        java.util.function.Function<LocalDate, PriceSeries[]> data = day -> {
            LocalDate prev = day.minusDays(1);
            PriceSeries five = new PriceSeries("NIFTY 50", Interval.MINUTE_5, List.of(
                    c(prev, 9, 15, 24050, 24200, 24000, 24100), c(prev, 15, 25, 24100, 24150, 24080, 24100),
                    c(day, 9, 15, 24100, 24120, 24050, 24080), c(day, 9, 20, 24080, 24090, 24060, 24070),
                    c(day, 9, 25, 24070, 24075, 23985, 23990), c(day, 9, 30, 23990, 24000, 23960, 23970)));
            return new PriceSeries[]{five, new PriceSeries("NIFTY 50", Interval.MINUTE_1, List.of(c(day, 9, 30, 23990, 23992, 23950, 23955)))};
        };
        Params only = params(Map.of("onlyMonthEndExpiry", "true"));
        LocalDate monthEnd = LocalDate.of(2026, 10, 27), weekly = LocalDate.of(2026, 10, 20);

        PriceSeries[] me = data.apply(monthEnd);
        assertThat(new PreviousRangeEngine().run(me[0], me[1], only, 7L, monthEnd.plusDays(1).atTime(10, 0).atZone(IST).toInstant(), null).tradeLogs()).hasSize(1);

        PriceSeries[] wk = data.apply(weekly);
        var skipped = new PreviousRangeEngine().run(wk[0], wk[1], only, 7L, weekly.plusDays(1).atTime(10, 0).atZone(IST).toInstant(), null);
        assertThat(skipped.tradeLogs()).isEmpty();
        assertThat(skipped.days()).anySatisfy(d -> {
            assertThat(d.date()).isEqualTo(weekly);
            assertThat(d.status()).isEqualTo("SKIPPED");
            assertThat(d.reason()).isEqualTo(MonthEndExpiry.ONLY_REASON);
        });
    }

    @Test
    void skipAndOnlyCantBothBeTrue() {
        assertThat(MonthEndExpiry.only(new Params(Map.of()))).isFalse();
        assertThatThrownBy(() -> NiftyOpeningBreakoutEngine.validateParams(new Params(Map.of("skipMonthEndExpiry", "true", "onlyMonthEndExpiry", "true"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("can't both be true");
        assertThatThrownBy(() -> NiftyOpeningBreakoutEngine.validateParams(new Params(Map.of("onlyMonthEndExpiry", "maybe"))))
                .hasMessageContaining("onlyMonthEndExpiry");
    }
}
