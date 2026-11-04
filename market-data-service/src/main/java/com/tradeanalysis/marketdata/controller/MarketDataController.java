package com.tradeanalysis.marketdata.controller;

import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.marketdata.client.KiteCredentials;
import com.tradeanalysis.marketdata.client.KiteInstrumentCache;
import com.tradeanalysis.marketdata.service.MarketDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market-data")
@Tag(name = "Market Data", description = "Fetch OHLCV price series for any symbol")
public class MarketDataController {

    private final MarketDataService svc;
    private final KiteInstrumentCache instruments;
    private final KiteCredentials credentials;

    public MarketDataController(MarketDataService svc, KiteInstrumentCache instruments, KiteCredentials credentials) {
        this.svc = svc;
        this.instruments = instruments;
        this.credentials = credentials;
    }

    @GetMapping("/{symbol}")
    @Operation(summary = "Get latest candles for a symbol")
    public PriceSeries getSeries(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "DAY_1") Interval interval,
            @RequestParam(defaultValue = "200") int limit) {
        return svc.getSeries(symbol, interval, limit);
    }

    @GetMapping("/{symbol}/range")
    @Operation(summary = "Get candles in a time range")
    public PriceSeries getRange(
            @PathVariable String symbol,
            @RequestParam Interval interval,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return svc.getRange(symbol, interval, from, to);
    }

    @GetMapping("/symbols")
    @Operation(summary = "List all tracked symbols")
    public List<String> listSymbols() {
        return svc.listSymbols();
    }

    @PostMapping("/admin/refresh-instruments")
    @Operation(summary = "Re-download the Kite instruments list (run once per trading day)")
    public String refreshInstruments() {
        instruments.refresh();
        return "Instrument cache refreshed";
    }

    @PostMapping("/admin/update-token")
    @Operation(summary = "Update the Kite access token at runtime (no restart needed)")
    public String updateToken(@RequestParam String token) {
        credentials.updateAccessToken(token);
        instruments.refresh();
        return "Access token updated and instrument cache refreshed";
    }

    @PostMapping("/admin/generate-token")
    @Operation(summary = "Exchange a Kite request_token for an access_token using stored api_key + api_secret")
    public String generateToken(@RequestParam String requestToken) {
        try {
            String checksum = sha256(credentials.getApiKey() + requestToken + credentials.getApiSecret());

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("api_key", credentials.getApiKey());
            form.add("request_token", requestToken);
            form.add("checksum", checksum);

            RestClient kite = RestClient.builder()
                    .baseUrl("https://api.kite.trade")
                    .defaultHeader("X-Kite-Version", "3")
                    .build();

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = kite.post()
                    .uri("/session/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            String accessToken = (String) data.get("access_token");

            credentials.updateAccessToken(accessToken);
            instruments.refresh();
            return "Access token generated and stored: " + accessToken;
        } catch (Exception e) {
            return "Failed to generate token: " + e.getMessage();
        }
    }

    private String sha256(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
