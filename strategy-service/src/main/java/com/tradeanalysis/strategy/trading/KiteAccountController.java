package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.strategy.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** The caller's own Kite Connect account (GOLD/PREMIUM). The API secret and access token are never returned. */
@RestController
@RequestMapping("/api/trading/kite-account")
@Tag(name = "Trading", description = "Your own Kite account")
public class KiteAccountController {

    public record AppRequest(String apiKey, String apiSecret) {}
    public record SessionRequest(String requestToken) {}

    private final KiteAccounts accounts;

    public KiteAccountController(KiteAccounts accounts) { this.accounts = accounts; }

    private static Long me() { return CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first")); }

    @GetMapping
    @Operation(summary = "Whether your own Kite account is saved and logged in today")
    public KiteAccounts.Status status() { return accounts.status(me()); }

    @PutMapping("/app")
    @Operation(summary = "Save the API key and secret of your Kite Connect app (blank secret = keep the saved one)")
    public KiteAccounts.Status saveApp(@RequestBody AppRequest r) { return accounts.saveApp(me(), r.apiKey(), r.apiSecret()); }

    @GetMapping("/login-url")
    @Operation(summary = "Kite login page for your app (Kite redirects back to /kite/callback with account=me)")
    public Map<String, String> loginUrl() { return Map.of("loginUrl", accounts.loginUrl(me())); }

    @PostMapping("/session")
    @Operation(summary = "Exchange the request_token from your Kite login for today's session")
    public KiteAccounts.Status session(@RequestBody SessionRequest r) { return accounts.connect(me(), r.requestToken()); }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Forget your Kite app and login")
    public void disconnect() { accounts.disconnect(me()); }
}
