package com.tradeanalysis.marketdata.service;

import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.marketdata.client.KiteConnectClient;
import com.tradeanalysis.marketdata.model.CandleEntity;
import com.tradeanalysis.marketdata.repository.CandleRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Backtest ranges: read once when nothing changed, and holes in the middle of the stored candles are filled. */
class MarketDataGapFillTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Instant SAT_EVENING = Instant.parse("2026-11-07T14:00:00Z");   // market closed
    final CandleRepository repo = mock(CandleRepository.class);
    final KiteConnectClient kite = mock(KiteConnectClient.class);

    static CandleEntity bar(String interval, LocalDateTime ist) {
        BigDecimal p = BigDecimal.valueOf(25000);
        return new CandleEntity("NIFTY 50", interval, ist.atZone(IST).toInstant(), p, p, p, p, 0L);
    }

    /** A 5-minute session from 09:15, `count` candles (75 = the whole day). */
    static List<CandleEntity> session(LocalDate d, int count) {
        List<CandleEntity> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(bar("MINUTE_5", d.atTime(9, 15).plusMinutes(5L * i)));
        return out;
    }

    static final LocalDate MON = LocalDate.parse("2026-11-02"), TUE = MON.plusDays(1), WED = MON.plusDays(2),
            THU = MON.plusDays(3), FRI = MON.plusDays(4);
    // Up to Friday's close: the last stored bar then needs no top-up, so any Kite call below is a gap fill.
    static final Instant FROM = MON.atStartOfDay(IST).toInstant(), TO = FRI.atTime(15, 30).atZone(IST).toInstant();

    MarketDataService with(List<CandleEntity> fiveMin, List<LocalDate> dailyCandles) {
        when(repo.findInRange(eq("NIFTY 50"), eq("MINUTE_5"), any(), any())).thenReturn(fiveMin);
        when(repo.findInRange(eq("NIFTY 50"), eq("DAY_1"), any(), any()))
                .thenReturn(dailyCandles.stream().map(d -> bar("DAY_1", d.atStartOfDay())).toList());
        when(kite.fetchRange(any(), any(), any(), any())).thenReturn(List.of());
        return new MarketDataService(repo, kite, Clock.fixed(SAT_EVENING, ZoneOffset.UTC));
    }

    static List<CandleEntity> days(List<CandleEntity>... sessions) {
        List<CandleEntity> all = new ArrayList<>();
        for (List<CandleEntity> s : sessions) all.addAll(s);
        return all;
    }

    @Test
    void aCompleteRangeIsReadOnceAndKiteIsNotCalled() {
        MarketDataService svc = with(days(session(MON, 75), session(TUE, 75), session(WED, 75), session(THU, 75), session(FRI, 75)), List.of());
        assertThat(svc.getRange("NIFTY 50", Interval.MINUTE_5, FROM, TO).candles()).hasSize(375);
        verify(repo, times(1)).findInRange("NIFTY 50", "MINUTE_5", FROM, TO);
        verify(kite, never()).fetchRange(any(), any(), any(), any());
    }

    @Test
    void aTradingDayMissingInTheMiddleIsFetched() {
        MarketDataService svc = with(days(session(MON, 75), session(WED, 75), session(THU, 75), session(FRI, 75)), List.of(MON, TUE, WED, THU, FRI));
        svc.getRange("NIFTY 50", Interval.MINUTE_5, FROM, TO);
        verify(kite).fetchRange("NIFTY 50", Interval.MINUTE_5, TUE.atTime(9, 15).atZone(IST).toInstant(), TUE.atTime(15, 30).atZone(IST).toInstant());
    }

    @Test
    void aHolidayIsNotAGap() {
        MarketDataService svc = with(days(session(MON, 75), session(WED, 75), session(THU, 75), session(FRI, 75)), List.of(MON, WED, THU, FRI));
        svc.getRange("NIFTY 50", Interval.MINUTE_5, FROM, TO);
        verify(kite, never()).fetchRange(any(), any(), any(), any());
    }

    @Test
    void aPartDayIsFetchedOnceOnly() {
        MarketDataService svc = with(days(session(MON, 75), session(TUE, 30), session(WED, 75), session(THU, 75), session(FRI, 75)), List.of());
        svc.getRange("NIFTY 50", Interval.MINUTE_5, FROM, TO);
        svc.getRange("NIFTY 50", Interval.MINUTE_5, FROM, TO);   // Kite couldn't complete it: not asked again
        verify(kite, times(1)).fetchRange("NIFTY 50", Interval.MINUTE_5, TUE.atTime(9, 15).atZone(IST).toInstant(), TUE.atTime(15, 30).atZone(IST).toInstant());
    }

    @Test
    void fullSessionSizes() {
        assertThat(MarketDataService.fullSessionCandles(Interval.MINUTE_1)).isEqualTo(375);
        assertThat(MarketDataService.fullSessionCandles(Interval.MINUTE_5)).isEqualTo(75);
        assertThat(MarketDataService.fullSessionCandles(Interval.HOUR_1)).isEqualTo(7);
    }
}
