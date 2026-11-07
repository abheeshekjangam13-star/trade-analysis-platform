package com.tradeanalysis.marketdata.client;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class KiteConnectClient {

    private static final Logger log = LoggerFactory.getLogger(KiteConnectClient.class);

    private static final DateTimeFormatter KITE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));

    private final RestClient http;
    private final KiteCredentials credentials;
    private final KiteInstrumentCache instruments;

    public KiteConnectClient(KiteCredentials credentials, KiteInstrumentCache instruments) {
        this.credentials = credentials;
        this.instruments = instruments;
        this.http = RestClient.builder()
                .baseUrl("https://api.kite.trade")
                .defaultHeader("X-Kite-Version", "3")
                .build();
    }

    public List<Candle> fetch(String symbol, Interval interval, int limit) {
        Instant to = Instant.now();
        return fetchRange(symbol, interval, to.minus(interval.duration().multipliedBy(limit)), to);
    }

    /**
     * Fetches [from, to] in chunks that respect Kite's per-request day limits.
     * {@code from} is floored to the interval boundary: Kite builds intraday candles starting
     * at the given {@code from}, so an unaligned value yields bars like 09:15:58 instead of 09:15:00.
     */
    public List<Candle> fetchRange(String symbol, Interval interval, Instant from, Instant to) {
        try {
            long token = instruments.tokenFor(symbol);
            String ivStr = toKiteInterval(interval);
            Duration maxSpan = Duration.ofDays(maxDaysPerRequest(interval));
            Instant cursor = alignDown(from, interval);

            List<Candle> all = new ArrayList<>();
            while (cursor.isBefore(to)) {
                Instant chunkEnd = cursor.plus(maxSpan).isBefore(to) ? cursor.plus(maxSpan) : to;
                String fromStr = KITE_FMT.format(cursor);
                String toStr   = KITE_FMT.format(chunkEnd);

                log.info("Kite fetch: symbol={} token={} interval={} from='{}' to='{}'",
                        symbol, token, ivStr, fromStr, toStr);

                Map<?, ?> body = http.get()
                        .uri("/instruments/historical/{token}/{iv}?from={f}&to={t}",
                                token, ivStr, fromStr, toStr)
                        .header("Authorization", credentials.authHeader())
                        .retrieve()
                        .body(Map.class);
                List<Candle> chunk = parse(body);
                if (chunk.isEmpty()) log.warn("Kite returned no candles for {} {} {}..{}: {}", symbol, ivStr, fromStr, toStr, summary(body));
                all.addAll(chunk);
                // Stay on an interval boundary; the overlapping boundary bar is de-duplicated on persist.
                cursor = chunkEnd;
            }
            log.info("Kite fetch: got {} candles for {}", all.size(), symbol);
            return all;
        } catch (Exception e) {
            log.error("Kite fetch failed for symbol={}: {}", symbol, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    static Instant alignDown(Instant ts, Interval interval) {
        if (interval == Interval.DAY_1 || interval == Interval.WEEK_1) return ts;
        long step = interval.duration().getSeconds();
        return Instant.ofEpochSecond(ts.getEpochSecond() - Math.floorMod(ts.getEpochSecond(), step));
    }

    // Kite historical API: max days per request for each interval.
    private int maxDaysPerRequest(Interval iv) {
        return switch (iv) {
            case MINUTE_1  -> 60;
            case MINUTE_5  -> 100;
            case MINUTE_15 -> 200;
            case HOUR_1    -> 400;
            case DAY_1, WEEK_1 -> 2000;
        };
    }

    @SuppressWarnings("unchecked")
    private List<Candle> parse(Map<?, ?> body) {
        try {
            Map<?, ?> data   = (Map<?, ?>) body.get("data");
            List<?>   candles = (List<?>) data.get("candles");
            List<Candle> result = new ArrayList<>();
            for (Object row : candles) {
                List<?> r = (List<?>) row;
                // [timestamp, open, high, low, close, volume]
                String tsRaw = r.get(0).toString();
                Instant ts = Instant.parse(
                        tsRaw.contains("+") ? tsRaw.replace(" ", "T") : tsRaw.replace(" ", "T") + "+05:30");
                result.add(new Candle(ts,
                        bd(r.get(1)), bd(r.get(2)), bd(r.get(3)), bd(r.get(4)),
                        r.get(5) == null ? 0L : ((Number) r.get(5)).longValue()));
            }
            return result;
        } catch (Exception e) {
            log.warn("Kite historical response not understood: {}", e.toString());
            return Collections.emptyList();
        }
    }

    /** What Kite said, for the log: status, error type and message, and how many candles (never the whole body). */
    private static String summary(Map<?, ?> body) {
        if (body == null) return "empty response";
        Object data = body.get("data");
        Object candles = data instanceof Map<?, ?> d ? d.get("candles") : null;
        return "status=" + body.get("status") + " error_type=" + body.get("error_type") + " message=" + body.get("message")
                + " candles=" + (candles instanceof List<?> l ? l.size() : "none");
    }

    private BigDecimal bd(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    private String toKiteInterval(Interval iv) {
        return switch (iv) {
            case MINUTE_1  -> "minute";
            case MINUTE_5  -> "5minute";
            case MINUTE_15 -> "15minute";
            case HOUR_1    -> "60minute";
            case DAY_1     -> "day";
            case WEEK_1    -> "week";
        };
    }
}
