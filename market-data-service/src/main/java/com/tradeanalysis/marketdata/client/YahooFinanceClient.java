package com.tradeanalysis.marketdata.client;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Thin wrapper around Yahoo Finance v8 chart API.
 *
 * To integrate a real broker API, implement the same interface and swap this bean.
 */
@Component
public class YahooFinanceClient {

    private final RestClient http;

    public YahooFinanceClient() {
        this.http = RestClient.builder()
                .baseUrl("https://query1.finance.yahoo.com")
                .build();
    }

    /**
     * Fetches up to {@code limit} bars for {@code symbol} at {@code interval}.
     * Returns an empty list if the upstream call fails.
     */
    @SuppressWarnings("unchecked")
    public List<Candle> fetch(String symbol, Interval interval, int limit) {
        try {
            String range = toRange(interval, limit);
            String ivStr = toYahooInterval(interval);
            Map<?, ?> body = http.get()
                    .uri("/v8/finance/chart/{sym}?interval={iv}&range={r}", symbol, ivStr, range)
                    .retrieve()
                    .body(Map.class);

            return parse(body);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Candle> parse(Map<?, ?> body) {
        try {
            Map<?, ?> chart    = (Map<?, ?>) body.get("chart");
            List<?>   result   = (List<?>)   ((Map<?, ?>) chart).get("result");
            Map<?, ?> r0       = (Map<?, ?>) result.get(0);
            List<?>   ts       = (List<?>)   r0.get("timestamp");
            Map<?, ?> quote    = (Map<?, ?>) ((List<?>) ((Map<?, ?>) r0.get("indicators")).get("quote")).get(0);
            List<?>   opens    = (List<?>) quote.get("open");
            List<?>   highs    = (List<?>) quote.get("high");
            List<?>   lows     = (List<?>) quote.get("low");
            List<?>   closes   = (List<?>) quote.get("close");
            List<?>   volumes  = (List<?>) quote.get("volume");

            List<Candle> candles = new ArrayList<>();
            for (int i = 0; i < ts.size(); i++) {
                candles.add(new Candle(
                        Instant.ofEpochSecond(((Number) ts.get(i)).longValue()),
                        bd(opens.get(i)), bd(highs.get(i)), bd(lows.get(i)), bd(closes.get(i)),
                        volumes.get(i) == null ? 0L : ((Number) volumes.get(i)).longValue()
                ));
            }
            return candles;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private BigDecimal bd(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    private String toYahooInterval(Interval iv) {
        return switch (iv) {
            case MINUTE_1  -> "1m";
            case MINUTE_5  -> "5m";
            case MINUTE_15 -> "15m";
            case HOUR_1    -> "1h";
            case DAY_1     -> "1d";
            case WEEK_1    -> "1wk";
        };
    }

    private String toRange(Interval iv, int limit) {
        long days = (long) (iv.duration().toMinutes() * limit / 1440.0) + 1;
        if (days <= 1)  return "1d";
        if (days <= 5)  return "5d";
        if (days <= 30) return "1mo";
        if (days <= 90) return "3mo";
        return "6mo";
    }
}
