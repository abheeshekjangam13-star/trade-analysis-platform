package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CandleMemoTest {

    static final Instant T0 = Instant.parse("2026-11-02T03:45:00Z");
    static final List<Candle> HOURLY = new ArrayList<>();
    static {
        for (int i = 0; i < 100; i++) {
            BigDecimal p = BigDecimal.valueOf(25_000 + i);
            HOURLY.add(new Candle(T0.plusSeconds(3600L * i), p, p, p, p, 0));
        }
    }
    static Instant h(int i) { return T0.plusSeconds(3600L * i); }

    MarketDataClient md;

    @BeforeEach
    void setUp() {
        md = mock(MarketDataClient.class);
        when(md.getRange(anyString(), any(), any(), any())).thenAnswer(inv -> {
            Instant from = inv.getArgument(2), to = inv.getArgument(3);
            List<Candle> in = HOURLY.stream().filter(c -> !c.timestamp().isBefore(from) && !c.timestamp().isAfter(to)).toList();
            if (in.isEmpty()) throw new IllegalStateException("No data in range");
            return new PriceSeries(inv.getArgument(0), inv.getArgument(1), in);
        });
    }

    @Test
    void withoutAMemoEveryReadGoesToMarketData() {
        CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_5, h(0), h(10));
        CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_5, h(0), h(10));
        verify(md, times(2)).getRange(anyString(), any(), any(), any());
    }

    @Test
    void aReadInsideAnEarlierOneIsCutFromIt() {
        CandleMemo memo = new CandleMemo(h(200));
        PriceSeries part = memo.with(() -> {
            CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_1, h(0), h(80));
            return CandleMemo.getRange(md, "nifty 50", Interval.MINUTE_1, h(10), h(20));
        });
        assertThat(part.candles()).hasSize(11);
        assertThat(part.candles().get(0).timestamp()).isEqualTo(h(10));
        assertThat(part.candles().get(10).timestamp()).isEqualTo(h(20));
        assertThat(memo.fetches()).isEqualTo(1);
        verify(md, times(1)).getRange(anyString(), any(), any(), any());
    }

    @Test
    void aReadReachingFurtherWidensTheStoredRangeOnce() {
        CandleMemo memo = new CandleMemo(h(200));
        memo.with(() -> {
            CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_1, h(40), h(50));
            CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_1, h(5), h(10));    // earlier: fetch h5..h50
            CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_1, h(20), h(30));   // inside: no fetch
            return null;
        });
        assertThat(memo.fetches()).isEqualTo(2);
        verify(md).getRange("NIFTY 50", Interval.MINUTE_1, h(5), h(50));
    }

    @Test
    void eachIntervalAndSymbolIsItsOwnRange() {
        CandleMemo memo = new CandleMemo(h(200));
        memo.with(() -> {
            CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_1, h(0), h(10));
            CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_5, h(0), h(10));
            CandleMemo.getRange(md, "NIFTY BANK", Interval.MINUTE_1, h(0), h(10));
            return null;
        });
        assertThat(memo.fetches()).isEqualTo(3);
    }

    @Test
    void aRangeFetchedUpToNowServesALaterNow() {
        CandleMemo memo = new CandleMemo(h(50));   // the report started at h50
        memo.with(() -> {
            CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_1, h(0), h(60));   // "now" when fetched
            CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_1, h(30), h(61));  // a moment later: same snapshot
            return null;
        });
        assertThat(memo.fetches()).isEqualTo(1);

        CandleMemo old = new CandleMemo(h(90));
        old.with(() -> {
            CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_1, h(0), h(60));   // ended before the report: a past range
            CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_1, h(30), h(70));
            return null;
        });
        assertThat(old.fetches()).isEqualTo(2);
    }

    @Test
    void aStretchWithoutCandlesFailsLikeMarketData() {
        CandleMemo memo = new CandleMemo(h(500));
        assertThatThrownBy(() -> memo.with(() -> {
            CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_1, h(0), h(99));
            return CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_1, h(100), h(120));   // after the last candle
        })).isInstanceOf(RuntimeException.class);
    }

    @Test
    void threadsSharingAMemoFetchOnce() throws Exception {
        CandleMemo memo = new CandleMemo(h(200));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> parts = new ArrayList<>();
            for (int i = 0; i < 8; i++)
                parts.add(pool.submit(() -> memo.with(() -> CandleMemo.getRange(md, "NIFTY 50", Interval.MINUTE_1, h(0), h(99)).candles().size())));
            for (Future<Integer> f : parts) assertThat(f.get()).isEqualTo(100);
        } finally { pool.shutdownNow(); }
        assertThat(memo.fetches()).isEqualTo(1);
    }
}
