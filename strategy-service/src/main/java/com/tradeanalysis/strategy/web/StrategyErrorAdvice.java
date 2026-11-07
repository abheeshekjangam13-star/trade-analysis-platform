package com.tradeanalysis.strategy.web;

import com.tradeanalysis.strategy.service.StrategyBlockedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Error body for a blocked strategy change: the usual fields plus a machine-readable {@code code}. */
@RestControllerAdvice
public class StrategyErrorAdvice {

    @ExceptionHandler(StrategyBlockedException.class)
    public ResponseEntity<Map<String, Object>> blocked(StrategyBlockedException e, HttpServletRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", e.getStatusCode().value());
        body.put("error", "Conflict");
        body.put("code", e.code().name());
        body.put("message", e.getReason());
        body.put("path", req.getRequestURI());
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }
}
