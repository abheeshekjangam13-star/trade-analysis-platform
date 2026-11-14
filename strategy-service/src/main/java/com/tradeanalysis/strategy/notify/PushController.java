package com.tradeanalysis.strategy.notify;

import com.tradeanalysis.strategy.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Phone alerts for the logged-in user: turn them on/off for this browser and send a test. Open to every plan. */
@RestController
@RequestMapping("/api/notifications/push")
@Tag(name = "Phone alerts", description = "Web Push alerts: orders, exits, market open, Kite login")
public class PushController {

    public record Subscription(String endpoint, Keys keys) {
        public record Keys(String p256dh, String auth) {}
    }
    public record Config(boolean enabled, String publicKey, int devices) {}

    private final PushService push;

    public PushController(PushService push) { this.push = push; }

    @GetMapping("/config")
    @Operation(summary = "Whether phone alerts are on, the key browsers subscribe with, and how many of your devices get them")
    public Config config() {
        return new Config(push.enabled(), push.publicKey(), push.devices(me()));
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Turn phone alerts on for this browser (the PushSubscription from the browser)")
    public void subscribe(@RequestBody Subscription s, @RequestHeader(value = "User-Agent", required = false) String ua) {
        if (s == null || s.keys() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing subscription");
        try {
            push.subscribe(me(), s.endpoint(), s.keys().p256dh(), s.keys().auth(), ua);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Turn phone alerts off for this browser")
    public void unsubscribe(@RequestParam String endpoint) { push.unsubscribe(me(), endpoint); }

    @PostMapping("/test")
    @Operation(summary = "Send a test alert to all your devices now; returns how many accepted it")
    public Map<String, Integer> test() {
        Long me = me();
        int n = push.send(me, new PushService.Alert("SysGrow alerts work",
                "You'll get alerts here for orders, exits, market open and Kite login.", "/account", "test", 300));
        return Map.of("devices", n);
    }

    private static Long me() {
        return CurrentUser.id().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Log in first"));
    }
}
