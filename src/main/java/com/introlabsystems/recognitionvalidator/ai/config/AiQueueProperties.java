package com.introlabsystems.recognitionvalidator.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("validator.ai-queue")
public record AiQueueProperties(@DefaultValue("10m") Duration leaseDuration) {
    public AiQueueProperties {
        if (leaseDuration == null || leaseDuration.compareTo(Duration.ofSeconds(10)) < 0
                || leaseDuration.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("AI lease duration must be between 10 seconds and 1 hour");
        }
    }
    public static void validateSize(int size) {
        if (size < 1 || size > 20) throw new IllegalArgumentException("size must be between 1 and 20");
    }
}
