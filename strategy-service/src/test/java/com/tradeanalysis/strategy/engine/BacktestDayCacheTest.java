package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Signal;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static com.tradeanalysis.strategy.engine.Candles.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Storing simulated backtest days, and splitting / joining simulation results by day. */
class BacktestDayCacheTest {

    static final LocalDate MON = LocalDate.of(2026, 11, 2), TUE = MON.plusDays(1), WED = MON.plusDays(2),
            THU = MON.plusDays(3), FRI = MON.plusDays(4), NEXT_MON = MON.plusDays(7);

    // ---- which days still need simulating ----

    static List<String> blocks(List<LocalDate> sessions, Set<LocalDate> stored, LocalDate today) {
        return blocks(sessions, stored, MON, today);
    }

    static List<String> blocks(List<LocalDate> sessions, Set<LocalDate> stored, LocalDate from, LocalDate today) {
        return BacktestEngine.missingBlocks(sessions, stored, from, today).stream().map(b -> b[0] + ".." + b[1]).toList();
    }

    @Test
    void daysBeforeTheFirstDailyCandleAreSimulatedNotDropped() {
        // Daily candles start on MON, the window three weeks earlier: those weeks must still be simulated.
        LocalDate from = MON.minusDays(21);
        assertThat(blocks(List.of(MON, TUE, WED), Set.of(MON, TUE, WED), from, THU))
                .containsExactly(from + ".." + MON.minusDays(1), THU + ".." + THU);
    }

    @Test
    void aWeekendOrHolidayAtTheStartOfTheWindowIsNotABlock() {
        // Window starts on the Thursday before; Thu/Fri holidays plus the weekend: four days, nothing missing.
        assertThat(blocks(List.of(MON, TUE, WED), Set.of(MON, TUE, WED), MON.minusDays(4), THU))
                .containsExactly(THU + ".." + THU);
    }

    @Test
    void everyPastDayStoredLeavesOnlyToday() {
        assertThat(blocks(List.of(MON, TUE, WED, THU, FRI), Set.of(MON, TUE, WED, THU, FRI), NEXT_MON))
                .containsExactly(FRI.plusDays(1) + ".." + NEXT_MON);
    }

    @Test
    void aGapInTheMiddleIsItsOwnBlockAndAGapAtTheEndRunsOnToToday() {
        assertThat(blocks(List.of(MON, TUE, WED, THU, FRI), Set.of(MON, WED, THU), NEXT_MON))
                .containsExactly(TUE + ".." + TUE, FRI + ".." + NEXT_MON);
    }

    @Test
    void nothingStoredIsOneBlockFromTheFirstSessionToToday() {
        assertThat(blocks(List.of(MON, TUE, WED), Set.of(), THU)).containsExactly(MON + ".." + THU);
    }

    @Test
    void onlyTheTodayBlockMayBeEmpty() {
        List<LocalDate> sessions = List.of(MON, TUE, WED, THU, FRI);
        assertThat(BacktestEngine.hasPastSession(sessions, TUE, NEXT_MON)).isTrue();
        assertThat(BacktestEngine.hasPastSession(sessions, FRI.plusDays(1), NEXT_MON)).isFalse();   // weekend + today only
    }

    // ---- splitting and joining results ----

    static NiftyTradeLog trade(LocalDate day, int number, int minuteOfDay) {
        Instant entry = day.atTime(9, 15).plusMinutes(minuteOfDay).atZone(IST).toInstant();
        NiftyTradeLog t = new NiftyTradeLog(7L, "NIFTY 50", day, number, "BULLISH", 25100, 25000,
                day.atTime(9, 15).atZone(IST).toInstant(), entry, 25110, "CE", 25100, 2, 65, 25050);
        t.setContext(0.35, 120.5);
        t.close(entry.plusSeconds(900), 25190, "TARGET_HIT", 25080);
        return t;
    }

    static Signal signal(LocalDate day, int h, int m, Signal.SignalType type) {
        return new Signal(day.atTime(h, m).atZone(IST).toInstant(), type, BigDecimal.valueOf(25110), "test");
    }

    static NiftyOpeningBreakoutEngine.DayReport report(LocalDate d, String status, int trades) {
        return new NiftyOpeningBreakoutEngine.DayReport(d, 0.1, status, "why", trades);
    }

    @Test
    void resultsAreSplitByDayAndJoinedBackInDayOrder() {
        var block1 = new NiftyOpeningBreakoutEngine.BreakoutResult(
                List.of(signal(WED, 9, 30, Signal.SignalType.BUY), signal(WED, 9, 45, Signal.SignalType.SELL)),
                List.of(trade(WED, 1, 15)), List.of(report(WED, "TRADED", 1)));
        var block2 = new NiftyOpeningBreakoutEngine.BreakoutResult(
                List.of(signal(MON, 10, 0, Signal.SignalType.BUY)),
                List.of(trade(MON, 1, 20), trade(MON, 2, 90)), List.of(report(MON, "TRADED", 2), report(TUE, "NO_TRADE", 0)));
        List<BacktestDayCache.Day> days = BacktestEngine.byDay(List.of(block1, block2));
        assertThat(days).extracting(BacktestDayCache.Day::date).containsExactly(MON, TUE, WED);
        assertThat(days.get(0).trades()).hasSize(2);

        // Reused Monday in front of freshly simulated Tuesday and Wednesday: one result in day order.
        var joined = BacktestEngine.assemble(List.of(days.get(0)), days.subList(1, 3));
        assertThat(joined.days()).extracting(NiftyOpeningBreakoutEngine.DayReport::date).containsExactly(MON, TUE, WED);
        assertThat(joined.tradeLogs()).extracting(t -> t.getTradeDate() + "#" + t.getTradeNumber()).containsExactly(MON + "#1", MON + "#2", WED + "#1");
        assertThat(joined.signals()).extracting(Signal::timestamp).isSorted();
    }

    // ---- storing ----

    @Test
    void onlyPastDaysTheEngineCouldJudgeWithClosedTradesAreStored() {
        LocalDate today = FRI;
        assertThat(BacktestDayCache.storable(new BacktestDayCache.Day(THU, report(THU, "TRADED", 1), List.of(trade(THU, 1, 10)), List.of()), today)).isTrue();
        assertThat(BacktestDayCache.storable(new BacktestDayCache.Day(THU, report(THU, "NO_DATA", 0), List.of(), List.of()), today)).isFalse();
        assertThat(BacktestDayCache.storable(new BacktestDayCache.Day(FRI, report(FRI, "TRADED", 1), List.of(trade(FRI, 1, 10)), List.of()), today)).isFalse();
        NiftyTradeLog open = new NiftyTradeLog(7L, "NIFTY 50", THU, 1, "BULLISH", 1, 1, Instant.EPOCH, Instant.EPOCH, 1, "CE", 1, 1, 65, 1);
        assertThat(BacktestDayCache.storable(new BacktestDayCache.Day(THU, report(THU, "TRADED", 1), List.of(open), List.of()), today)).isFalse();
        assertThat(BacktestDayCache.storable(new BacktestDayCache.Day(THU, null, List.of(), List.of()), today)).isFalse();
    }

    @Test
    void aStoredDayComesBackWithEveryTradeFieldAndNoId() {
        BacktestDayCacheRepository repo = mock(BacktestDayCacheRepository.class);
        List<BacktestDayCacheEntry> rows = new ArrayList<>();
        when(repo.saveAll(anyList())).thenAnswer(i -> { rows.addAll(i.getArgument(0)); return i.getArgument(0); });
        when(repo.findByCacheKeyAndTradeDateBetween(anyString(), any(), any())).thenAnswer(i -> List.copyOf(rows));
        BacktestDayCache cache = new BacktestDayCache(repo, Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC));

        NiftyTradeLog original = trade(THU, 1, 10);
        original.setBacktestResultId(99L);
        var day = new BacktestDayCache.Day(THU, report(THU, "TRADED", 1), List.of(original), List.of(signal(THU, 9, 25, Signal.SignalType.BUY)));
        String key = cache.key(7L, "nifty 50", Map.of("lots", "2", "lotSize", "65"));
        assertThat(cache.store(key, List.of(day), FRI)).isEqualTo(1);

        BacktestDayCache.Day back = cache.load(key, MON, FRI).get(THU);
        assertThat(back.report()).isEqualTo(day.report());
        assertThat(back.signals()).isEqualTo(day.signals());
        NiftyTradeLog t = back.trades().get(0);
        assertThat(t).isNotSameAs(original);
        assertThat(t.getId()).isNull();
        assertThat(t.getBacktestResultId()).isNull();   // each backtest saves its own rows
        assertThat(t).usingRecursiveComparison().ignoringFields("id", "backtestResultId", "createdAt").isEqualTo(original);
        assertThat(t.getCharges()).isEqualTo(120.5);
        assertThat(t.getEstimatedPnl()).isEqualTo(original.getEstimatedPnl());
        verify(repo).touch(eq(key), any());
    }

    @Test
    void theKeyChangesWithAnyParameterOrTheStrategyButNotTheOrderOrSymbolCase() {
        BacktestDayCache cache = new BacktestDayCache(mock(BacktestDayCacheRepository.class), Clock.systemUTC());
        Map<String, String> a = new LinkedHashMap<>(); a.put("lots", "2"); a.put("lotSize", "65");
        Map<String, String> b = new LinkedHashMap<>(); b.put("lotSize", "65"); b.put("lots", "2");
        String k = cache.key(7L, "NIFTY 50", a);
        assertThat(cache.key(7L, "nifty 50", b)).isEqualTo(k);
        assertThat(cache.key(7L, "NIFTY 50", Map.of("lots", "3", "lotSize", "65"))).isNotEqualTo(k);
        assertThat(cache.key(8L, "NIFTY 50", a)).isNotEqualTo(k);
        assertThat(k).hasSize(64);
    }
}
