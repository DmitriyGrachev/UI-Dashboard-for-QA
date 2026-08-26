package com.introlabsystems.recognitionvalidator.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class B2ClientTimeoutConfigurationTest {

    @Test
    void buildsExplicitTimeoutsAndStandardRetryConfiguration() {
        ClientOverrideConfiguration configuration = B2ClientConfig.clientOverrideConfiguration(properties());

        assertThat(configuration.apiCallAttemptTimeout()).contains(Duration.ofSeconds(45));
        assertThat(configuration.apiCallTimeout()).contains(Duration.ofMinutes(2));
        assertThat(configuration.retryStrategy()).isPresent();
        assertThat(configuration.retryStrategy().orElseThrow().maxAttempts()).isEqualTo(4);
    }

    private static B2StorageProperties properties() {
        return new B2StorageProperties(
                true,
                URI.create("https://s3.eu-central-003.backblazeb2.com"),
                "bucket",
                "access",
                "secret",
                "validator",
                1000,
                8,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofDays(3),
                Duration.ofMinutes(30),
                Duration.ofDays(21),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofSeconds(45),
                Duration.ofMinutes(2),
                4
        );
    }
}
