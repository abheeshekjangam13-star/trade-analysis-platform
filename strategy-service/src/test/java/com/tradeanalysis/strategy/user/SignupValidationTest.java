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
    void planLimitsAndPrices() {
        assertThat(Plan.FREE.maxStrategies()).isEqualTo(2);
        assertThat(Plan.FREE.maxBacktestDays()).isEqualTo(90);
        assertThat(Plan.FREE.paperTrading()).isFalse();
        assertThat(Plan.GOLD.maxStrategies()).isEqualTo(6);
        assertThat(Plan.GOLD.maxBacktestDays()).isEqualTo(365);
        assertThat(Plan.GOLD.paperTrading()).isTrue();
        assertThat(Plan.GOLD.liveTrading()).isFalse();
        assertThat(Plan.GOLD.pricePerMonth()).isEqualTo(1499);
        assertThat(Plan.GOLD.discountPercent()).isEqualTo(30);
        assertThat(Plan.PREMIUM.maxStrategies()).isEqualTo(12);
        assertThat(Plan.PREMIUM.maxBacktestDays()).isEqualTo(730);
        assertThat(Plan.PREMIUM.liveTrading()).isTrue();
        assertThat(Plan.PREMIUM.pricePerMonth()).isEqualTo(2499);
        assertThat(Plan.PREMIUM.discountPercent()).isEqualTo(50);
        assertThat(Plan.PREMIUM.maxLiveStrategies()).isEqualTo(8);
        assertThat(Plan.PREMIUM.maxLiveLots()).isEqualTo(2);
        assertThat(Plan.PREMIUM_PLUS.pricePerMonth()).isEqualTo(4499);
        assertThat(Plan.PREMIUM_PLUS.maxLiveStrategies()).isEqualTo(12);
        assertThat(Plan.PREMIUM_PLUS.maxLiveLots()).isEqualTo(4);
        assertThat(Plan.PREMIUM_PRO.pricePerMonth()).isEqualTo(7999);
        assertThat(Plan.PREMIUM_PRO.maxLiveStrategies()).isEqualTo(20);
        assertThat(Plan.PREMIUM_PRO.maxLiveLots()).isEqualTo(8);
        assertThat(Plan.GOLD.maxLiveStrategies()).isZero();
        assertThat(Plan.PREMIUM_PLUS.label()).isEqualTo("Premium Plus");
        assertThat(Plan.GOLD.label()).isEqualTo("Gold");
        assertThat(Plan.PREMIUM.next()).isEqualTo(Plan.PREMIUM_PLUS);
        assertThat(Plan.PREMIUM_PRO.next()).isNull();
        assertThat(Plan.PREMIUM_PRO.atLeast(Plan.PREMIUM)).isTrue();   // live trading checks use atLeast(PREMIUM)
        for (Plan p : Plan.values())   // the discount shown matches the price charged (to the rupee)
            assertThat(Math.round(p.listPricePerMonth() * (100 - p.discountPercent()) / 100.0)).isEqualTo(p.pricePerMonth());
    }

    @Test
    void accessRules() {
        AppUser u = new AppUser("u", null, null);
        assertThat(Access.deny(u, Plan.FREE, "GET", "/api/strategies")).isNull();
        assertThat(Access.deny(u, Plan.FREE, "POST", "/api/backtests")).isNull();         // limited in StrategyService
        assertThat(Access.deny(u, Plan.FREE, "POST", "/api/strategies")).isNull();
        assertThat(Access.deny(u, Plan.FREE, "POST", "/api/portfolios")).contains("GOLD");
        assertThat(Access.deny(u, Plan.FREE, "PUT", "/api/trading/strategies/1")).isNull();   // OFF for anyone; PAPER needs GOLD (TradingService)
        assertThat(Access.deny(u, Plan.FREE, "POST", "/api/trading/orders")).contains("GOLD");
        assertThat(Access.deny(u, Plan.GOLD, "POST", "/api/backtests")).isNull();
        assertThat(Access.deny(u, Plan.FREE, "POST", "/api/auth/change-password")).isNull();
        assertThat(Access.deny(u, Plan.PREMIUM, "GET", "/api/users")).isNotNull();
        assertThat(Access.deny(u, Plan.FREE, "GET", "/api/users/me")).isNull();
        assertThat(Access.deny(u, Plan.PREMIUM, "PUT", "/api/trading/controls")).isNotNull();
        assertThat(Access.deny(u, Plan.FREE, "GET", "/api/trading/controls")).isNull();
        assertThat(Access.deny(u, Plan.PREMIUM, "GET", "/api/access-log")).isNotNull();
    }
}
