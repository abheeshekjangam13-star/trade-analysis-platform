package com.tradeanalysis.marketdata.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class KiteCredentials {

    private final String apiKey;
    private final String apiSecret;
    private volatile String accessToken;

    public KiteCredentials(
            @Value("${kite.api-key}") String apiKey,
            @Value("${kite.api-secret}") String apiSecret,
            @Value("${kite.access-token:}") String accessToken) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.accessToken = accessToken;
    }

    public String getApiKey()      { return apiKey; }
    public String getApiSecret()   { return apiSecret; }
    public String getAccessToken() { return accessToken; }

    public void updateAccessToken(String newToken) {
        this.accessToken = newToken;
    }

    public String authHeader() {
        return "token " + apiKey + ":" + accessToken;
    }
}
