package com.tradeanalysis.strategy.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Login and sign-up. /api/auth/status, /plans, /signup, /setup and /login are the only calls open without a token. */
@RestController
@Tag(name = "Auth", description = "Login, first-admin setup and passwords")
public class AuthController {

    public record LoginRequest(String username, String password) {}
    public record SetupRequest(String username, String password, String fullName, String email) {}
    public record ChangePassword(String currentPassword, String newPassword) {}
    public record SetPassword(String password) {}
    public record SignupRequest(String username, String password, String email, String phone, String fullName,
                                com.tradeanalysis.strategy.user.Plan plan) {}

    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @GetMapping("/api/auth/status")
    @Operation(summary = "Whether the first admin still has to be set up")
    public Map<String, Object> status() { return Map.of("setupRequired", auth.setupRequired()); }

    @PostMapping("/api/auth/setup")
    @Operation(summary = "One time: create the first admin (or give an existing user ADMIN and a password)")
    public AuthService.LoginResult setup(@RequestBody SetupRequest r) {
        return auth.setup(r.username(), r.password(), r.fullName(), r.email());
    }

    @GetMapping("/api/auth/plans")
    @Operation(summary = "Subscription plans with prices and what each allows")
    public java.util.List<com.tradeanalysis.strategy.user.Plan.Info> plans() { return com.tradeanalysis.strategy.user.Plan.catalog(); }

    @PostMapping("/api/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Sign up (email and phone required); the account waits for admin approval")
    public com.tradeanalysis.strategy.user.UserService.SignupResult signup(@RequestBody SignupRequest r) {
        return auth.signup(r.username(), r.password(), r.email(), r.phone(), r.fullName(), r.plan());
    }

    @PostMapping("/api/auth/login")
    @Operation(summary = "Log in; returns a token to send as 'Authorization: Bearer <token>'")
    public AuthService.LoginResult login(@RequestBody LoginRequest r) { return auth.login(r.username(), r.password()); }

    @PostMapping("/api/auth/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change your own password")
    public void changePassword(@RequestBody ChangePassword r) { auth.changePassword(r.currentPassword(), r.newPassword()); }

    @PostMapping("/api/users/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "ADMIN: set a user's password")
    public void setPassword(@PathVariable Long id, @RequestBody SetPassword r) { auth.setPassword(id, r.password()); }
}
