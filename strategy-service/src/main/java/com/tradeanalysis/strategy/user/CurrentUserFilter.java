package com.tradeanalysis.strategy.user;

import com.tradeanalysis.strategy.auth.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Identifies the caller of every /api request from the login token ("Authorization: Bearer ..."), checked here as
 * well as at the gateway, so calling this service directly (port 8083) doesn't get around login. The X-User header
 * is ignored unless {@code auth.trust-user-header=true} (development and tests only). Without a valid token a
 * request gets 401, except the login calls themselves. Only the user's latest login is valid: a token from an older
 * login (another window or device) gets 401 with code SESSION_REPLACED.
 */
@Component
public class CurrentUserFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-User";
    // The Razorpay webhook has no login; PaymentController verifies its HMAC signature instead.
    static final Set<String> PUBLIC = Set.of("/api/auth/status", "/api/auth/plans", "/api/auth/signup", "/api/auth/signup/payment", "/api/auth/signup/verify",
            "/api/auth/setup", "/api/auth/login", "/api/auth/grievance",
            "/api/payments/webhook");

    private final UserService users;
    private final TokenService tokens;
    private final boolean autoRegister;
    private final boolean trustHeader;

    public CurrentUserFilter(UserService users, TokenService tokens,
                             @Value("${auth.auto-register-users:false}") boolean autoRegister,
                             @Value("${auth.trust-user-header:false}") boolean trustHeader) {
        this.users = users;
        this.tokens = tokens;
        this.autoRegister = autoRegister;
        this.trustHeader = trustHeader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (!PUBLIC.contains(request.getRequestURI())) {
                String token = bearer(request);
                Optional<TokenService.Verified> verified = token != null ? tokens.read(token) : Optional.empty();
                Optional<String> username = token != null ? verified.map(TokenService.Verified::username) : headerUser(request);
                if (username.isEmpty() && !trustHeader) {
                    response.sendError(401, token == null ? "Log in first" : "Session expired or invalid: log in again");
                    return;
                }
                if (username.isPresent()) {
                    try {
                        AppUser user = users.resolve(username.get(), autoRegister && trustHeader);
                        if (verified.isPresent() && !verified.get().currentFor(user.getSessionId())) {
                            sessionReplaced(response);
                            return;
                        }
                        Plan plan = user.effectivePlan(java.time.Instant.now());
                        String denied = Access.deny(user, plan, request.getMethod(), request.getRequestURI());
                        if (denied != null) {
                            response.sendError(403, denied);
                            return;
                        }
                        CurrentUser.set(user.getId(), user.isAdmin(), plan);
                    } catch (ResponseStatusException e) {
                        response.sendError(e.getStatusCode().value(), e.getReason());
                        return;
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            CurrentUser.clear();
        }
    }

    private Optional<String> headerUser(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        return trustHeader && header != null && !header.isBlank() ? Optional.of(header) : Optional.empty();
    }

    /** 401 with a code, so the UI can say the account logged in elsewhere rather than "session expired". */
    private static void sessionReplaced(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"code\":\"" + TokenService.SESSION_REPLACED
                + "\",\"message\":\"" + TokenService.SESSION_REPLACED_MESSAGE + "\"}");
    }

    private static String bearer(HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        return auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) && auth.length() > 7 ? auth.substring(7).trim() : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }
}
