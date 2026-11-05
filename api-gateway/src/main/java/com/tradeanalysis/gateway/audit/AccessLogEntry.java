package com.tradeanalysis.gateway.audit;

import java.time.Instant;

/** One API request as stored in the access_log table. */
public record AccessLogEntry(String username, String method, String path, String query, Integer statusCode,
                             long durationMs, String clientIp, String userAgent, Instant createdAt) {}
