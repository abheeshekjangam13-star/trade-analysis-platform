package com.tradeanalysis.marketdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.tradeanalysis.marketdata", "com.tradeanalysis.common.exception"})
@EnableDiscoveryClient
@EnableScheduling
public class MarketDataServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MarketDataServiceApplication.class, args);
    }
}
