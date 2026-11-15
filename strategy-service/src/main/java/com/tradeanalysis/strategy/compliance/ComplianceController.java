package com.tradeanalysis.strategy.compliance;

import com.tradeanalysis.strategy.user.AppUserRepository;
import com.tradeanalysis.strategy.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;

/**
 * Compliance: Algo IDs, compliance switches and the audit trail (ADMIN); the risk disclosure, two-factor sign-in and
 * the caller's client ID and PAN (every user); the grievance contact (public).
 */
@RestController
@Tag(name = "Compliance", description = "Algo IDs, risk disclosure, two-factor sign-in, client ID and PAN, audit trail")
public class ComplianceController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record AlgoIdRequest(String scope, String algoId) {}
    public record SettingsRequest(Boolean algoIdRequired, Boolean liveNeedsTwoFactor, Boolean liveNeedsPan,
                                  String grievanceName, String grievanceEmail, String grievancePhone) {}
    public record AcceptRequest(String version) {}
    public record CodeRequest(String code) {}
    public record PanRequest(String pan, String nameOnPan) {}
    public record VerifyRequest(Boolean verified) {}

    private final AlgoIds algoIds;
    private final ComplianceSettings settings;
    private final ComplianceAudit audit;
    private final RiskDisclosure disclosure;
    private final TwoFactor twoFactor;
    private final PanRegistry pans;
    private final AppUserRepository users;
    private final Clock clock;

    public ComplianceController(AlgoIds algoIds, ComplianceSettings settings, ComplianceAudit audit, RiskDisclosure disclosure,
                                TwoFactor twoFactor, PanRegistry pans, AppUserRepository users, Clock clock) {
        this.algoIds = algoIds; this.settings = settings; this.audit = audit; this.disclosure = disclosure;
        this.twoFactor = twoFactor; this.pans = pans; this.users = users; this.clock = clock;
    }

    private static Long me() { return CurrentUser.id().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Log in first")); }

    private static void requireAdmin() {
        if (!CurrentUser.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an ADMIN can do this");
    }

    /** Runs an action, turning bad input into 400 and a duplicate into 409. */
    private static <T> T run(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ---- Algo IDs and switches (ADMIN) ------------------------------------------------------------------------------

    @GetMapping("/api/trading/compliance/algo-ids")
    @Operation(summary = "Exchange Algo IDs per scope: DEFAULT, MANUAL and each strategy type (ADMIN)")
    public Map<String, Object> algoIds() {
        requireAdmin();
        return Map.of("algoIds", algoIds.all(), "required", settings.get().algoIdRequired());
    }

    @PutMapping("/api/trading/compliance/algo-ids")
    @Operation(summary = "Set (blank = remove) the Algo ID of one scope (ADMIN)")
    public Map<String, Object> setAlgoId(@RequestBody AlgoIdRequest r) {
        requireAdmin();
        run(() -> { algoIds.set(r.scope(), r.algoId(), me()); return null; });
        audit.record("ALGO_ID_SET", r.scope() + " = " + (r.algoId() == null || r.algoId().isBlank() ? "(removed)" : r.algoId().trim()));
        return algoIds();
    }

    @GetMapping("/api/trading/compliance/settings")
    @Operation(summary = "Compliance switches and the grievance contact (ADMIN)")
    public ComplianceSettings.Settings settings() { requireAdmin(); return settings.get(); }

    @PutMapping("/api/trading/compliance/settings")
    @Operation(summary = "Change compliance switches / grievance contact; fields left out stay as they are (ADMIN)")
    public ComplianceSettings.Settings updateSettings(@RequestBody SettingsRequest r) {
        requireAdmin();
        ComplianceSettings.Settings before = settings.get();
        ComplianceSettings.Settings after = run(() -> settings.update(r.algoIdRequired(), r.liveNeedsTwoFactor(), r.liveNeedsPan(),
                r.grievanceName(), r.grievanceEmail(), r.grievancePhone()));
        audit.record("COMPLIANCE_SETTINGS", "before " + before + " after " + after);
        return after;
    }

    @GetMapping("/api/trading/compliance/audit")
    @Operation(summary = "The compliance audit trail for a date range (IST, at most 400 days; ADMIN)")
    public List<ComplianceAudit.Entry> auditTrail(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireAdmin();
        LocalDate today = LocalDate.now(clock.withZone(IST));
        LocalDate end = to == null ? today : to, start = from == null ? end.minusDays(30) : from;
        if (start.isAfter(end) || start.plusDays(400).isBefore(end))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a range of at most 400 days, from before to");
        return audit.between(start.atStartOfDay(IST).toInstant(), end.plusDays(1).atStartOfDay(IST).toInstant());
    }

    // ---- grievance (public) -------------------------------------------------------------------------------------------

    @GetMapping("/api/auth/grievance")
    @Operation(summary = "The grievance officer's contact (open without logging in)")
    public ComplianceSettings.Grievance grievance() { return settings.grievance(); }

    // ---- risk disclosure ----------------------------------------------------------------------------------------------

    @GetMapping("/api/trading/risk-disclosure")
    @Operation(summary = "The live trading risk disclosure and whether you accepted this version")
    public RiskDisclosure.Status riskDisclosure() { return disclosure.status(me()); }

    @PostMapping("/api/trading/risk-disclosure/accept")
    @Operation(summary = "Accept the live trading risk disclosure (send the version you read)")
    public RiskDisclosure.Status acceptRisk(@RequestBody AcceptRequest r) {
        RiskDisclosure.Status s = run(() -> disclosure.accept(me(), r == null ? null : r.version()));
        audit.record("RISK_DISCLOSURE_ACCEPTED", "version " + s.version());
        return s;
    }

    // ---- two-factor sign-in -------------------------------------------------------------------------------------------

    @GetMapping("/api/auth/2fa")
    @Operation(summary = "Whether two-factor sign-in is on for you")
    public TwoFactor.Status twoFactor() { return twoFactor.status(me()); }

    @PostMapping("/api/auth/2fa/setup")
    @Operation(summary = "Start setting up an authenticator app: returns the secret and otpauth link (shown once)")
    public TwoFactor.Setup setupTwoFactor() {
        Long id = me();
        String label = users.findById(id).map(u -> u.getClientCode() != null ? u.getClientCode() : u.getUsername()).orElse("user");
        return run(() -> twoFactor.setup(id, label));
    }

    @PostMapping("/api/auth/2fa/enable")
    @Operation(summary = "Turn two-factor sign-in on with the first code from your app")
    public TwoFactor.Status enableTwoFactor(@RequestBody CodeRequest r) {
        TwoFactor.Status s = run(() -> twoFactor.enable(me(), r == null ? null : r.code()));
        audit.record("TWO_FACTOR_ON", null);
        return s;
    }

    @PostMapping("/api/auth/2fa/disable")
    @Operation(summary = "Turn two-factor sign-in off (needs a current code)")
    public TwoFactor.Status disableTwoFactor(@RequestBody CodeRequest r) {
        Long id = me();
        run(() -> { twoFactor.disable(id, r == null ? null : r.code()); return null; });
        audit.record("TWO_FACTOR_OFF", null);
        return twoFactor.status(id);
    }

    @DeleteMapping("/api/users/{id}/2fa")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a user's two-factor sign-in, e.g. after a lost phone (ADMIN)")
    public void resetTwoFactor(@PathVariable Long id) {
        requireAdmin();
        twoFactor.reset(id);
        audit.record("TWO_FACTOR_RESET", "user " + id);
    }

    // ---- client ID and PAN --------------------------------------------------------------------------------------------

    @GetMapping("/api/users/me/kyc")
    @Operation(summary = "Your client ID and PAN status (the PAN is shown masked)")
    public PanRegistry.Kyc myKyc() { return pans.kyc(me()); }

    @PutMapping("/api/users/me/kyc")
    @Operation(summary = "Add your PAN and your name as on the PAN card (once; changes go through an admin)")
    public PanRegistry.Kyc saveMyPan(@RequestBody PanRequest r) {
        PanRegistry.Kyc k = run(() -> pans.save(me(), r.pan(), r.nameOnPan(), false));
        audit.record("PAN_ADDED", "masked " + k.panMasked());
        return k;
    }

    @GetMapping("/api/users/kyc")
    @Operation(summary = "Every user's client ID and PAN status (ADMIN)")
    public List<PanRegistry.Row> allKyc() { requireAdmin(); return pans.all(); }

    @GetMapping("/api/users/{id}/kyc/pan")
    @Operation(summary = "Show a user's full PAN to check it against their PAN card (ADMIN; recorded in the audit trail)")
    public Map<String, String> revealPan(@PathVariable Long id) {
        requireAdmin();
        String pan = run(() -> pans.reveal(id));
        audit.record("PAN_VIEWED", "user " + id);
        return Map.of("pan", pan);
    }

    @PutMapping("/api/users/{id}/kyc")
    @Operation(summary = "Set or correct a user's PAN and name as on the PAN card (ADMIN)")
    public PanRegistry.Kyc savePan(@PathVariable Long id, @RequestBody PanRequest r) {
        requireAdmin();
        PanRegistry.Kyc k = run(() -> pans.save(id, r.pan(), r.nameOnPan(), true));
        audit.record("PAN_SET_BY_ADMIN", "user " + id + " masked " + k.panMasked());
        return k;
    }

    @PutMapping("/api/users/{id}/kyc/verified")
    @Operation(summary = "Mark a user's PAN verified or not (ADMIN)")
    public PanRegistry.Kyc verifyPan(@PathVariable Long id, @RequestBody VerifyRequest r) {
        requireAdmin();
        boolean v = r != null && Boolean.TRUE.equals(r.verified());
        PanRegistry.Kyc k = run(() -> pans.setVerified(id, v, me()));
        audit.record(v ? "PAN_VERIFIED" : "PAN_UNVERIFIED", "user " + id);
        return k;
    }
}
