package com.tradeanalysis.strategy.trading;

import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class KiteTickerTest {

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T05:00:00Z"));   // 10:30 IST, a Friday
    final KiteTicker ticker = new KiteTicker(mock(KiteSession.class), mock(NiftyOptions.class), new TradingDays(""), clock, "wss://x");

    /** One binary message: a packet count, then each packet's length and bytes (token, last price in paise, padding). */
    static ByteBuffer message(long[][] packets, int[] lengths) {
        int size = 2;
        for (int l : lengths) size += 2 + l;
        ByteBuffer b = ByteBuffer.allocate(size);
        b.putShort((short) packets.length);
        for (int i = 0; i < packets.length; i++) {
            b.putShort((short) lengths[i]);
            b.putInt((int) packets[i][0]).putInt((int) packets[i][1]);
            for (int k = 8; k < lengths[i]; k++) b.put((byte) 0);
        }
        return b.flip();
    }

    @Test
    void readsTheLastPriceOfEveryPacketWhateverItsMode() {
        // An index in LTP mode (8 bytes) and an option in quote mode (44 bytes): the price is always the second int.
        Map<Long, Double> ticks = KiteTicker.parse(message(new long[][]{{256265, 2588530}, {12345678, 14905}}, new int[]{8, 44}));
        assertThat(ticks).containsExactly(Map.entry(256265L, 25885.30), Map.entry(12345678L, 149.05));
        assertThat(KiteTicker.parse(ByteBuffer.wrap(new byte[]{1}))).isEmpty();   // heartbeat
    }

    @Test
    void servesStreamedPricesOnlyWhileKiteIsHeardFrom() {
        ticker.connectedForTest(mock(WebSocket.class), Map.of("NSE:NIFTY 50", 256265L));
        ticker.apply(Map.of(256265L, 25885.3, 999L, 1.0));                         // 999 isn't subscribed: ignored
        assertThat(ticker.prices(List.of("NSE:NIFTY 50", "NFO:X"))).containsExactly(Map.entry("NSE:NIFTY 50", 25885.3));

        clock.set(clock.instant().plus(KiteTicker.LIVE_WITHIN).plusMillis(1));    // no heartbeat for 3 s: not live
        assertThat(ticker.live()).isFalse();
        assertThat(ticker.prices(List.of("NSE:NIFTY 50"))).isEmpty();
    }

    @Test
    void quoteCacheUsesTheStreamAndCallsKiteOnlyForWhatItLacks() {
        KiteApi kite = mock(KiteApi.class);
        QuoteCache cache = new QuoteCache(kite, clock, 1000, 10);
        cache.useStream(ticker);
        ticker.connectedForTest(mock(WebSocket.class), Map.of("NSE:NIFTY 50", 256265L));
        ticker.apply(Map.of(256265L, 25885.3));

        QuoteCache.Quotes q = cache.get(List.of("NSE:NIFTY 50"));
        assertThat(q.prices()).containsEntry("NSE:NIFTY 50", 25885.3);
        assertThat(q.streaming()).isTrue();
        assertThat(q.asOf()).isEqualTo(clock.instant());
        assertThat(cache.streaming()).isTrue();
        verifyNoInteractions(kite);

        when(kite.ltp(anyList())).thenReturn(Map.of("NSE:NIFTY 50", 25880.0, "NFO:X", 150.0));
        QuoteCache.Quotes mixed = cache.get(List.of("NSE:NIFTY 50", "NFO:X"));     // NFO:X not streaming yet: one Kite call
        assertThat(mixed.prices()).containsEntry("NFO:X", 150.0);
        assertThat(mixed.streaming()).isFalse();
        verify(kite, times(1)).ltp(anyList());
    }
}
