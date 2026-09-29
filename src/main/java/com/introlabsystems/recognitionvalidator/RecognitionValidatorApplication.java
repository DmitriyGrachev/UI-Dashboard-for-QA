package com.introlabsystems.recognitionvalidator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class RecognitionValidatorApplication {

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--backfill-statistics")) {
            com.introlabsystems.recognitionvalidator.maintenance.StatisticsBackfill.run(args);
            return;
        }
        SpringApplication.run(RecognitionValidatorApplication.class, args);
    }
}
