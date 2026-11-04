package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;

/** Declarative HTTP client for market-data-service, resolved through Eureka. */
@FeignClient(name = "market-data-service", path = "/api/market-data")
public interface MarketDataClient {

    @GetMapping("/{symbol}")
    PriceSeries getSeries(@PathVariable String symbol,
                          @RequestParam Interval interval,
                          @RequestParam int limit);

    @GetMapping("/{symbol}/range")
    PriceSeries getRange(@PathVariable String symbol,
                         @RequestParam Interval interval,
                         @RequestParam Instant from,
                         @RequestParam Instant to);
}
