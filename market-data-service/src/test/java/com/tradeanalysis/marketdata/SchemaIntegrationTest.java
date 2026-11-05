package com.tradeanalysis.marketdata;

import com.tradeanalysis.marketdata.client.KiteCredentials;
import com.tradeanalysis.marketdata.client.KiteSessionStore;
import com.tradeanalysis.marketdata.model.CandleEntity;
import com.tradeanalysis.marketdata.repository.CandleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Flyway creates the schema and Hibernate validates CandleEntity against it (ddl-auto: validate). */
@SpringBootTest
@ActiveProfiles("it")
class SchemaIntegrationTest {

    @Autowired CandleRepository candles;
    @Autowired KiteCredentials credentials;
    @Autowired KiteSessionStore sessions;

    @Test
    void candlesAreStoredAndUniquePerSymbolIntervalAndTime() {
        Instant ts = Instant.parse("2026-11-03T03:45:00Z");
        BigDecimal p = new BigDecimal("23610.70");
        candles.save(new CandleEntity("NIFTY 50", "MINUTE_5", ts, p, p, p, p, 0));
        assertThat(candles.count()).isEqualTo(1);
        assertThatThrownBy(() -> candles.saveAndFlush(new CandleEntity("NIFTY 50", "MINUTE_5", ts, p, p, p, p, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void kiteSessionIsStoredForRestartsAndOverwritten() {
        credentials.updateAccessToken("tok-1", "AB1234", "Abheeshek");
        credentials.updateAccessToken("tok-2", "AB1234", "Abheeshek");
        KiteSessionStore.Session s = sessions.load().orElseThrow();
        assertThat(s.accessToken()).isEqualTo("tok-2");
        assertThat(s.userId()).isEqualTo("AB1234");
        assertThat(new KiteCredentials("test", "secret", "", sessions).getAccessToken()).isEqualTo("tok-2");
        assertThat(new KiteCredentials("other-key", "secret", "", sessions).hasToken()).isFalse();   // different app
        assertThat(new KiteCredentials("test", "secret", "env-token", sessions).getAccessToken()).isEqualTo("env-token");
    }
}
