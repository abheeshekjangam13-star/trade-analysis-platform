package com.tradeanalysis.strategy.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@Tag(name = "Users", description = "Users, roles and the API access log")
public class UserController {

    public record UserRequest(String username, String email, String fullName, List<String> roles, AppUser.Status status,
                              String password, String phone) {}
    public record Approval(Plan plan, java.time.Instant expiresAt, String note) {}
    public record Rejection(String note) {}
    public record PlanChange(Plan plan, java.time.Instant expiresAt) {}

    private final UserService svc;

    private final com.tradeanalysis.strategy.auth.AuthService auth;

    public UserController(UserService svc, com.tradeanalysis.strategy.auth.AuthService auth) { this.svc = svc; this.auth = auth; }

    @PostMapping("/api/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "ADMIN: create a user with a password (roles default to TRADER)")
    public AppUser create(@RequestBody UserRequest body) {
        AppUser u = svc.create(body.username(), body.email(), body.fullName(), body.roles());
        if (body.password() != null && !body.password().isBlank()) auth.setPassword(u.getId(), body.password());
        return svc.get(u.getId());
    }

    @GetMapping("/api/users/pending")
    @Operation(summary = "ADMIN: sign-ups waiting for approval, oldest first")
    public List<AppUser> pending() { return svc.pending(); }

    @PostMapping("/api/users/{id}/approve")
    @Operation(summary = "ADMIN: approve a sign-up with a plan (default: the plan they asked for) and optional expiry")
    public AppUser approve(@PathVariable Long id, @RequestBody(required = false) Approval a) {
        return a == null ? svc.approve(id, null, null, null) : svc.approve(id, a.plan(), a.expiresAt(), a.note());
    }

    @PostMapping("/api/users/{id}/reject")
    @Operation(summary = "ADMIN: reject a pending sign-up")
    public AppUser reject(@PathVariable Long id, @RequestBody(required = false) Rejection r) {
        return svc.reject(id, r == null ? null : r.note());
    }

    @PutMapping("/api/users/{id}/plan")
    @Operation(summary = "ADMIN: change a user's plan and expiry (renewal, upgrade, downgrade)")
    public AppUser changePlan(@PathVariable Long id, @RequestBody PlanChange p) { return svc.changePlan(id, p.plan(), p.expiresAt()); }

    @GetMapping("/api/users")
    @Operation(summary = "ADMIN: list users")
    public List<AppUser> list() { return svc.list(); }

    @GetMapping("/api/users/me")
    @Operation(summary = "The logged-in user")
    public AppUser me() {
        return svc.get(CurrentUser.id().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Log in first")));
    }

    @GetMapping("/api/users/{id}")
    @Operation(summary = "Get a user")
    public AppUser get(@PathVariable Long id) { return svc.get(id); }

    @PatchMapping("/api/users/{id}")
    @Operation(summary = "Update email, name, roles or status (ACTIVE / DISABLED)")
    public AppUser update(@PathVariable Long id, @RequestBody UserRequest body) {
        return svc.update(id, body.email(), body.fullName(), body.roles(), body.status(), body.phone());
    }

    @GetMapping("/api/users/roles")
    @Operation(summary = "List roles")
    public List<AppRole> roles() { return svc.roles(); }

    @GetMapping("/api/access-log")
    @Operation(summary = "ADMIN: recent API requests recorded by the gateway, newest first")
    public List<AccessLog> accessLog(@RequestParam(required = false) String username,
                                     @RequestParam(defaultValue = "100") int limit) {
        return svc.accessLog(username, limit);
    }
}
