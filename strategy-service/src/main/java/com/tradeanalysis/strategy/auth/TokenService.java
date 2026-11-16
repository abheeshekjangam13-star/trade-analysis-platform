package com.tradeanalysis.strategy.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.tradeanalysis.strategy.user.AppRole;
import com.tradeanalysis.strategy.user.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/** Issues (and, for tests and tools, checks) the signed login tokens the gateway verifies. */
@Component
public class TokenService {

    public static final String ISSUER = "trade-analysis";

    /** The token claim holding the login's session id (see AppUser#loggedIn). */
    public static final String SESSION_CLAIM = "sid";
    /** The error code a request gets when a newer login has replaced its session. The UI shows why it logged out. */
    public static final String SESSION_REPLACED = "SESSION_REPLACED";
    public static final String SESSION_REPLACED_MESSAGE =
            "You were logged out because this account logged in on another window or device.";

    public record Issued(String token, Instant expiresAt) {}

    /** A valid token's username and session id (null for tokens issued before sessions were added). */
    public record Verified(String username, String sessionId) {
        /** True when this token belongs to the user's latest login (or either side predates sessions). */
        public boolean currentFor(String latestSessionId) {
            return latestSessionId == null || latestSessionId.equals(sessionId);
        }
    }

    private final AuthKeys keys;
    private final Duration ttl;

    public TokenService(AuthKeys keys, @Value("${auth.token-hours:8}") long hours) {
        this.keys = keys;
        this.ttl = Duration.ofHours(hours);
    }

    public Issued issue(AppUser user) {
        Instant now = Instant.now(), exp = now.plus(ttl);
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(user.getUsername()).jwtID(UUID.randomUUID().toString())
                .claim("uid", user.getId())
                .claim("roles", user.getRoles().stream().map(AppRole::getName).toList())
                .issueTime(Date.from(now)).expirationTime(Date.from(exp));
        if (user.getSessionId() != null) b.claim(SESSION_CLAIM, user.getSessionId());
        JWTClaimsSet claims = b.build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(keys.key()));
            return new Issued(jwt.serialize(), exp);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign login token", e);
        }
    }

    /** The username of a valid, unexpired token from this issuer. */
    public Optional<String> verify(String token) {
        return read(token).map(Verified::username);
    }

    /** The username and session id of a valid, unexpired token from this issuer. */
    public Optional<Verified> read(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(keys.key()))) return Optional.empty();
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            if (!ISSUER.equals(c.getIssuer()) || c.getExpirationTime() == null || c.getExpirationTime().before(new Date())) return Optional.empty();
            if (c.getSubject() == null) return Optional.empty();
            return Optional.of(new Verified(c.getSubject(), c.getStringClaim(SESSION_CLAIM)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

}
