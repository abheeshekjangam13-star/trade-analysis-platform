package com.tradeanalysis.strategy.user;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIdsTest {

    @Test
    void everyIdHasItsOwnCodeAndTheCodeLeadsBack() {
        Set<String> seen = new HashSet<>();
        for (long id = 1; id <= 20_000; id++) {
            String code = ClientIds.code(id);
            assertThat(code).matches("SG\\d{6}");
            assertThat(seen.add(code)).isTrue();
            assertThat(ClientIds.userId(code)).contains(id);
        }
        assertThat(ClientIds.userId(ClientIds.code(999_999).toLowerCase())).contains(999_999L);
        assertThat(ClientIds.code(2)).isNotEqualTo("SG000002");   // not just the id
    }

    @Test
    void textThatIsNotAClientIdIsIgnored() {
        assertThat(ClientIds.userId(null)).isEmpty();
        assertThat(ClientIds.userId("ravi")).isEmpty();
        assertThat(ClientIds.userId("SG12345")).isEmpty();
        assertThat(ClientIds.userId("XX123456")).isEmpty();
        assertThatThrownBy(() -> ClientIds.code(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientIds.code(1_000_000)).isInstanceOf(IllegalArgumentException.class);
    }
}
