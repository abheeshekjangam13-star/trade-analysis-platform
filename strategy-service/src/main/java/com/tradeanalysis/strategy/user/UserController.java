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
    private final DeleteAllowance deletes;
    private final com.tradeanalysis.strategy.notify.BetaAppService beta;

    public UserController(UserService svc, com.tradeanalysis.strategy.auth.AuthService auth, DeleteAllowance deletes,
                          com.tradeanalysis.strategy.notify.BetaAppService beta) {
        this.svc = svc; this.auth = auth; this.deletes = deletes; this.beta = beta;
    }

    // ---- beta mobile app ------------------------------------------------------------------------------------------

    public record BetaNomination(List<Long> userIds, String note) {}
    public record BetaOverview(long totalUsers, java.util.Map<Long, com.tradeanalysis.strategy.notify.BetaAppService.Status> users) {}

    @PostMapping("/api/users/beta/nominate")
    @Operation(summary = "ADMIN: nominate users for the beta mobile app; each gets a congratulations email asking them to request the link")
    public com.tradeanalysis.strategy.notify.BetaAppService.NominateResult nominateBeta(@RequestBody BetaNomination body) {
        if (body == null || body.userIds() == null || body.userIds().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick at least one user");
        if (body.note() != null && body.note().length() > 1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keep the note under 1000 characters");
        return beta.nominate(body.userIds(), body.note(), CurrentUser.id().orElse(null));
    }

    public record BetaWithdrawal(List<Long> userIds) {}

    @PostMapping("/api/users/beta/withdraw")
    @Operation(summary = "ADMIN: withdraw beta app invitations; the users lose the app-link option on My account (no email is sent)")
    public com.tradeanalysis.strategy.notify.BetaAppService.WithdrawResult withdrawBeta(@RequestBody BetaWithdrawal body) {
        if (body == null || body.userIds() == null || body.userIds().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick at least one user");
        return beta.withdraw(body.userIds());
    }

    @GetMapping("/api/users/beta")
    @Operation(summary = "ADMIN: active user count and every nominated user's beta status (nominated, link requested, links sent)")
    public BetaOverview betaOverview() { return new BetaOverview(beta.totalUsers(), beta.statuses()); }

    @GetMapping("/api/users/me/beta")
    @Operation(summary = "The logged-in user's beta app status (whether nominated, when the link was requested)")
    public com.tradeanalysis.strategy.notify.BetaAppService.Mine myBeta() { return beta.mine(me().getId()); }

    @PostMapping("/api/users/me/beta/request-link")
    @Operation(summary = "Nominated users: email me the beta app link now (at most once every 5 minutes)")
    public com.tradeanalysis.strategy.notify.BetaAppService.Mine requestBetaLink() { return beta.requestLink(me().getId()); }

    /**
     * What the caller's plan allows and how much of it is used, for the UI's limits and upgrade hints. Limits of -1
     * mean "no limit" (admins: no limits at all). nextPlan is the plan to upgrade to (null on the highest plan).
     */
    public record Usage(Plan plan, Plan nextPlan, boolean admin, int strategiesOwned, int maxStrategies,
                        int deletesUsed, int maxDeletes, boolean editStrategies, int maxBacktestDays,
                        boolean paperTrading, boolean liveTrading, boolean paidPlanExpired) {}

    @GetMapping("/api/users/me/usage")
    @Operation(summary = "The logged-in user's plan limits and how much of them is used (strategies, deletes)")
    public Usage myUsage() {
        AppUser u = me();
        Plan p = u.getEffectivePlan();
        boolean admin = u.isAdmin();
        return new Usage(p, admin ? null : p.next(), admin, deletes.strategiesOwned(u.getId()),
                admin ? -1 : p.maxStrategies(), deletes.used(u.getId()), admin ? -1 : p.maxDeletes(),
                admin || p.editStrategies(), admin ? -1 : p.maxBacktestDays(), admin || p.paperTrading(),
                admin || p.liveTrading(), !admin && u.getPlan() != Plan.FREE && p == Plan.FREE);
    }

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

    @DeleteMapping("/api/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "ADMIN: delete a user (not yourself or another admin; users with strategies/trades: disable instead)")
    public void delete(@PathVariable Long id) { svc.delete(id); }

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
