package com.tradeanalysis.marketdata.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kite API key/secret (from the environment) and the current access token. The token is kept in the database
 * (kite_session) so it survives restarts and is shared with strategy-service; KITE_ACCESS_TOKEN, if set, wins.
 */
@Component
public class KiteCredentials {

    private final String apiKey;
    private final String apiSecret;
    private final KiteSessionStore store;
    private volatile String accessToken;

    public KiteCredentials(
            @Value("${kite.api-key}") String apiKey,
            @Value("${kite.api-secret}") String apiSecret,
            @Value("${kite.access-token:}") String accessToken,
            KiteSessionStore store) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.store = store;
        this.accessToken = accessToken == null || accessToken.isBlank()
                ? store.load().filter(s -> apiKey.equals(s.apiKey())).map(KiteSessionStore.Session::accessToken).orElse("")
                : accessToken;
    }

    public String getApiKey()      { return apiKey; }
    public String getApiSecret()   { return apiSecret; }
    public String getAccessToken() { return accessToken; }
    public boolean hasToken()      { return accessToken != null && !accessToken.isBlank(); }

    public void updateAccessToken(String newToken) { updateAccessToken(newToken, null, null); }

    /** Uses the new token from now on and stores it for restarts and for strategy-service. */
    public void updateAccessToken(String newToken, String userId, String userName) {
        this.accessToken = newToken;
        store.save(apiKey, newToken, userId, userName);
    }

    public String authHeader() {
        return "token " + apiKey + ":" + accessToken;
    }
}
