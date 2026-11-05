package com.tradeanalysis.strategy.user;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignupValidationTest {

    @Test
    void phoneNumbersAreNormalised() {
        assertThat(UserService.validPhone("9876543210")).isEqualTo("+919876543210");
        assertThat(UserService.validPhone("098765-43210")).isEqualTo("+919876543210");
        assertThat(UserService.validPhone("91 98765 43210")).isEqualTo("+919876543210");
        assertThat(UserService.validPhone("+91 (98765) 43210")).isEqualTo("+919876543210");
        assertThat(UserService.validPhone("+44 7911 123456")).isEqualTo("+447911123456");
        for (String bad : new String[]{"", "12345", "5876543210", "+91 5876543210", "98765abc10", "+1234"})
            assertThatThrownBy(() -> UserService.validPhone(bad)).as(bad).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void emailsAreCheckedAndLowerCased() {
        assertThat(UserService.validEmail(" Ravi@Example.COM ")).isEqualTo("ravi@example.com");
        for (String bad : new String[]{"", "ravi", "ravi@", "ravi@example", "a b@example.com"})
            assertThatThrownBy(() -> UserService.validEmail(bad)).as(bad).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void accessRules() {
        AppUser u = new AppUser("u", null, null);
        assertThat(Access.deny(u, Plan.FREE, "GET", "/api/strategies")).isNull();
        assertThat(Access.deny(u, Plan.FREE, "POST", "/api/backtests")).contains("GOLD");
        assertThat(Access.deny(u, Plan.GOLD, "POST", "/api/backtests")).isNull();
        assertThat(Access.deny(u, Plan.FREE, "POST", "/api/auth/change-password")).isNull();
        assertThat(Access.deny(u, Plan.PREMIUM, "GET", "/api/users")).isNotNull();
        assertThat(Access.deny(u, Plan.FREE, "GET", "/api/users/me")).isNull();
        assertThat(Access.deny(u, Plan.PREMIUM, "PUT", "/api/trading/controls")).isNotNull();
        assertThat(Access.deny(u, Plan.FREE, "GET", "/api/trading/controls")).isNull();
        assertThat(Access.deny(u, Plan.PREMIUM, "GET", "/api/access-log")).isNotNull();
    }
}
