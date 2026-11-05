package com.tradeanalysis.strategy.trading;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class QuoteCacheTest {

    final KiteApi kite = mock(KiteApi.class);
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-05T05:00:00Z"));
    final QuoteCache cache = new QuoteCache(kite, clock, 1000, 10);

    @Test
    void manyScreensPollingShareOneKiteCallPerSecond() {
        when(kite.ltp(anyList())).thenReturn(Map.of("NSE:NIFTY 50", 24423.7, "NFO:X", 149.1));
        assertThat(cache.get(List.of("NSE:NIFTY 50", "NFO:X")).prices()).containsEntry("NFO:X", 149.1);
        clock.set(clock.instant().plusMillis(300));
        cache.get(List.of("NFO:X"));                           // another screen, same second: from the cache
        cache.get(List.of("NSE:NIFTY 50"));
        verify(kite, times(1)).ltp(anyList());

        when(kite.ltp(anyList())).thenReturn(Map.of("NSE:NIFTY 50", 24430.0, "NFO:X", 151.0));
        clock.set(clock.instant().plusMillis(800));            // a second later: fresh prices for everything watched
        QuoteCache.Quotes q = cache.get(List.of("NFO:X"));
        assertThat(q.prices()).containsEntry("NFO:X", 151.0);
        assertThat(q.asOf()).isEqualTo(clock.instant());
        verify(kite, times(2)).ltp(List.of("NFO:X", "NSE:NIFTY 50"));   // one batch for all watched symbols
    }

    @Test
    void symbolsNobodyWatchesAnyMoreAreDropped() {
        when(kite.ltp(anyList())).thenReturn(Map.of("NFO:A", 1.0, "NFO:B", 2.0));
        cache.get(List.of("NFO:A"));
        clock.set(clock.instant().plusSeconds(11));
        cache.get(List.of("NFO:B"));
        verify(kite).ltp(List.of("NFO:B"));                    // A not asked for in 10 s: not fetched again
    }
}
