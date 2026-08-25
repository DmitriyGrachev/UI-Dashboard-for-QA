package com.introlabsystems.recognitionvalidator.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class B2StoragePropertiesTest {

    private static final URI ENDPOINT = URI.create("https://s3.eu-central-003.backblazeb2.com");

    @Test
    void derivesBackblazeRegionFromEndpointHost() {
        B2StorageProperties properties = validProperties();

        assertThat(properties.region().id()).isEqualTo("eu-central-003");
    }

    @Test
    void normalizesObjectPrefixAndBuildsDeterministicObjectKey() {
        B2StorageProperties properties = new B2StorageProperties(
                false,
                ENDPOINT,
                "bucket",
                "access",
                "secret",
                "/validator/",
                100,
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofMinutes(15),
                Duration.ofDays(21)
        );

        assertThat(properties.objectKey("image-123")).isEqualTo("validator/image-123.png");
        assertThat(properties.objectKey("image-123")).isEqualTo(properties.objectKey("image-123"));
    }

    @Test
    void disabledPropertiesAllowEmptyEndpointBucketAndCredentials() {
        B2StorageProperties properties = new B2StorageProperties(
                false,
                URI.create(""),
                "",
                "",
                "",
                "",
                1000,
                8,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofDays(3),
                Duration.ofMinutes(30),
                Duration.ofDays(21)
        );

        assertThat(validate(properties)).isEmpty();
    }

    @Test
    void rejectsEnabledPropertiesWithoutCredentials() {
        B2StorageProperties properties = new B2StorageProperties(
                true,
                ENDPOINT,
                "bucket",
                "",
                "",
                "screenshots",
                100,
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofMinutes(15),
                Duration.ofDays(21)
        );

        assertThat(validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsNonHttpsEndpoint() {
        B2StorageProperties properties = new B2StorageProperties(
                true,
                URI.create("http://s3.eu-central-003.backblazeb2.com"),
                "bucket",
                "access",
                "secret",
                "validator",
                100,
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofMinutes(15),
                Duration.ofDays(21)
        );

        assertThat(validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsEnabledHttpsEndpointOutsideBackblaze() {
        B2StorageProperties properties = new B2StorageProperties(
                true,
                URI.create("https://s3.eu-central-1.amazonaws.com"),
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
                Duration.ofDays(21)
        );

        assertThat(validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsEnabledEndpointWithUserInfo() {
        B2StorageProperties properties = propertiesWithEndpoint(
                true,
                URI.create("https://endpoint-user:endpoint-secret@s3.eu-central-003.backblazeb2.com")
        );

        assertThat(validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsEnabledEndpointWithQuery() {
        B2StorageProperties properties = propertiesWithEndpoint(
                true,
                URI.create("https://s3.eu-central-003.backblazeb2.com?credential=secret")
        );

        assertThat(validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsEnabledEndpointWithFragment() {
        B2StorageProperties properties = propertiesWithEndpoint(
                true,
                URI.create("https://s3.eu-central-003.backblazeb2.com#credentials")
        );

        assertThat(validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsEnabledEndpointWithNonRootPath() {
        B2StorageProperties properties = propertiesWithEndpoint(
                true,
                URI.create("https://s3.eu-central-003.backblazeb2.com/tenant")
        );

        assertThat(validate(properties)).isNotEmpty();
    }

    @Test
    void allowsEnabledEndpointWithRootPath() {
        B2StorageProperties properties = propertiesWithEndpoint(
                true,
                URI.create("https://s3.eu-central-003.backblazeb2.com/")
        );

        assertThat(validate(properties)).isEmpty();
    }

    @Test
    void rejectsNonPositivePresignedUrlTtl() {
        B2StorageProperties properties = new B2StorageProperties(
                false,
                ENDPOINT,
                "bucket",
                "",
                "",
                "screenshots",
                100,
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ZERO,
                Duration.ofDays(21)
        );

        assertThat(validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsPresignedUrlTtlLongerThanSevenDays() {
        B2StorageProperties properties = new B2StorageProperties(
                false,
                ENDPOINT,
                "",
                "",
                "",
                "validator",
                1000,
                8,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofDays(3),
                Duration.ofDays(8),
                Duration.ofDays(21)
        );

        assertThat(validate(properties)).isNotEmpty();
    }

    @Test
    void redactsCredentialsFromToString() {
        B2StorageProperties properties = new B2StorageProperties(
                true,
                ENDPOINT,
                "bucket",
                "access-key-literal",
                "secret-key-literal",
                "validator",
                1000,
                8,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofDays(3),
                Duration.ofMinutes(30),
                Duration.ofDays(21)
        );

        assertThat(properties.toString())
                .doesNotContain("access-key-literal")
                .doesNotContain("secret-key-literal")
                .contains("[REDACTED]");
    }

    @Test
    void redactsEndpointUserInfoFromToStringWhenDisabled() {
        B2StorageProperties properties = propertiesWithEndpoint(
                false,
                URI.create("https://endpoint-user:endpoint-secret@s3.eu-central-003.backblazeb2.com")
        );

        assertThat(properties.toString())
                .doesNotContain("endpoint-user")
                .doesNotContain("endpoint-secret");
    }

    @Test
    void redactsEndpointUserInfoFromToStringWhenConfigurationIsInvalid() {
        B2StorageProperties properties = propertiesWithEndpoint(
                true,
                URI.create("https://endpoint-user:endpoint-secret@s3.eu-central-003.backblazeb2.com/tenant")
        );

        assertThat(validate(properties)).isNotEmpty();
        assertThat(properties.toString())
                .doesNotContain("endpoint-user")
                .doesNotContain("endpoint-secret");
    }

    @ParameterizedTest
    @ValueSource(longs = {20, 22})
    void rejectsMetadataRetentionOtherThanExactlyTwentyOneDays(long retentionDays) {
        B2StorageProperties properties = new B2StorageProperties(
                false,
                ENDPOINT,
                "bucket",
                "",
                "",
                "validator",
                1000,
                8,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofDays(3),
                Duration.ofMinutes(30),
                Duration.ofDays(retentionDays)
        );

        assertThat(validate(properties)).isNotEmpty();
    }

    private static B2StorageProperties validProperties() {
        return new B2StorageProperties(
                false,
                ENDPOINT,
                "bucket",
                "",
                "",
                "validator",
                1000,
                8,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofDays(3),
                Duration.ofMinutes(30),
                Duration.ofDays(21)
        );
    }

    private static B2StorageProperties propertiesWithEndpoint(boolean enabled, URI endpoint) {
        return new B2StorageProperties(
                enabled,
                endpoint,
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
                Duration.ofDays(21)
        );
    }

    private static java.util.Set<jakarta.validation.ConstraintViolation<B2StorageProperties>> validate(
            B2StorageProperties properties
    ) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(properties);
        }
    }
}
