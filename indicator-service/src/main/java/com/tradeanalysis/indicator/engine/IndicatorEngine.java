package com.tradeanalysis.indicator.engine;

import com.tradeanalysis.common.dto.IndicatorResult;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.indicator.Params;

/** Contract every indicator plugin must implement. */
public interface IndicatorEngine {
    /** Short upper-case name (e.g. "SMA", "RSI"). */
    String name();
    IndicatorResult compute(PriceSeries series, Params params);
}
