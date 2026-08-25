package com.introlabsystems.recognitionvalidator.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Validated
@ConfigurationProperties("validator.b2")
public record B2StorageProperties(
        boolean enabled,
        URI endpoint,
        String bucket,
        String accessKeyId,
        String secretAccessKey,
        String objectPrefix,
        @Min(1) int uploadBatchSize,
        @Min(1) int uploadConcurrency,
        @NotNull Duration uploadDelay,
        @NotNull Duration uploadRetryDelay,
        @NotNull Duration localPreferredAge,
        @NotNull Duration presignedUrlTtl,
        @NotNull Duration metadataRetention,
        @NotNull Duration connectTimeout,
        @NotNull Duration socketTimeout,
        @NotNull Duration apiCallAttemptTimeout,
        @NotNull Duration apiCallTimeout,
        @Min(1) int maxAttempts
) {

    private static final Pattern B2_ENDPOINT = Pattern.compile(
            "^s3\\.([^.]+)\\.backblazeb2\\.com$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Duration MAX_PRESIGNED_URL_TTL = Duration.ofDays(7);
    private static final Duration REQUIRED_METADATA_RETENTION = Duration.ofDays(21);

    public B2StorageProperties {
        objectPrefix = normalizePrefix(objectPrefix);
    }

    public Region region() {
        if (endpoint == null || endpoint.getHost() == null) {
            throw new IllegalArgumentException("B2 endpoint must include a host");
        }
        Matcher matcher = B2_ENDPOINT.matcher(endpoint.getHost());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "B2 endpoint host must match s3.<region>.backblazeb2.com"
            );
        }
        return Region.of(matcher.group(1));
    }

    public String objectKey(String imageId) {
        if (imageId == null || imageId.isBlank()) {
            throw new IllegalArgumentException("imageId must not be blank");
        }
        String filename = imageId.endsWith(".png") ? imageId : imageId + ".png";
        return objectPrefix.isEmpty() ? filename : objectPrefix + "/" + filename;
    }

    @AssertTrue(message = "B2 endpoint must use HTTPS")
    public boolean isHttpsEndpoint() {
        return !enabled || endpoint != null && "https".equalsIgnoreCase(endpoint.getScheme());
    }

    @AssertTrue(message = "B2 endpoint must be a Backblaze S3 endpoint")
    public boolean isBackblazeEndpoint() {
        return !enabled || endpoint != null
                && endpoint.getHost() != null
                && B2_ENDPOINT.matcher(endpoint.getHost()).matches();
    }

    @AssertTrue(message = "B2 endpoint must not include userinfo, query, fragment, or a non-root path")
    public boolean isEndpointUriValid() {
        if (!enabled || endpoint == null) {
            return !enabled;
        }
        String path = endpoint.getPath();
        return endpoint.getRawUserInfo() == null
                && endpoint.getRawQuery() == null
                && endpoint.getRawFragment() == null
                && (path == null || path.isEmpty() || "/".equals(path));
    }

    @AssertTrue(message = "B2 bucket and credentials are required when B2 is enabled")
    public boolean isEnabledConfigurationComplete() {
        return !enabled
                || hasText(bucket)
                && hasText(accessKeyId)
                && hasText(secretAccessKey)
                && hasText(objectPrefix);
    }

    @AssertTrue(message = "B2 pre-signed URL TTL must be between one second and seven days")
    public boolean isPresignedUrlTtlValid() {
        return presignedUrlTtl != null
                && !presignedUrlTtl.isNegative()
                && !presignedUrlTtl.isZero()
                && presignedUrlTtl.compareTo(MAX_PRESIGNED_URL_TTL) <= 0;
    }

    @AssertTrue(message = "B2 upload and retention durations must be positive")
    public boolean areDurationsValid() {
        return isPositive(uploadDelay)
                && isPositive(uploadRetryDelay)
                && isPositive(localPreferredAge)
                && isPositive(metadataRetention)
                && isPositive(connectTimeout)
                && isPositive(socketTimeout)
                && isPositive(apiCallAttemptTimeout)
                && isPositive(apiCallTimeout);
    }

    @AssertTrue(message = "B2 API timeout ordering is invalid")
    public boolean isApiTimeoutsValid() {
        return isPositive(connectTimeout)
                && isPositive(socketTimeout)
                && isPositive(apiCallAttemptTimeout)
                && isPositive(apiCallTimeout)
                && connectTimeout.compareTo(apiCallAttemptTimeout) <= 0
                && socketTimeout.compareTo(apiCallAttemptTimeout) <= 0
                && apiCallAttemptTimeout.compareTo(apiCallTimeout) <= 0;
    }

    @AssertTrue(message = "B2 metadata retention must be exactly 21 days")
    public boolean isMetadataRetentionValid() {
        return REQUIRED_METADATA_RETENTION.equals(metadataRetention);
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String normalized = prefix.trim();
        int start = 0;
        int end = normalized.length();
        while (start < end && normalized.charAt(start) == '/') {
            start++;
        }
        while (end > start && normalized.charAt(end - 1) == '/') {
            end--;
        }
        return normalized.substring(start, end);
    }

    @Override
    public String toString() {
        return "B2StorageProperties["
                + "enabled=" + enabled
                + ", endpoint=" + endpointForLogging()
                + ", bucket=" + bucket
                + ", accessKeyId=[REDACTED]"
                + ", secretAccessKey=[REDACTED]"
                + ", objectPrefix=" + objectPrefix
                + ", uploadBatchSize=" + uploadBatchSize
                + ", uploadConcurrency=" + uploadConcurrency
                + ", uploadDelay=" + uploadDelay
                + ", uploadRetryDelay=" + uploadRetryDelay
                + ", localPreferredAge=" + localPreferredAge
                + ", presignedUrlTtl=" + presignedUrlTtl
                + ", metadataRetention=" + metadataRetention
                + ", connectTimeout=" + connectTimeout
                + ", socketTimeout=" + socketTimeout
                + ", apiCallAttemptTimeout=" + apiCallAttemptTimeout
                + ", apiCallTimeout=" + apiCallTimeout
                + ", maxAttempts=" + maxAttempts
                + ']';
    }

    private String endpointForLogging() {
        if (endpoint == null || endpoint.getRawUserInfo() == null) {
            return String.valueOf(endpoint);
        }
        return endpoint.toString().replace(endpoint.getRawUserInfo() + "@", "");
    }
}
