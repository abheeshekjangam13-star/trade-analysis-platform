package com.tradeanalysis.strategy.trading;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class TradingConfig {
    @Bean
    @ConditionalOnMissingBean
    Clock clock() { return Clock.systemUTC(); }
}
