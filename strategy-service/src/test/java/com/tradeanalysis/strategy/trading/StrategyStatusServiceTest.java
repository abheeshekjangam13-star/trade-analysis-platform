package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.indicator.Params;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyStatusServiceTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate YDAY = LocalDate.of(2026, 11, 11), TODAY = LocalDate.of(2026, 11, 12);

    static Candle bar(LocalDate d, int h, int m, double o, double hi, double lo, double c) {
        return new Candle(d.atTime(h, m).atZone(IST).toInstant(), BigDecimal.valueOf(o), BigDecimal.valueOf(hi),
                BigDecimal.valueOf(lo), BigDecimal.valueOf(c), 0);
    }

    static Params p(String... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new Params(m);
    }

    // Yesterday: high 26,000, low 25,800, close 25,900. Today: 09:15 candle 25,920-25,880, then 25,950.
    final List<Candle> bars = List.of(
            bar(YDAY, 9, 15, 25850, 26000, 25800, 25950), bar(YDAY, 15, 25, 25950, 25960, 25890, 25900),
            bar(TODAY, 9, 15, 25900, 25920, 25880, 25910), bar(TODAY, 9, 20, 25910, 25955, 25905, 25950));

    static LivePosition position() {
        return new LivePosition(131L, 7L, TODAY, TradingMode.LIVE, "CE", "NIFTY26N1725250CE", 130,
                TODAY.atTime(9, 50).atZone(IST).toInstant(), 25254.6, 25200.0);
    }

    @Test
    void theDoneNoteSaysWhenTheBrokerRefusedAnOrder() {
        // 16 Nov 2026: one strategy's BUY was rejected for margin, another's stop exit was rejected after it.
        LivePosition normal = position();
        normal.closed(null, 25200.0, "SL_HIT", -3985.0, "Sent to Kite");
        assertThat(StrategyStatusService.doneNote(normal)).isEqualTo("Stop hit · done for today");

        LivePosition entryRejected = position();
        entryRejected.failed("Entry was rejected by the broker: Insufficient funds");
        assertThat(StrategyStatusService.doneNote(entryRejected)).isEqualTo("Entry rejected by the broker · no trade today");

        LivePosition notFilled = position();
        notFilled.closed(null, 25200.0, "ENTRY_NOT_FILLED", 0.0, "Entry was not filled; cancelled");
        assertThat(StrategyStatusService.doneNote(notFilled)).isEqualTo("Entry not filled · no trade today");

        LivePosition exitRejected = position();
        exitRejected.closed(null, 25200.0, "SL_HIT", null, "Sent to Kite");
        exitRejected.note(LiveStrategyRunner.EXIT_REJECTED + ": Insufficient funds. Check the position at the broker.");
        assertThat(StrategyStatusService.doneNote(exitRejected)).isEqualTo("Exit rejected by the broker · check this position in Kite");
    }

    @Test
    void missingCandlesSayMarketClosedOutsideMarketHours() {
        TradingDays days = new TradingDays("2026-11-24");
        java.util.function.Function<LocalDateTime, String> at = t -> StrategyStatusService.dataProblem(t.atZone(IST).toInstant(), days);
        assertThat(at.apply(LocalDate.of(2026, 11, 27).atTime(8, 40))).isEqualTo("Market closed");                      // Friday, before 09:15
        assertThat(at.apply(LocalDate.of(2026, 11, 27).atTime(15, 45))).isEqualTo("Market closed");                     // after 15:30
        assertThat(at.apply(LocalDate.of(2026, 11, 28).atTime(11, 0))).isEqualTo("Market closed");                      // Saturday
        assertThat(at.apply(LocalDate.of(2026, 11, 24).atTime(11, 0))).isEqualTo("Market closed");                      // listed holiday
        assertThat(at.apply(LocalDate.of(2026, 11, 27).atTime(11, 0))).isEqualTo("Market data unavailable right now");  // trading hours
    }

    @Test
    void cachedStatusExpiresAtEachFiveMinuteCloseOrAfterAMinute() {
        Instant at = TODAY.atTime(10, 1, 10).atZone(IST).toInstant();
        assertThat(StrategyStatusService.fresh(at, at.plusSeconds(50))).isTrue();                          // 10:02:00: same candle
        assertThat(StrategyStatusService.fresh(at, at.plusSeconds(61))).isFalse();                         // a minute old
        Instant beforeClose = TODAY.atTime(10, 4, 40).atZone(IST).toInstant();
        assertThat(StrategyStatusService.fresh(beforeClose, beforeClose.plusSeconds(22))).isTrue();        // 10:05:02: within the grace
        assertThat(StrategyStatusService.fresh(beforeClose, beforeClose.plusSeconds(26))).isFalse();       // 10:05:06: the 10:00 candle closed
    }

    @Test
    void triggersCarryTheStopAndTargetTheTradeWouldStartWith() {
        // Previous day: stop on the other side of yesterday's range, pulled to 100 points by the cap; target 50 points.
        var t = StrategyStatusService.triggers(p("referenceMode", "PREVIOUS_DAY", "maxStopPoints", "100", "targetPoints", "50"), bars, TODAY, 25950.0);
        assertThat(t).containsExactly(new StrategyStatusService.Trigger("CE", 26000, 50, 25900.0, 26050.0),
                new StrategyStatusService.Trigger("PE", 25800, 150, 25900.0, 25750.0));
        // Opening range (09:15 candle 25,920-25,880): midpoint stop; 2R target.
        var o = StrategyStatusService.triggers(p("targetR", "2"), bars, TODAY, 25950.0);
        assertThat(o.get(0)).isEqualTo(new StrategyStatusService.Trigger("CE", 25920, -30, 25900.0, 25960.0));
        // Breakout-candle stop without a cap: not known until the candle; no stop, no R target.
        var b = StrategyStatusService.triggers(p("initialStop", "BREAKOUT_CANDLE", "targetR", "2"), bars, TODAY, 25950.0);
        assertThat(b.get(0).stop()).isNull();
        assertThat(b.get(0).target()).isNull();
        // CPR: stop at the pivot (26000 + 25800 + 25900) / 3 = 25,900.
        var c = StrategyStatusService.triggers(p("referenceMode", "CPR"), bars, TODAY, 25950.0);
        assertThat(c.get(0).stop()).isEqualTo(25900.0);
    }

    @Test
    void previousDayTriggersAreYesterdaysHighAndLow() {
        var t = StrategyStatusService.triggers(p("referenceMode", "PREVIOUS_DAY"), bars, TODAY, 25950.0);
        assertThat(t).containsExactly(new StrategyStatusService.Trigger("CE", 26000, 50, 25800.0, null), new StrategyStatusService.Trigger("PE", 25800, 150, 26000.0, null));   // stop: the other side
    }

    @Test
    void cprTriggersAreR1AndS1AndFollowTheDirections() {
        // P = (26000 + 25800 + 25900) / 3 = 25900; R1 = 2P − L = 26000; S1 = 2P − H = 25800
        var t = StrategyStatusService.triggers(p("referenceMode", "CPR", "directions", "CE"), bars, TODAY, 25950.0);
        assertThat(t).containsExactly(new StrategyStatusService.Trigger("CE", 26000, 50, 25900.0, null));   // stop: the pivot
    }

    @Test
    void openingRangeTriggersWaitForTheRangeToForm() {
        assertThat(StrategyStatusService.triggers(p(), bars, TODAY, 25950.0))
                .containsExactly(new StrategyStatusService.Trigger("CE", 25920, -30, 25900.0, null), new StrategyStatusService.Trigger("PE", 25880, 70, 25900.0, null));   // stop: the midpoint
        assertThat(StrategyStatusService.triggers(p("referenceMinutes", "15"), bars, TODAY, 25950.0)).isEmpty();   // 09:15-09:30 not complete
    }

    @Test
    void emaCrossAndGapFillHaveNoFixedLevel() {
        assertThat(StrategyStatusService.triggers(p("referenceMode", "EMA_CROSS"), bars, TODAY, 25950.0)).isEmpty();
        assertThat(StrategyStatusService.triggers(p("referenceMode", "GAP_FILL"), bars, TODAY, 25950.0)).isEmpty();
    }

    @Test
    void exitReasonsReadAsSentences() {
        assertThat(StrategyStatusService.readable("TARGET_HIT")).isEqualTo("Target hit");
        assertThat(StrategyStatusService.readable("STOPPED_BY_USER")).isEqualTo("Closed by you");
    }

    static Instant at(int h, int m) { return TODAY.atTime(h, m).atZone(IST).toInstant(); }

    @Test
    void aSignalBeforeTheStrategyWasSwitchedOnEndsItsDayInsteadOfWaiting() {
        Duration delay = Duration.ofMinutes(5);
        // #161 on 18 Nov: the 09:35 candle closed below yesterday's low at 09:40; switched on at 11:35; now 11:41.
        assertThat(StrategyStatusService.missedSignals(List.of(at(9, 40)), 1, delay, at(11, 41), at(11, 35)))
                .isEqualTo("Today’s signal came at 09:40, before this strategy was switched on at 11:35. "
                        + "It takes one trade a day, so it trades again on the next trading day.");
        // Switched on before the signal, but no order went out (kill switch, day-loss limit...).
        assertThat(StrategyStatusService.missedSignals(List.of(at(9, 40)), 1, delay, at(11, 41), at(9, 0)))
                .startsWith("Today’s signal came at 09:40, and no order went out for it then.");
    }

    @Test
    void aFreshSignalOrATradeStillToComeKeepsItWaiting() {
        Duration delay = Duration.ofMinutes(5);
        assertThat(StrategyStatusService.missedSignals(List.of(), 1, delay, at(11, 41), at(11, 35))).isNull();          // no signal yet
        assertThat(StrategyStatusService.missedSignals(List.of(at(11, 40)), 1, delay, at(11, 43), at(11, 35))).isNull(); // runner can still enter
        assertThat(StrategyStatusService.missedSignals(List.of(at(9, 40)), 2, delay, at(11, 41), at(11, 35))).isNull();  // a 2nd trade may come
        assertThat(StrategyStatusService.missedSignals(List.of(at(9, 40), at(10, 30)), 2, delay, at(11, 41), at(11, 35)))
                .startsWith("Today’s 2 signals came at 09:40, 10:30, before this strategy was switched on at 11:35. It takes up to 2 trades a day");
    }

    @Test
    void fixedStopModesTakeOneTradeADayTheOpeningRangeItsMaxTradesPerDay() {
        assertThat(StrategyStatusService.maxTradesPerDay(p("referenceMode", "PREVIOUS_DAY", "maxTradesPerDay", "3"))).isEqualTo(1);
        assertThat(StrategyStatusService.maxTradesPerDay(p("referenceMode", "CPR"))).isEqualTo(1);
        assertThat(StrategyStatusService.maxTradesPerDay(p())).isEqualTo(2);
        assertThat(StrategyStatusService.maxTradesPerDay(p("maxTradesPerDay", "1"))).isEqualTo(1);
    }
}
