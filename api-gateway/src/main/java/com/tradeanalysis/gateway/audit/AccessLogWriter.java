package com.tradeanalysis.gateway.audit;

import reactor.core.publisher.Mono;

public interface AccessLogWriter {
    Mono<Void> write(AccessLogEntry entry);
}
