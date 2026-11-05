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
import java.util.LinkedHashMap;
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

            credentials.updateAccessToken(accessToken, (String) data.get("user_id"), (String) data.get("user_name"));
            instruments.refresh();
            return "Connected to Kite as " + data.get("user_name") + " (" + data.get("user_id") + ")";
        } catch (Exception e) {
            return "Failed to generate token: " + e.getMessage();
        }
    }

    @GetMapping("/admin/kite/login-url")
    @Operation(summary = "Kite login page for this API key (redirects back with request_token)")
    public Map<String, String> kiteLoginUrl() {
        return Map.of("loginUrl", "https://kite.zerodha.com/connect/login?v=3&api_key=" + credentials.getApiKey());
    }

    @GetMapping("/admin/kite/status")
    @Operation(summary = "Whether the stored Kite session works (checks the Kite profile); never returns the token")
    public Map<String, Object> kiteStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apiKeyConfigured", credentials.getApiKey() != null && !credentials.getApiKey().isBlank());
        if (!credentials.hasToken()) {
            out.put("connected", false);
            out.put("message", "Not connected: log in to Kite");
            return out;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = RestClient.builder().baseUrl("https://api.kite.trade")
                    .defaultHeader("X-Kite-Version", "3").build()
                    .get().uri("/user/profile").header("Authorization", credentials.authHeader())
                    .retrieve().body(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> user = (Map<String, Object>) resp.get("data");
            out.put("connected", true);
            out.put("userId", user.get("user_id"));
            out.put("userName", user.get("user_name"));
            out.put("broker", user.get("broker"));
            out.put("exchanges", user.get("exchanges"));
            out.put("products", user.get("products"));
            out.put("message", "Connected");
        } catch (Exception e) {
            out.put("connected", false);
            out.put("message", "Kite session not valid (tokens expire daily): log in again");
        }
        return out;
    }

    private String sha256(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
