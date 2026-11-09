package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.indicator.Params;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

/** The lot size comes from the index, so a lot size stored with a strategy is ignored rather than rejected. */
class LotSizeValidationTest {

    @Test
    void aStoredLotSizeIsIgnored() {
        assertThatCode(() -> NiftyOpeningBreakoutEngine.validateParams(new Params(Map.of("underlying", "BANKNIFTY", "lotSize", "65"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> NiftyOpeningBreakoutEngine.validateParams(new Params(Map.of("lotSize", "30"))))
                .doesNotThrowAnyException();
    }
}
