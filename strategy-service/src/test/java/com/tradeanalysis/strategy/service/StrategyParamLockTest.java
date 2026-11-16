package com.tradeanalysis.strategy.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which parameter changes may go through while a strategy trades PAPER/LIVE. */
class StrategyParamLockTest {

    static final Map<String, String> LIVE = Map.of("referenceMode", "PREVIOUS_DAY", "lots", "2", "lotSize", "75");

    @Test
    void turningTheMonthEndSkipOnOrOffIsTheOnlyChangeAllowedWhileTrading() {
        assertThat(StrategyService.onlySkipMonthEndChanged(LIVE, Map.of("referenceMode", "PREVIOUS_DAY", "lots", "2", "skipMonthEndExpiry", "true"))).isTrue();
        assertThat(StrategyService.onlySkipMonthEndChanged(Map.of("lots", "2", "skipMonthEndExpiry", "true"), Map.of("lots", "2"))).isTrue();
        assertThat(StrategyService.onlySkipMonthEndChanged(LIVE, Map.of("referenceMode", "PREVIOUS_DAY", "lots", "3", "skipMonthEndExpiry", "true"))).isFalse();
        assertThat(StrategyService.onlySkipMonthEndChanged(LIVE, Map.of("referenceMode", "PREVIOUS_DAY", "lots", "2", "targetPoints", "40"))).isFalse();
    }
}
