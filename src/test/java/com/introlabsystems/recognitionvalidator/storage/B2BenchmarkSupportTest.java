package com.introlabsystems.recognitionvalidator.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class B2BenchmarkSupportTest {

    @Test
    void rejectsUnknownEmptyDuplicateAndMalformedSelectionValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> B2BenchmarkSupport.parseTransports("sync,unknown"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> B2BenchmarkSupport.parseTransports(""));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> B2BenchmarkSupport.parseTransports("sync,sync"));

        Properties properties = new Properties();
        properties.setProperty("b2.benchmark.count", "ten");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> B2BenchmarkSupport.optionsFrom(properties));
    }

    @Test
    void keepsRepeatOrderDeterministicAndRotatesSelectedTransports() {
        List<String> selected = List.of("sync", "async", "transfer-manager", "apache", "crt");

        List<String> first = B2BenchmarkSupport.orderFor(selected, 1, 8128L);
        List<String> firstAgain = B2BenchmarkSupport.orderFor(selected, 1, 8128L);
        List<String> second = B2BenchmarkSupport.orderFor(selected, 2, 8128L);

        assertThat(firstAgain).isEqualTo(first);
        assertThat(second).containsExactlyInAnyOrderElementsOf(selected);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void countsOnlySuccessfulPayloadsInSummary() {
        List<B2BenchmarkSupport.UploadObservation> observations = List.of(
                B2BenchmarkSupport.UploadObservation.success(10),
                B2BenchmarkSupport.UploadObservation.failure(20, "S3:503"),
                B2BenchmarkSupport.UploadObservation.success(30)
        );

        B2BenchmarkSupport.BenchmarkMetrics metrics = B2BenchmarkSupport.metrics(
                "sync", observations, 1_000_000_000L, 2, 100L,
                B2BenchmarkSupport.RetryMetrics.observed(3, 4)
        );

        assertThat(metrics.successes()).isEqualTo(2);
        assertThat(metrics.failures()).isEqualTo(1);
        assertThat(metrics.totalBytes()).isEqualTo(200L);
        assertThat(metrics.filesPerSecond()).isEqualTo(2.0);
        assertThat(metrics.failureCategories()).containsEntry("S3:503", 1);
    }

    @Test
    void derivesRetryAttemptsFromHttpAttemptsWithoutLoggingRequests() {
        B2BenchmarkSupport.RetryMetrics retryMetrics =
                B2BenchmarkSupport.RetryMetrics.observedPerCall(List.of(0, 3, 1));

        assertThat(retryMetrics.httpAttempts()).isEqualTo(4);
        assertThat(retryMetrics.retryAttempts()).isEqualTo(2);
        assertThat(retryMetrics.available()).isTrue();
        assertThat(B2BenchmarkSupport.RetryMetrics.unavailable().available()).isFalse();
    }

    @Test
    void observerCountsRetriesPerCallEvenWhenExecutionAttributesChange() {
        B2BenchmarkSupport.AttemptTracker tracker = new B2BenchmarkSupport.AttemptTracker();
        B2BenchmarkSupport.AttemptTracker otherTransport = new B2BenchmarkSupport.AttemptTracker();
        ExecutionAttributes firstCall = new ExecutionAttributes();
        ExecutionAttributes secondCall = new ExecutionAttributes();
        tracker.begin();
        tracker.beforeExecution(null, firstCall);
        tracker.beforeTransmission(null, firstCall);
        tracker.beforeTransmission(null, firstCall);
        firstCall.putAttribute(new ExecutionAttribute<String>("unrelated"), "changed");
        tracker.onExecutionFailure(null, firstCall);
        tracker.beforeExecution(null, secondCall);
        tracker.beforeTransmission(null, secondCall);
        tracker.afterExecution(null, secondCall);

        B2BenchmarkSupport.RetryMetrics metrics = tracker.end(2);
        assertThat(metrics.httpAttempts()).isEqualTo(3);
        assertThat(metrics.retryAttempts()).isEqualTo(1);
        assertThat(otherTransport.end(0).httpAttempts()).isZero();
    }

    @Test
    void averagesTheTwoCentralRatesForAnEvenRepetitionSummary() {
        B2BenchmarkSupport.BenchmarkMetrics slow = B2BenchmarkSupport.metrics(
                "sync", List.of(B2BenchmarkSupport.UploadObservation.success(1)),
                1_000_000_000L, 1, 1L, B2BenchmarkSupport.RetryMetrics.observed(1, 1));
        B2BenchmarkSupport.BenchmarkMetrics fast = B2BenchmarkSupport.metrics(
                "sync", List.of(B2BenchmarkSupport.UploadObservation.success(1)),
                500_000_000L, 1, 1L, B2BenchmarkSupport.RetryMetrics.observed(1, 1));

        assertThat(B2BenchmarkSupport.summary("sync", List.of(slow, fast))
                .medianFilesPerSecond()).isEqualTo(1.5);
    }

    @Test
    void preflightCollisionFailsWithoutDeletingExistingVersions() {
        String prefix = "validator/benchmark/collision";
        S3Client client = mock(S3Client.class);
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder()
                        .versions(ObjectVersion.builder()
                                .key(prefix + "/already-there.png")
                                .versionId("v1")
                                .build())
                        .isTruncated(false)
                        .build());

        assertThatThrownBy(() -> B2TransportBenchmarkIT.ensurePrefixEmpty(client, "bucket", prefix))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(prefix)
                .hasMessageContaining("no objects were deleted")
                .hasMessageNotContaining("deleting every version");
        verify(client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void wrapsCleanupBoundaryErrorsWithPrefixAndCause() {
        String prefix = "validator/benchmark/cleanup-error";
        RuntimeException networkFailure = new RuntimeException("network failure");
        S3Client client = mock(S3Client.class);
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenThrow(networkFailure);

        assertThatThrownBy(() -> B2TransportBenchmarkIT.cleanupVersions(client, "bucket", prefix))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(prefix)
                .hasCause(networkFailure);
    }
}
