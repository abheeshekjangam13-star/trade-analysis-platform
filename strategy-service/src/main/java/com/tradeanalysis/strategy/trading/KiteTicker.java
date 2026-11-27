package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.common.market.Underlying;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Kite's live price stream (WebSocket, https://kite.trade/docs/connect/v3/websocket/) on the platform session: every
 * price change of the indices and options anyone is watching arrives as it happens, instead of {@link QuoteCache}
 * asking Kite once a second. {@link QuoteCache} serves from here while the stream is live and falls back to its
 * once-a-second calls when it isn't (not connected yet, dropped, no Kite login).
 *
 * <p>Symbols are watched by asking for them ({@link #watch}); one not asked for in {@link #WATCH_FOR} is unsubscribed.
 * Every {@code trading.ticker.maintain-ms} it connects in market hours (09:00 to 15:35 on trading days, or a special
 * session) when something is watched, reconnects after a drop or a new Kite login, and closes outside those hours.
 * Kite sends a heartbeat every second, so a stream silent for {@link #LIVE_WITHIN} is not treated as live.
 */
@Component
@ConditionalOnProperty(name = "trading.ticker.enabled", havingValue = "true", matchIfMissing = true)
public class KiteTicker {

    private static final Logger log = LoggerFactory.getLogger(KiteTicker.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Duration WATCH_FOR = Duration.ofSeconds(30);
    static final Duration LIVE_WITHIN = Duration.ofSeconds(3);
    static final Duration SILENT_RECONNECT = Duration.ofSeconds(10);
    /** Kite instrument tokens of the indices (fixed by Kite). */
    static final Map<String, Long> INDEX_TOKENS = Map.of("NSE:NIFTY 50", 256265L, "NSE:NIFTY BANK", 260105L);

    private final KiteSession session;
    private final NiftyOptions options;
    private final TradingDays days;
    private final Clock clock;
    private final String url;

    private final Map<String, Instant> watched = new ConcurrentHashMap<>();
    private final Map<String, Long> tokenOf = new ConcurrentHashMap<>();
    private final Map<Long, Double> prices = new ConcurrentHashMap<>();
    private final Set<Long> subscribed = ConcurrentHashMap.newKeySet();

    private volatile WebSocket socket;
    private volatile String socketToken;
    private volatile Instant lastMessage = Instant.EPOCH;

    public KiteTicker(KiteSession session, NiftyOptions options, TradingDays days, Clock clock,
                      @Value("${kite.ticker-url:wss://ws.kite.trade}") String url) {
        this.session = session; this.options = options; this.days = days; this.clock = clock; this.url = url;
    }

    /** Remembers that these symbols ("NSE:NIFTY 50", "NFO:NIFTY26D0126000CE") are wanted; they are subscribed shortly. */
    public void watch(Collection<String> symbols) {
        Instant now = clock.instant();
        symbols.forEach(s -> watched.put(s, now));
    }

    /** Whether prices are streaming now: connected and heard from Kite within {@link #LIVE_WITHIN}. */
    public boolean live() {
        return socket != null && !clock.instant().isAfter(lastMessage.plus(LIVE_WITHIN));
    }

    /** Streamed last prices of these symbols (only those that have one); empty while the stream isn't live. */
    public Map<String, Double> prices(Collection<String> symbols) {
        if (!live()) return Map.of();
        Map<String, Double> out = new LinkedHashMap<>();
        for (String s : symbols) {
            Long t = tokenOf.get(s);
            Double p = t == null || !subscribed.contains(t) ? null : prices.get(t);
            if (p != null && p > 0) out.put(s, p);
        }
        return out;
    }

    @Scheduled(fixedDelayString = "${trading.ticker.maintain-ms:2000}", initialDelayString = "${trading.ticker.initial-delay-ms:15000}")
    public void maintain() {
        try {
            doMaintain();
        } catch (RuntimeException e) {
            log.warn("TICKER_MAINTAIN_FAILED {}", e.getMessage());
        }
    }

    private void doMaintain() {
        Instant now = clock.instant();
        watched.entrySet().removeIf(e -> e.getValue().isBefore(now.minus(WATCH_FOR)));
        Optional<KiteSession.Credentials> creds = session.current();
        if (watched.isEmpty() || !inHours(now) || creds.isEmpty() || creds.get().accessToken() == null) {
            close("idle");
            return;
        }
        WebSocket ws = socket;
        if (ws != null && (!creds.get().accessToken().equals(socketToken) || now.isAfter(lastMessage.plus(SILENT_RECONNECT)))) {
            close(creds.get().accessToken().equals(socketToken) ? "silent" : "new Kite login");
            ws = null;
        }
        if (ws == null) ws = connect(creds.get());
        if (ws != null) resubscribe(ws);
    }

    /** 09:00 to 15:35 IST on a trading day, or a special session: a little either side of the market's own hours. */
    private boolean inHours(Instant now) {
        if (com.tradeanalysis.common.market.SpecialSessions.open(now)) return true;
        ZonedDateTime t = now.atZone(IST);
        LocalTime time = t.toLocalTime();
        return days.isTradingDay(t.toLocalDate()) && !time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(15, 35));
    }

    private WebSocket connect(KiteSession.Credentials c) {
        String uri = url + "?api_key=" + URLEncoder.encode(c.apiKey(), StandardCharsets.UTF_8)
                + "&access_token=" + URLEncoder.encode(c.accessToken(), StandardCharsets.UTF_8);
        try {
            prices.clear();
            subscribed.clear();
            WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(uri), new Listener()).get(15, TimeUnit.SECONDS);
            socket = ws;
            socketToken = c.accessToken();
            lastMessage = clock.instant();
            log.info("TICKER_CONNECTED");
            return ws;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("TICKER_CONNECT_FAILED {}", e.getMessage());
            return null;
        }
    }

    private void close(String why) {
        WebSocket ws = socket;
        socket = null;
        socketToken = null;
        subscribed.clear();
        prices.clear();
        if (ws != null) {
            log.info("TICKER_CLOSED {}", why);
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, why); } catch (RuntimeException ignored) { }
            ws.abort();
        }
    }

    /** Subscribes what is watched and not yet subscribed (in LTP mode), unsubscribes what nobody watches any more. */
    private void resubscribe(WebSocket ws) {
        Set<Long> wanted = new TreeSet<>();
        for (String s : watched.keySet()) {
            Long t = tokenOf.computeIfAbsent(s, this::lookup);
            if (t != null && t > 0) wanted.add(t);
        }
        tokenOf.values().removeIf(t -> t == null || t <= 0);
        List<Long> add = wanted.stream().filter(t -> !subscribed.contains(t)).toList();
        List<Long> drop = subscribed.stream().filter(t -> !wanted.contains(t)).sorted().toList();
        if (!drop.isEmpty()) {
            send(ws, "{\"a\":\"unsubscribe\",\"v\":" + drop + "}");
            drop.forEach(t -> { subscribed.remove(t); prices.remove(t); });
        }
        if (!add.isEmpty()) {
            send(ws, "{\"a\":\"subscribe\",\"v\":" + add + "}");
            send(ws, "{\"a\":\"mode\",\"v\":[\"ltp\"," + add + "]}");
            subscribed.addAll(add);
        }
    }

    private void send(WebSocket ws, String text) {
        ws.sendText(text, true).join();
    }

    /** The Kite instrument token of "NSE:<index>" or "NFO:<option>", or 0 when unknown (looked up again later). */
    private Long lookup(String symbol) {
        Long index = INDEX_TOKENS.get(symbol);
        if (index != null) return index;
        if (!symbol.startsWith("NFO:")) return 0L;
        String ts = symbol.substring(4);
        Underlying u = ts.startsWith(Underlying.BANKNIFTY.optionName) ? Underlying.BANKNIFTY : Underlying.NIFTY;
        try {
            return options.find(u, ts).map(KiteApi.OptionInstrument::instrumentToken).orElse(0L);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * Last prices in one binary message: a 2-byte packet count, then each packet as a 2-byte length and its bytes. Every
     * packet starts with the 4-byte instrument token and the 4-byte last price in paise (all modes). A 1-byte message is
     * Kite's heartbeat and has no packets.
     */
    static Map<Long, Double> parse(ByteBuffer b) {
        Map<Long, Double> out = new LinkedHashMap<>();
        if (b.remaining() < 2) return out;
        int count = Short.toUnsignedInt(b.getShort());
        for (int i = 0; i < count && b.remaining() >= 2; i++) {
            int len = Short.toUnsignedInt(b.getShort());
            if (len > b.remaining()) break;
            int start = b.position();
            if (len >= 8) out.put(Integer.toUnsignedLong(b.getInt(start)), b.getInt(start + 4) / 100.0);
            b.position(start + len);
        }
        return out;
    }

    /** Applies one message's prices (only for tokens still subscribed). */
    void apply(Map<Long, Double> ticks) {
        lastMessage = clock.instant();
        ticks.forEach((t, p) -> { if (subscribed.contains(t) && p > 0) prices.put(t, p); });
    }

    /** For tests: marks these symbols subscribed with these tokens, as if connected. */
    void connectedForTest(WebSocket ws, Map<String, Long> tokens) {
        socket = ws;
        tokenOf.putAll(tokens);
        subscribed.addAll(tokens.values());
        lastMessage = clock.instant();
    }

    private final class Listener implements WebSocket.Listener {
        private final ByteArrayOutputStream parts = new ByteArrayOutputStream();

        @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            parts.writeBytes(chunk);
            if (last) {
                try { apply(parse(ByteBuffer.wrap(parts.toByteArray()))); }
                catch (RuntimeException e) { log.warn("TICKER_PARSE_FAILED {}", e.getMessage()); }
                parts.reset();
            }
            ws.request(1);
            return null;
        }

        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            lastMessage = clock.instant();   // order updates and messages: not used here
            if (data.toString().contains("\"type\":\"error\"")) log.warn("TICKER_ERROR {}", data);
            ws.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            if (socket == ws) { socket = null; subscribed.clear(); prices.clear(); }
            log.info("TICKER_DISCONNECTED {} {}", status, reason);
            return null;
        }

        @Override public void onError(WebSocket ws, Throwable error) {
            if (socket == ws) { socket = null; subscribed.clear(); prices.clear(); }
            log.warn("TICKER_ERROR {}", error.toString());
        }
    }
}
