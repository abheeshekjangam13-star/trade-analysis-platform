package com.tradeanalysis.marketdata.service;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.marketdata.client.KiteConnectClient;
import com.tradeanalysis.marketdata.model.CandleEntity;
import com.tradeanalysis.marketdata.repository.CandleRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** "Latest" intraday candles are topped up from Kite to now, without calling Kite on every request. */
class MarketDataServiceTest {

    static final Instant T1024 = Instant.parse("2026-11-06T04:54:00Z");   // Fri 10:24 IST
    final CandleRepository repo = mock(CandleRepository.class);
    final KiteConnectClient kite = mock(KiteConnectClient.class);
    final List<CandleEntity> stored = new ArrayList<>(List.of(entity(T1024.minusSeconds(60)), entity(T1024)));

    static CandleEntity entity(Instant t) {
        BigDecimal p = BigDecimal.valueOf(24650);
        return new CandleEntity("NIFTY 50", "MINUTE_1", t, p, p, p, p, 0L);
    }

    static Candle candle(Instant t) {
        BigDecimal p = BigDecimal.valueOf(24660);
        return new Candle(t, p, p, p, p, 0L);
    }

    MarketDataService at(Instant now) {
        when(repo.findBySymbolAndIntervalCodeOrderByTimestampAsc("NIFTY 50", "MINUTE_1")).thenAnswer(i -> List.copyOf(stored));
        when(repo.findInRange(any(), any(), any(), any())).thenReturn(List.of());
        when(repo.saveAll(anyList())).thenAnswer(i -> { stored.addAll(i.getArgument(0)); return i.getArgument(0); });
        return new MarketDataService(repo, kite, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void latestIntradayCandlesAreFetchedFromTheLastStoredBarToNow() {
        Instant now = T1024.plusSeconds(200);   // 10:27:20
        MarketDataService svc = at(now);
        when(kite.fetchRange("NIFTY 50", Interval.MINUTE_1, T1024, now))
                .thenReturn(List.of(candle(T1024), candle(T1024.plusSeconds(60)), candle(T1024.plusSeconds(120))));

        var series = svc.getSeries("NIFTY 50", Interval.MINUTE_1, 400);

        assertThat(series.candles()).last().extracting(Candle::timestamp).isEqualTo(T1024.plusSeconds(120));
        verify(kite).fetchRange("NIFTY 50", Interval.MINUTE_1, T1024, now);
    }

    @Test
    void kiteIsCalledAtMostOnceEveryTenSecondsPerSymbolInMarketHours() {
        MarketDataService svc = at(T1024.plusSeconds(30));
        when(kite.fetchRange(any(), any(), any(), any())).thenReturn(List.of());
        svc.getSeries("NIFTY 50", Interval.MINUTE_1, 400);
        svc.getSeries("NIFTY 50", Interval.MINUTE_1, 400);
        verify(kite, times(1)).fetchRange(any(), any(), any(), any());
    }

    @Test
    void aKiteFailureStillReturnsTheStoredCandles() {
        MarketDataService svc = at(T1024.plusSeconds(90));
        when(kite.fetchRange(any(), any(), any(), any())).thenThrow(new RuntimeException("Kite session expired"));
        assertThat(svc.getSeries("NIFTY 50", Interval.MINUTE_1, 400).candles()).hasSize(2);
    }

    @Test
    void dailyCandlesAreNotToppedUpHere() {
        when(repo.findBySymbolAndIntervalCodeOrderByTimestampAsc("NIFTY 50", "DAY_1")).thenReturn(List.of(entity(T1024)));
        new MarketDataService(repo, kite, Clock.fixed(T1024, ZoneOffset.UTC)).getSeries("NIFTY 50", Interval.DAY_1, 10);
        verifyNoInteractions(kite);
    }

    /** A clock the test can move. */
    static final class MovingClock extends Clock {
        Instant now;
        MovingClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void aCandleStoredRightAtItsCloseIsFetchedAgainOnceItHasSettled() {
        // 10:25:00.4: a request stores the 10:24 bar (not final yet: Kite may still add the last ticks) and the 10:25 one.
        Instant close = T1024.plusSeconds(60), barAfter = close;
        MovingClock clock = new MovingClock(close.plusMillis(400));
        List<CandleEntity> inRange = new ArrayList<>(List.of(entity(T1024.minusSeconds(60)), entity(T1024), entity(barAfter)));
        when(repo.findInRange(any(), eq("MINUTE_1"), any(), any())).thenAnswer(i -> List.copyOf(inRange));
        when(kite.fetchRange(any(), any(), any(), any())).thenReturn(List.of());
        MarketDataService svc = new MarketDataService(repo, kite, clock);

        svc.getRange("NIFTY 50", Interval.MINUTE_1, T1024.minusSeconds(60), clock.now);
        verify(kite, never()).fetchRange(any(), any(), any(), any());          // 10:25 bar already stored, too soon to settle

        clock.now = close.plusSeconds(5);                                      // 10:25:05: the runner reads the candles
        svc.getRange("NIFTY 50", Interval.MINUTE_1, T1024.minusSeconds(60), clock.now);
        verify(kite).fetchRange("NIFTY 50", Interval.MINUTE_1, T1024, clock.now);   // the 10:24 bar again, now final

        clock.now = close.plusSeconds(20);                                     // later in the same minute: once is enough
        svc.getRange("NIFTY 50", Interval.MINUTE_1, T1024.minusSeconds(60), clock.now);
        verify(kite, times(1)).fetchRange(any(), any(), any(), any());
    }

    @Test
    void marketHours() {
        assertThat(MarketDataService.marketOpen(T1024)).isTrue();
        assertThat(MarketDataService.marketOpen(Instant.parse("2026-11-06T10:05:00Z"))).isFalse();   // 15:35 IST
        assertThat(MarketDataService.marketOpen(Instant.parse("2026-11-07T05:00:00Z"))).isFalse();   // Saturday
    }
}
