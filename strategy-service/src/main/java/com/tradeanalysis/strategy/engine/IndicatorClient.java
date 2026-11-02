package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.IndicatorRequest;
import com.tradeanalysis.common.dto.IndicatorResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "indicator-service", path = "/api/indicators")
public interface IndicatorClient {

    @PostMapping("/compute")
    IndicatorResult compute(@RequestBody IndicatorRequest req);
}
