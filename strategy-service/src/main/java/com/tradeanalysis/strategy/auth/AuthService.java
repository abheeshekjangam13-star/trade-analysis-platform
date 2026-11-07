package com.tradeanalysis.strategy.auth;

import com.tradeanalysis.strategy.user.AppRole;
import com.tradeanalysis.strategy.user.AppRoleRepository;
import com.tradeanalysis.strategy.user.AppUser;
import com.tradeanalysis.strategy.user.AppUserRepository;
import com.tradeanalysis.strategy.user.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public record LoginResult(String token, Instant expiresAt, AppUser user) {}

    private final AppUserRepository users;
    private final AppRoleRepository roles;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final LoginAttempts attempts;
    private final String dummyHash;
    private final com.tradeanalysis.strategy.notify.EmailService emails;
    private final com.tradeanalysis.strategy.user.UserService userService;   // checked for unknown users so timing doesn't reveal which usernames exist

    public AuthService(AppUserRepository users, AppRoleRepository roles, PasswordEncoder encoder, TokenService tokens,
                       LoginAttempts attempts, com.tradeanalysis.strategy.user.UserService userService,
                       com.tradeanalysis.strategy.notify.EmailService emails) {
        this.userService = userService;
        this.emails = emails;
        this.users = users; this.roles = roles; this.encoder = encoder; this.tokens = tokens; this.attempts = attempts;
        this.dummyHash = encoder.encode("not-a-real-password-1");
    }

    /** Setup is needed until some ACTIVE admin has a password. */
    @Transactional(readOnly = true)
    public boolean setupRequired() {
        return users.findAll().stream().noneMatch(u -> u.isAdmin() && u.isPasswordSet() && u.getStatus() == AppUser.Status.ACTIVE);
    }

    /** One-time: makes the first admin (an existing user keeps its history and gains ADMIN). */
    @Transactional
    public LoginResult setup(String username, String password, String fullName, String email) {
        if (!setupRequired()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Setup is already done: log in instead");
        checkPassword(password);
        String name = clean(username);
        AppUser user = users.findByUsernameIgnoreCase(name).orElseGet(() -> users.save(new AppUser(name, blankToNull(email), blankToNull(fullName))));
        Set<AppRole> r = new LinkedHashSet<>(user.getRoles());
        r.add(role("ADMIN"));
        r.add(role("TRADER"));
        user.setRoles(r);
        if (fullName != null && !fullName.isBlank()) user.setFullName(fullName.trim());
        if (email != null && !email.isBlank()) user.setEmail(email.trim());
        user.setStatus(AppUser.Status.ACTIVE);
        user.setPlan(com.tradeanalysis.strategy.user.Plan.PREMIUM, null);
        user.setPasswordHash(encoder.encode(password));
        log.info("AUTH_SETUP first admin {}", user.getUsername());
        return issue(user);
    }

    @Transactional
    public LoginResult login(String username, String password) {
        String name = username == null ? "" : username.trim();
        long locked = attempts.lockedFor(name);
        if (locked > 0) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Too many failed logins: try again in " + Math.max(1, (locked + 59) / 60) + " minute(s)");
        AppUser user = users.findByUsernameIgnoreCase(name).orElse(null);
        // Same message and a hash check either way, so it never reveals which usernames exist.
        boolean ok = user != null && user.isPasswordSet() && password != null && encoder.matches(password, user.getPasswordHash());
        if (!ok) {
            if (user == null || !user.isPasswordSet()) encoder.matches(String.valueOf(password), dummyHash);
            attempts.failed(name);
            log.warn("AUTH_LOGIN_FAILED user={}", name);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong username or password");
        }
        if (user.getStatus() != AppUser.Status.ACTIVE)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, com.tradeanalysis.strategy.user.UserService.statusMessage(user.getStatus()));
        attempts.succeeded(name);
        return issue(user);
    }

    /** Self sign-up (see UserService.signup): checks and hashes the password; no token until an admin approves. */
    public com.tradeanalysis.strategy.user.UserService.SignupResult signup(String username, String password, String email,
            String phone, String fullName, com.tradeanalysis.strategy.user.Plan plan) {
        checkPassword(password);
        var r = userService.signup(username, email, phone, fullName, plan, encoder.encode(password));
        log.info("AUTH_SIGNUP user={} plan={}", r.username(), r.requestedPlan());
        return r;
    }

    @Transactional
    public void changePassword(String current, String next) {
        AppUser user = currentUser();
        if (!user.isPasswordSet() || current == null || !encoder.matches(current, user.getPasswordHash()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is wrong");
        checkPassword(next);
        user.setPasswordHash(encoder.encode(next));
        emails.passwordChanged(user, false);
    }

    /** ADMIN: set someone's password (new users, forgotten passwords). */
    @Transactional
    public void setPassword(Long userId, String password) {
        requireAdmin();
        checkPassword(password);
        AppUser user = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));
        boolean firstPassword = !user.isPasswordSet();
        user.setPasswordHash(encoder.encode(password));
        if (!firstPassword) emails.passwordChanged(user, true);
    }

    @Transactional(readOnly = true)
    public void requireAdmin() {
        if (!currentUser().isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an ADMIN can do this");
    }

    private AppUser currentUser() {
        Long id = CurrentUser.id().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Log in first"));
        return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Log in first"));
    }

    private LoginResult issue(AppUser user) {
        user.loggedIn(Instant.now());
        TokenService.Issued t = tokens.issue(user);
        log.info("AUTH_LOGIN user={}", user.getUsername());
        return new LoginResult(t.token(), t.expiresAt(), user);
    }

    private AppRole role(String name) {
        return roles.findByName(name).orElseThrow(() -> new IllegalStateException("Role missing: " + name));
    }

    /** At least 8 characters with a letter and a digit. */
    static void checkPassword(String p) {
        if (p == null || p.length() < 8 || p.length() > 128 || !p.matches(".*[A-Za-z].*") || !p.matches(".*\\d.*"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be 8-128 characters with at least one letter and one digit");
    }

    private static String clean(String username) {
        String name = username == null ? "" : username.trim();
        if (!name.matches("[A-Za-z0-9._@-]{1,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username must be 1-64 characters of letters, digits, '.', '_', '@' or '-'");
        return name;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
