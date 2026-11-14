package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.strategy.notify.PushService.Alert;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TradingDaysTest {

    /** The list as configured in application.yml (folded YAML: commas, spaces and line breaks). */
    private final TradingDays days = new TradingDays("""
            2026-01-15,2026-01-26,2026-03-03,2026-03-26,2026-03-31,2026-04-03,2026-04-14,2026-05-01,2026-05-28,
            2026-06-26,2026-09-14,2026-10-02,2026-10-20,2026-11-10,2026-11-24,2026-12-25""");

    @Test
    void holidaysAndWeekendsAreNotTradingDays() {
        assertThat(days.isTradingDay(LocalDate.of(2026, 11, 24))).isFalse();   // Guru Nanak Jayanti (Tuesday)
        assertThat(days.isTradingDay(LocalDate.of(2026, 12, 25))).isFalse();   // Christmas (Friday)
        assertThat(days.isTradingDay(LocalDate.of(2026, 1, 15))).isFalse();    // added later by circular
        assertThat(days.isTradingDay(LocalDate.of(2026, 11, 14))).isFalse();   // Saturday
        assertThat(days.isTradingDay(LocalDate.of(2026, 11, 16))).isTrue();    // Monday
        assertThat(days.isTradingDay(LocalDate.of(2026, 11, 23))).isTrue();
    }

    @Test
    void knowsWhetherAYearIsCovered() {
        assertThat(days.coversYear(2026)).isTrue();
        assertThat(days.coversYear(2027)).isFalse();
    }

    @Test
    void badEntriesAreSkippedAndAnEmptyListMeansWeekdaysOnly() {
        TradingDays odd = new TradingDays("2026-11-24, 24/12/2026 ,, 2026-12-25");
        assertThat(odd.isHoliday(LocalDate.of(2026, 11, 24))).isTrue();
        assertThat(odd.isHoliday(LocalDate.of(2026, 12, 25))).isTrue();
        TradingDays none = new TradingDays("");
        assertThat(none.isTradingDay(LocalDate.of(2026, 12, 25))).isTrue();
        assertThat(none.coversYear(2026)).isFalse();
    }

    @Test
    void marketOpenAlertSaysHowManyStrategiesAreRunning() {
        LocalDate d = LocalDate.of(2026, 11, 16);
        StrategyLiveConfig live = new StrategyLiveConfig(1L, 7L); live.set(TradingMode.LIVE, "MIS");
        StrategyLiveConfig paper = new StrategyLiveConfig(2L, 7L); paper.set(TradingMode.PAPER, "MIS");

        Alert two = TradeAlerts.marketOpenAlert(d, List.of(live, paper));
        assertThat(two.title()).isEqualTo("Market is open");
        assertThat(two.body()).isEqualTo("NSE is open for trading. 2 of your strategies are running (1 live, 1 paper); entries from 9:20.");
        assertThat(two.tag()).isEqualTo("market-open-2026-11-16");

        assertThat(TradeAlerts.marketOpenAlert(d, List.of()).body()).isEqualTo("NSE is open for trading. None of your strategies is switched on today.");
    }
}
