package com.tradeanalysis.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record IndicatorRequest(
        @NotBlank String indicator,
        @NotNull PriceSeries series,
        Map<String, Object> parameters) {
}
