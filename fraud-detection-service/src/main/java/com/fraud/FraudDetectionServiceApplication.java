package com.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// [FEATURE-ALLOWLIST] Required so FeatureFilter’s @Scheduled (daily file reload) runs.
@EnableScheduling
@SpringBootApplication
public class FraudDetectionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudDetectionServiceApplication.class, args);
    }

}
