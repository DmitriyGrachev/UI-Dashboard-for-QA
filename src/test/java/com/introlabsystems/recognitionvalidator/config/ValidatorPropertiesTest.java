package com.introlabsystems.recognitionvalidator.config;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatorPropertiesTest {

    @Test
    void zeroCleanupBatchLimitIsValidForUnlimitedMode() {
        ValidatorProperties properties = new ValidatorProperties(
                Path.of("data/images"),
                List.of("bj_igt"),
                1000,
                Duration.ofMinutes(30),
                Duration.ofDays(4),
                5000,
                0,
                true,
                Duration.ofSeconds(2),
                50000,
                false
        );

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }
}
