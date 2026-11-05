package com.tradeanalysis.strategy.user;

import com.tradeanalysis.common.exception.NotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class UserService {

    static final String DEFAULT_ROLE = "TRADER";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._@-]{1,64}");
    private static final Duration SEEN_RESOLUTION = Duration.ofMinutes(1);

    private final AppUserRepository users;
    private final AppRoleRepository roles;
    private final AccessLogRepository accessLogs;
    private final com.tradeanalysis.strategy.notify.EmailService emails;

    public UserService(AppUserRepository users, AppRoleRepository roles, AccessLogRepository accessLogs,
                       com.tradeanalysis.strategy.notify.EmailService emails) {
        this.users = users; this.roles = roles; this.accessLogs = accessLogs; this.emails = emails;
    }

    /**
     * Finds the user for an X-User header value. With login on, the gateway sets the header from a verified token, so
     * the user exists; auto-registration (role TRADER on first use) is only for development without login.
     */
    @Transactional
    public AppUser resolve(String username, boolean autoRegister) {
        String name = validUsername(username);
        AppUser user = users.findByUsernameIgnoreCase(name).orElse(null);
        if (user == null && !autoRegister) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user: " + name);
        if (user == null) {   // development only: behaves like the old TRADER default
            user = register(name, null, null, Set.of(DEFAULT_ROLE));
            user.setPlan(Plan.GOLD, null);
        }
        if (user.getStatus() != AppUser.Status.ACTIVE)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, statusMessage(user.getStatus()));
        Instant now = Instant.now();
        if (user.getLastSeenAt() == null || user.getLastSeenAt().isBefore(now.minus(SEEN_RESOLUTION))) user.seen(now);
        return user;
    }

    @Transactional
    public AppUser create(String username, String email, String fullName, Collection<String> roleNames) {
        requireAdmin();
        String name = validUsername(username);
        if (users.findByUsernameIgnoreCase(name).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists: " + name);
        AppUser u = register(name, email == null || email.isBlank() ? null : validEmail(email), fullName,
                roleNames == null || roleNames.isEmpty() ? Set.of(DEFAULT_ROLE) : roleNames);
        Set<String> r = new HashSet<>();
        u.getRoles().forEach(x -> r.add(x.getName()));
        u.setPlan(r.contains("PREMIUM") ? Plan.PREMIUM : r.contains("TRADER") ? Plan.GOLD : Plan.FREE, null);   // plan follows the role
        emails.accountCreated(u);
        return u;
    }

    @Transactional
    public AppUser update(Long id, String email, String fullName, Collection<String> roleNames, AppUser.Status status, String phone) {
        requireAdmin();
        AppUser user = get(id);
        if (user.getId().equals(CurrentUser.id().orElse(null)) && (status == AppUser.Status.DISABLED
                || roleNames != null && roleNames.stream().noneMatch(r -> "ADMIN".equalsIgnoreCase(r.trim()))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can't disable yourself or remove your own ADMIN role");
        if (email != null) user.setEmail(email.isBlank() ? null : validEmail(email));
        if (fullName != null) user.setFullName(fullName);
        if (phone != null) user.setPhone(phone.isBlank() ? null : validPhone(phone));
        if (roleNames != null) user.setRoles(lookupRoles(roleNames));
        if (status != null) user.setStatus(status);
        return user;
    }

    @Transactional(readOnly = true)
    public AppUser get(Long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<AppUser> list() { requireAdmin(); return users.findAll(); }

    /** Why a non-ACTIVE user can't log in or call the API. */
    public static String statusMessage(AppUser.Status status) {
        return switch (status) {
            case PENDING -> "Your account is waiting for admin approval";
            case REJECTED -> "Your sign-up was not approved. Contact the admin";
            case DISABLED -> "This account is disabled";
            case ACTIVE -> "";
        };
    }

    public record SignupResult(Long id, String username, AppUser.Status status, Plan requestedPlan, String message) {}

    /** Self sign-up: email and phone required; the account stays PENDING (no access) until an admin approves it. */
    @Transactional
    public SignupResult signup(String username, String email, String phone, String fullName, Plan requested, String passwordHash) {
        String name = validUsername(username);
        String mail = validEmail(email);
        String tel = validPhone(phone);
        if (users.findByUsernameIgnoreCase(name).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is taken");
        if (users.findByEmailIgnoreCase(mail).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That email is already registered");
        if (users.findByPhone(tel).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That phone number is already registered");
        AppUser u = new AppUser(name, mail, fullName == null || fullName.isBlank() ? null : fullName.trim());
        u.setPhone(tel);
        u.requestPlan(requested == null ? Plan.FREE : requested);
        u.setStatus(AppUser.Status.PENDING);
        u.setPasswordHash(passwordHash);
        users.save(u);
        emails.signupReceived(u);
        return new SignupResult(u.getId(), u.getUsername(), u.getStatus(), u.getRequestedPlan(),
                "Thanks! An admin will review your sign-up" + (u.getRequestedPlan() == Plan.FREE ? "" :
                " and confirm payment for " + u.getRequestedPlan() + " (₹" + u.getRequestedPlan().pricePerMonth() + "/month)")
                + ". You can log in once it is approved.");
    }

    @Transactional(readOnly = true)
    public List<AppUser> pending() { requireAdmin(); return users.findByStatusOrderByCreatedAtAsc(AppUser.Status.PENDING); }

    /** ADMIN: approve a sign-up (PENDING or REJECTED) with a plan; paid plans may have an expiry date. */
    @Transactional
    public AppUser approve(Long id, Plan plan, Instant expiresAt, String note) {
        Long admin = requireAdminId();
        AppUser u = get(id);
        if (u.getStatus() != AppUser.Status.PENDING && u.getStatus() != AppUser.Status.REJECTED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, u.getUsername() + " is already " + u.getStatus());
        Plan p = plan != null ? plan : u.getRequestedPlan() != null ? u.getRequestedPlan() : Plan.FREE;
        applyPlan(u, p, expiresAt);
        u.setStatus(AppUser.Status.ACTIVE);
        u.approved(admin, Instant.now());
        u.setReviewNote(note);
        emails.approved(u);
        return u;
    }

    /** ADMIN: refuse a PENDING sign-up. */
    @Transactional
    public AppUser reject(Long id, String note) {
        requireAdmin();
        AppUser u = get(id);
        if (u.getStatus() != AppUser.Status.PENDING)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only PENDING sign-ups can be rejected (" + u.getUsername() + " is " + u.getStatus() + ")");
        u.setStatus(AppUser.Status.REJECTED);
        u.setReviewNote(note);
        emails.rejected(u);
        return u;
    }

    /** ADMIN: change an existing user's plan (renewal, upgrade, downgrade). */
    @Transactional
    public AppUser changePlan(Long id, Plan plan, Instant expiresAt) {
        requireAdmin();
        if (plan == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a plan: FREE, GOLD or PREMIUM");
        AppUser u = get(id);
        applyPlan(u, plan, expiresAt);
        return u;
    }

    /** Sets the plan and its role (VIEWER / TRADER / PREMIUM), keeping ADMIN. */
    private void applyPlan(AppUser u, Plan plan, Instant expiresAt) {
        if (expiresAt != null && plan != Plan.FREE && !expiresAt.isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The plan expiry must be in the future");
        u.setPlan(plan, expiresAt);
        Set<String> names = new LinkedHashSet<>();
        if (u.isAdmin()) names.add("ADMIN");
        names.add(plan.role());
        u.setRoles(lookupRoles(names));
    }

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}");

    static String validEmail(String email) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (e.length() > 255 || !EMAIL.matcher(e).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid email address");
        return e;
    }

    /** Indian mobile numbers become +91XXXXXXXXXX; other countries need the +country code (8-15 digits). */
    static String validPhone(String phone) {
        String p = phone == null ? "" : phone.replaceAll("[\\s()-]", "");
        String digits = p.startsWith("+") ? p.substring(1) : p;
        if (!digits.matches("\\d+")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid phone number");
        if (!p.startsWith("+")) {
            if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
            if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);
            if (digits.length() == 10 && digits.matches("[6-9]\\d{9}")) return "+91" + digits;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a 10-digit Indian mobile number, or the number with its +country code");
        }
        if (digits.startsWith("91") && !digits.matches("91[6-9]\\d{9}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid Indian mobile number");
        if (digits.length() < 8 || digits.length() > 15) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid phone number");
        return "+" + digits;
    }

    @Transactional(readOnly = true)
    public List<AppRole> roles() { return roles.findAll(); }

    @Transactional(readOnly = true)
    public List<AccessLog> accessLog(String username, int limit) {
        requireAdmin();   // the access log (who called what, from which IP) is for admins only
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 1000)));
        return username == null || username.isBlank()
                ? accessLogs.findAllByOrderByCreatedAtDesc(page)
                : accessLogs.findByUsernameIgnoreCaseOrderByCreatedAtDesc(username.trim(), page);
    }

    /** Only ADMINs manage users. */
    public void requireAdmin() { requireAdminId(); }

    private Long requireAdminId() {
        AppUser me = CurrentUser.id().flatMap(users::findById)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Log in first"));
        if (!me.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an ADMIN can manage users");
        return me.getId();
    }

    private AppUser register(String username, String email, String fullName, Collection<String> roleNames) {
        AppUser user = new AppUser(username, email, fullName);
        user.setRoles(lookupRoles(roleNames));
        return users.save(user);
    }

    private Set<AppRole> lookupRoles(Collection<String> names) {
        Set<AppRole> found = new LinkedHashSet<>();
        for (String n : names) {
            String role = n.trim().toUpperCase(Locale.ROOT);
            found.add(roles.findByName(role).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role: " + role)));
        }
        return found;
    }

    static String validUsername(String username) {
        String name = username == null ? "" : username.trim();
        if (!USERNAME.matcher(name).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid user '" + name + "': must be 1-64 characters of letters, digits, '.', '_', '@' or '-'"
                    + (name.contains("{{") ? " (a Bruno variable was not resolved; select an environment)" : ""));
        return name;
    }
}
