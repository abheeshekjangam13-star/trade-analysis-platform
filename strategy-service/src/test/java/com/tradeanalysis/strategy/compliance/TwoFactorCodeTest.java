package com.tradeanalysis.strategy.compliance;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** TOTP codes against the RFC 6238 SHA-1 test vectors (last 6 digits), and base32 round trips. */
class TwoFactorCodeTest {

    private static final byte[] KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238Vectors() {
        assertThat(TwoFactor.code(KEY, 59 / 30)).isEqualTo("287082");
        assertThat(TwoFactor.code(KEY, 1111111109L / 30)).isEqualTo("081804");
        assertThat(TwoFactor.code(KEY, 1234567890L / 30)).isEqualTo("005924");
        assertThat(TwoFactor.code(KEY, 20000000000L / 30)).isEqualTo("353130");
    }

    @Test
    void base32RoundTrips() {
        assertThat(TwoFactor.base32(KEY)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(TwoFactor.base32Decode("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")).isEqualTo(KEY);
    }
}
