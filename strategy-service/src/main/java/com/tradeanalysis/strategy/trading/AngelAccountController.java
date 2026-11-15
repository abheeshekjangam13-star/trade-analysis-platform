package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.strategy.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** The caller's own Angel One SmartAPI account (GOLD/PREMIUM), instead of Kite. Tokens are never returned. */
@RestController
@RequestMapping("/api/trading/angel-account")
@Tag(name = "Trading", description = "Your own Angel One account")
public class AngelAccountController {

    public record AppRequest(String apiKey) {}
    public record SessionRequest(String authToken, String refreshToken, String feedToken) {}

    private final AngelAccounts accounts;
    private final BrokerAccounts brokers;

    public AngelAccountController(AngelAccounts accounts, BrokerAccounts brokers) { this.accounts = accounts; this.brokers = brokers; }

    private static Long me() { return CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first")); }

    @GetMapping
    @Operation(summary = "Whether your own Angel One account is saved and logged in today")
    public AngelAccounts.Status status() { return accounts.status(me()); }

    @PutMapping("/app")
    @Operation(summary = "Save the API key of your Angel One SmartAPI app (refused while a Kite account is connected)")
    public AngelAccounts.Status saveApp(@RequestBody AppRequest r) {
        brokers.requireNoOtherBroker(me(), BrokerAccounts.Broker.ANGEL_ONE);
        return accounts.saveApp(me(), r.apiKey());
    }

    @GetMapping("/login-url")
    @Operation(summary = "Angel One publisher login for your app (Angel redirects back to /angel/callback with state=me)")
    public Map<String, String> loginUrl() { return Map.of("loginUrl", accounts.loginUrl(me())); }

    @PostMapping("/session")
    @Operation(summary = "Store today's session from the Angel One login redirect (auth_token, refresh_token, feed_token)")
    public AngelAccounts.Status session(@RequestBody SessionRequest r) {
        return accounts.connect(me(), r.authToken(), r.refreshToken(), r.feedToken());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Forget your Angel One app and login")
    public void disconnect() { accounts.disconnect(me()); }
}
