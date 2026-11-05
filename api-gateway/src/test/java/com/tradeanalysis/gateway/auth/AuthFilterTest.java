package com.tradeanalysis.gateway.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuthFilterTest {

    static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private final AuthFilter filter = new AuthFilter(new GatewayAuthKey(mock(org.springframework.r2dbc.core.DatabaseClient.class), SECRET), true);
    private final AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

    static String token(String user, String secret, Instant exp, String issuer) throws Exception {
        return token(user, secret, exp, issuer, List.of("ADMIN", "TRADER"));
    }

    static String token(String user, String secret, Instant exp, String issuer, List<String> roles) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .issuer(issuer).subject(user).claim("roles", roles).expirationTime(Date.from(exp)).build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> req) {
        MockServerWebExchange ex = MockServerWebExchange.from(req);
        passed.set(null);
        StepVerifier.create(filter.filter(ex, e -> { passed.set(e); return Mono.empty(); })).verifyComplete();
        return ex;
    }

    @Test
    void validTokenPassesTheVerifiedUserAndReplacesAnyClientXUser() throws Exception {
        String t = token("abheeshek", SECRET, Instant.now().plusSeconds(600), "trade-analysis");
        MockServerWebExchange ex = run(MockServerHttpRequest.get("/api/trading/status")
                .header("Authorization", "Bearer " + t).header("X-User", "someone-else"));
        assertThat(passed.get()).isNotNull();
        assertThat(passed.get().getRequest().getHeaders().get("X-User")).containsExactly("abheeshek");
        assertThat(passed.get().getRequest().getHeaders().getFirst("X-User-Roles")).isEqualTo("ADMIN,TRADER");
        assertThat((String) ex.getAttribute(AuthFilter.USER_ATTR)).isEqualTo("abheeshek");
    }

    @Test
    void missingWrongExpiredOrForeignTokensAreRejected() throws Exception {
        for (String auth : new String[]{null, "Basic abc", "Bearer " + token("x", "another-secret-another-secret-another-12", Instant.now().plusSeconds(600), "trade-analysis"),
                "Bearer " + token("x", SECRET, Instant.now().minusSeconds(5), "trade-analysis"),
                "Bearer " + token("x", SECRET, Instant.now().plusSeconds(600), "someone-else"), "Bearer not-a-jwt"}) {
            MockServerHttpRequest.BaseBuilder<?> req = MockServerHttpRequest.get("/api/strategies").header("X-User", "admin");
            if (auth != null) req.header("Authorization", auth);
            MockServerWebExchange ex = run(req);
            assertThat(passed.get()).as(String.valueOf(auth)).isNull();
            assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void loginEndpointsAreOpenButCannotCarryAFakeUser() {
        run(MockServerHttpRequest.post("/api/auth/login").header("X-User", "admin"));
        assertThat(passed.get()).isNotNull();
        assertThat(passed.get().getRequest().getHeaders().containsKey("X-User")).isFalse();
        run(MockServerHttpRequest.get("/actuator/health"));
        assertThat(passed.get()).isNotNull();
    }

    @Test
    void signUpIsOpenAndKiteAdminNeedsAnAdminToken() throws Exception {
        run(MockServerHttpRequest.post("/api/auth/signup"));
        assertThat(passed.get()).isNotNull();
        String trader = token("ravi", SECRET, Instant.now().plusSeconds(600), "trade-analysis", List.of("TRADER"));
        MockServerWebExchange ex = run(MockServerHttpRequest.post("/api/market-data/admin/generate-token").header("Authorization", "Bearer " + trader));
        assertThat(passed.get()).isNull();
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        run(MockServerHttpRequest.get("/api/market-data/candles").header("Authorization", "Bearer " + trader));
        assertThat(passed.get()).isNotNull();
        String admin = token("abheeshek", SECRET, Instant.now().plusSeconds(600), "trade-analysis");
        run(MockServerHttpRequest.post("/api/market-data/admin/generate-token").header("Authorization", "Bearer " + admin));
        assertThat(passed.get()).isNotNull();
    }
}
