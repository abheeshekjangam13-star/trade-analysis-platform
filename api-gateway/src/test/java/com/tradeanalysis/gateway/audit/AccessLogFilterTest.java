package com.tradeanalysis.gateway.audit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogFilterTest {

    private final List<AccessLogEntry> written = new ArrayList<>();
    private final AccessLogFilter filter = new AccessLogFilter(e -> { written.add(e); return Mono.empty(); },
            Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void recordsWhoCalledWhatAndTheResult() {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/backtests?strategyId=56&lookbackDays=30")
                .header("X-User", "abheeshek").header("X-Forwarded-For", "10.0.0.7, 10.0.0.1")
                .header("User-Agent", "bruno-runtime/2.0"));
        ex.getAttributes().put(com.tradeanalysis.gateway.auth.AuthFilter.USER_ATTR, "abheeshek");   // set by AuthFilter
        StepVerifier.create(filter.filter(ex, e -> { e.getResponse().setStatusCode(HttpStatus.CREATED); return Mono.empty(); }))
                .verifyComplete();

        assertThat(written).hasSize(1);
        AccessLogEntry e = written.get(0);
        assertThat(e.username()).isEqualTo("abheeshek");
        assertThat(e.method()).isEqualTo("POST");
        assertThat(e.path()).isEqualTo("/api/backtests");
        assertThat(e.query()).isEqualTo("strategyId=56&lookbackDays=30");
        assertThat(e.statusCode()).isEqualTo(201);
        assertThat(e.clientIp()).isEqualTo("10.0.0.7");
        assertThat(e.userAgent()).isEqualTo("bruno-runtime/2.0");
    }

    @Test
    void skipsNonApiPathsAndToleratesAnonymousCalls() {
        StepVerifier.create(filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health")),
                e -> Mono.empty())).verifyComplete();
        assertThat(written).isEmpty();

        StepVerifier.create(filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/strategies")),
                e -> Mono.empty())).verifyComplete();
        assertThat(written).singleElement().satisfies(e -> {
            assertThat(e.username()).isNull();
            assertThat(e.query()).isNull();
        });
    }

    @Test
    void aFailingDatabaseDoesNotFailTheRequest() {
        AccessLogFilter failing = new AccessLogFilter(e -> Mono.error(new IllegalStateException("db down")));
        StepVerifier.create(failing.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/strategies")),
                e -> Mono.empty())).verifyComplete();
    }

    @Test
    void tokensInTheQueryAreMasked() {
        assertThat(AccessLogFilter.redact("token=abc123&x=1")).isEqualTo("token=***&x=1");
        assertThat(AccessLogFilter.redact("requestToken=Y3V6&symbol=NIFTY")).isEqualTo("requestToken=***&symbol=NIFTY");
        assertThat(AccessLogFilter.redact("strategyId=56&lookbackDays=30")).isEqualTo("strategyId=56&lookbackDays=30");
        assertThat(AccessLogFilter.redact("access_token=a&api_secret=b")).isEqualTo("access_token=***&api_secret=***");
        assertThat(AccessLogFilter.redact(null)).isNull();
    }
}
