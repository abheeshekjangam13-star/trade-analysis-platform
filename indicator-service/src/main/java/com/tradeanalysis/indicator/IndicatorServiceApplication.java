package com.tradeanalysis.indicator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.tradeanalysis.indicator", "com.tradeanalysis.common.exception"})
@EnableDiscoveryClient
public class IndicatorServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IndicatorServiceApplication.class, args);
    }
}
