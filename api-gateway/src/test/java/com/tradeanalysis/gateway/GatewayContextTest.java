package com.tradeanalysis.gateway;

import com.tradeanalysis.gateway.audit.AccessLogFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** The gateway application context starts (bean wiring), without Eureka or a running database. */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
class GatewayContextTest {

    @Autowired AccessLogFilter accessLogFilter;
    @Autowired org.springframework.cloud.gateway.route.RouteDefinitionLocator routes;

    @Test
    void contextStartsWithTheAccessLogFilter() {
        assertThat(accessLogFilter).isNotNull();
    }

    @Test
    void everyApiTheUiCallsIsRouted() {
        String predicates = routes.getRouteDefinitions().collectList().block().toString();
        for (String path : new String[]{"/api/auth/**", "/api/strategies/**", "/api/backtests/**", "/api/nifty-trades/**",
                "/api/portfolios/**", "/api/users/**", "/api/access-log/**", "/api/trading/**", "/api/market-data/**", "/api/indicators/**"})
            assertThat(predicates).as(path).contains(path);
    }
}
