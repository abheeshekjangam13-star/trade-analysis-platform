package com.tradeanalysis.marketdata.client;

import com.tradeanalysis.common.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Downloads the Kite instruments CSV once on first lookup and caches symbol → instrument_token.
 * Uses NSE EQ + INDICES segment. Call refresh() to re-download (e.g. on new trading day).
 */
@Component
public class KiteInstrumentCache {

    private static final Logger log = LoggerFactory.getLogger(KiteInstrumentCache.class);

    private final RestClient http;
    private final KiteCredentials credentials;

    public KiteInstrumentCache(KiteCredentials credentials) {
        this.credentials = credentials;
        this.http = RestClient.builder()
                .baseUrl("https://api.kite.trade")
                .defaultHeader("X-Kite-Version", "3")
                .build();
    }

    // tradingsymbol (uppercase) → instrument_token
    private final Map<String, Long> cache = new ConcurrentHashMap<>();

    public long tokenFor(String symbol) {
        if (cache.isEmpty()) refresh();
        Long token = cache.get(symbol.toUpperCase());
        if (token == null) throw new NotFoundException("No instrument token found for symbol: " + symbol);
        return token;
    }

    public void refresh() {
        try {
            String csv = http.get()
                    .uri("/instruments/NSE")
                    .header("Authorization", credentials.authHeader())
                    .retrieve()
                    .body(String.class);
            if (csv == null || csv.isBlank()) return;

            Map<String, Long> fresh = new ConcurrentHashMap<>();
            try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
                String header = reader.readLine(); // skip header
                if (header == null) return;

                // Columns: instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,
                //          strike,tick_size,lot_size,instrument_type,segment,exchange
                // Note: segment for equities = "NSE", for indices = "NSE-INDICES"
                // Filter on exchange (col 11) = "NSE" to capture both EQ and INDICES
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] cols = line.split(",", -1);
                    if (cols.length < 12) continue;
                    String instrType = cols[9].trim();
                    String exchange  = cols[11].trim();
                    if (!"NSE".equals(exchange)) continue;
                    if (!"EQ".equals(instrType) && !"INDICES".equals(instrType)) continue;
                    try {
                        long token = Long.parseLong(cols[0].trim());
                        String sym = cols[2].trim().toUpperCase();
                        fresh.put(sym, token);
                    } catch (NumberFormatException ignored) {}
                }
            }
            cache.clear();
            cache.putAll(fresh);
            log.info("Instrument cache loaded: {} symbols", cache.size());
        } catch (Exception e) {
            log.error("Failed to refresh instrument cache: {}", e.getMessage(), e);
        }
    }
}
