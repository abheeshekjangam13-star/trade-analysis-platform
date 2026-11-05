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

    public record Issued(String token, Instant expiresAt) {}

    private final AuthKeys keys;
    private final Duration ttl;

    public TokenService(AuthKeys keys, @Value("${auth.token-hours:8}") long hours) {
        this.keys = keys;
        this.ttl = Duration.ofHours(hours);
    }

    public Issued issue(AppUser user) {
        Instant now = Instant.now(), exp = now.plus(ttl);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(user.getUsername()).jwtID(UUID.randomUUID().toString())
                .claim("uid", user.getId())
                .claim("roles", user.getRoles().stream().map(AppRole::getName).toList())
                .issueTime(Date.from(now)).expirationTime(Date.from(exp)).build();
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
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(keys.key()))) return Optional.empty();
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            if (!ISSUER.equals(c.getIssuer()) || c.getExpirationTime() == null || c.getExpirationTime().before(new Date())) return Optional.empty();
            return Optional.ofNullable(c.getSubject());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

}
