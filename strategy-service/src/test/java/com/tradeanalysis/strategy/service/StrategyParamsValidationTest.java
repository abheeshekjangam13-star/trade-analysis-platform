package com.tradeanalysis.strategy.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Strategies are checked before they are saved, so the live runner never meets parameters it cannot run. */
class StrategyParamsValidationTest {

    @Test
    void invalidSkipDaysAreRefusedOnSave() {
        assertThatThrownBy(() -> StrategyService.cleanParams("NIFTY_OPENING_BREAKOUT", Map.of("skipDays", "2")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("skipDays must be weekdays");
    }

    @Test
    void emptyValuesMeanDefaultsSoNoSkipDaysTradesEveryDay() {
        Map<String, String> in = new HashMap<>(Map.of("skipDays", " ", "lots", "3", "targetPoints", ""));
        assertThat(StrategyService.cleanParams("NIFTY_OPENING_BREAKOUT", in)).isEqualTo(Map.of("lots", "3"));
    }

    @Test
    void validWeekdaysAreKept() {
        assertThat(StrategyService.cleanParams("NIFTY_OPENING_BREAKOUT", Map.of("skipDays", "MON,THU"))).containsEntry("skipDays", "MON,THU");
    }
}
