package com.introlabsystems.recognitionvalidator.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit-only real B2 transport benchmark. It is intentionally named *IT so
 * Maven's normal test phase does not run it. Enable with -Db2.benchmark.enabled=true.
 */
class B2TransportBenchmarkIT {

    private static final int DEFAULT_FILE_COUNT = 10;
    private static final int MAX_FILE_COUNT = 500;
    private static final int DEFAULT_CONCURRENCY = 8;
    private static final int MAX_CONCURRENCY = 128;
    private static final int SOURCE_SIZE_BYTES = 1_474_560;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration API_CALL_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_ATTEMPTS = 4;

    @TempDir
    Path tempDir;

    @Test
    void comparesBoundedB2TransportsAndCleansEveryVersion() throws Exception {
        Assumptions.assumeTrue(
                Boolean.parseBoolean(System.getProperty("b2.benchmark.enabled", "false")),
                "B2 benchmark disabled; use -Db2.benchmark.enabled=true explicitly"
        );

        BenchmarkSettings settings = BenchmarkSettings.fromEnvironment();
        Path source = createSourceFile();
        String runPrefix = settings.objectPrefix()
                + "/benchmark/"
                + Instant.now().toEpochMilli()
                + "-"
                + UUID.randomUUID();

        S3Client syncClient = createSyncClient(settings);
        S3AsyncClient asyncClient = createAsyncClient(settings);
        S3TransferManager transferManager = S3TransferManager.builder()
                .s3Client(asyncClient)
                .build();

        try {
            runTransport(
                    "sync",
                    settings,
                    runPrefix + "/sync",
                    source,
                    syncClient,
                    null,
                    null
            );
            runTransport(
                    "async",
                    settings,
                    runPrefix + "/async",
                    source,
                    syncClient,
                    asyncClient,
                    null
            );
            runTransport(
                    "transfer-manager",
                    settings,
                    runPrefix + "/transfer-manager",
                    source,
                    syncClient,
                    asyncClient,
                    transferManager
            );
        } finally {
            transferManager.close();
            asyncClient.close();
            syncClient.close();
            deleteRecursively(tempDir);
        }
    }

    private void runTransport(
            String transport,
            BenchmarkSettings settings,
            String prefix,
            Path source,
            S3Client syncClient,
            S3AsyncClient asyncClient,
            S3TransferManager transferManager
    ) throws Exception {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(settings.bucket())
                .contentType("image/png")
                .build();
        ExecutorService executor = Executors.newFixedThreadPool(settings.concurrency());
        long started = System.nanoTime();
        try {
            List<Future<UploadObservation>> uploads = new ArrayList<>(settings.fileCount());
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger peakInFlight = new AtomicInteger();
            for (int index = 0; index < settings.fileCount(); index++) {
                String key = prefix + "/" + index + ".png";
                uploads.add(executor.submit(() -> measure(
                        () -> upload(transport, request.toBuilder().key(key).build(), source,
                                syncClient, asyncClient, transferManager),
                        inFlight,
                        peakInFlight
                )));
            }
            List<UploadObservation> observations = new ArrayList<>(uploads.size());
            for (Future<UploadObservation> upload : uploads) {
                observations.add(upload.get());
            }
            BenchmarkMetrics metrics = BenchmarkMetrics.from(
                    transport,
                    observations,
                    System.nanoTime() - started,
                    peakInFlight.get()
            );
            System.out.println(metrics);
            assertThat(metrics.successes() + metrics.failures()).isEqualTo(settings.fileCount());
        } finally {
            executor.shutdownNow();
            cleanupVersions(syncClient, settings.bucket(), prefix);
        }
    }

    private static void upload(
            String transport,
            PutObjectRequest request,
            Path source,
            S3Client syncClient,
            S3AsyncClient asyncClient,
            S3TransferManager transferManager
    ) {
        switch (transport) {
            case "sync" -> syncClient.putObject(request, RequestBody.fromFile(source));
            case "async" -> asyncClient.putObject(request, AsyncRequestBody.fromFile(source)).join();
            case "transfer-manager" -> transferManager.uploadFile(
                    UploadFileRequest.builder()
                            .putObjectRequest(request)
                            .source(source)
                            .build()
            ).completionFuture().join();
            default -> throw new IllegalArgumentException("Unknown benchmark transport: " + transport);
        }
    }

    private static UploadObservation measure(
            Runnable operation,
            AtomicInteger inFlight,
            AtomicInteger peakInFlight
    ) {
        int active = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(active, Math::max);
        long started = System.nanoTime();
        try {
            operation.run();
            return UploadObservation.success(System.nanoTime() - started);
        } catch (Exception exception) {
            return UploadObservation.failure(System.nanoTime() - started);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private Path createSourceFile() throws IOException {
        Path source = tempDir.resolve("benchmark-source.png");
        byte[] bytes = new byte[SOURCE_SIZE_BYTES];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 31);
        }
        Files.write(source, bytes);
        return source;
    }

    private static S3Client createSyncClient(BenchmarkSettings settings) {
        return S3Client.builder()
                .region(settings.region())
                .endpointOverride(settings.endpoint())
                .credentialsProvider(settings.credentials())
                .serviceConfiguration(pathStyleConfiguration())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(CONNECT_TIMEOUT)
                        .socketTimeout(SOCKET_TIMEOUT))
                .overrideConfiguration(clientOverrideConfiguration())
                .build();
    }

    private static S3AsyncClient createAsyncClient(BenchmarkSettings settings) {
        return S3AsyncClient.builder()
                .region(settings.region())
                .endpointOverride(settings.endpoint())
                .credentialsProvider(settings.credentials())
                .serviceConfiguration(pathStyleConfiguration())
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(settings.concurrency())
                        .connectionTimeout(CONNECT_TIMEOUT)
                        .readTimeout(SOCKET_TIMEOUT)
                        .writeTimeout(SOCKET_TIMEOUT))
                .overrideConfiguration(clientOverrideConfiguration())
                .build();
    }

    private static ClientOverrideConfiguration clientOverrideConfiguration() {
        return ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                .apiCallTimeout(API_CALL_TIMEOUT)
                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(MAX_ATTEMPTS).build())
                .build();
    }

    private static S3Configuration pathStyleConfiguration() {
        return S3Configuration.builder().pathStyleAccessEnabled(true).build();
    }

    private static void cleanupVersions(S3Client client, String bucket, String prefix) {
        List<ObjectIdentifier> objects = new ArrayList<>();
        String keyMarker = null;
        String versionIdMarker = null;
        do {
            ListObjectVersionsResponse response = client.listObjectVersions(
                    ListObjectVersionsRequest.builder()
                            .bucket(bucket)
                            .prefix(prefix + "/")
                            .keyMarker(keyMarker)
                            .versionIdMarker(versionIdMarker)
                            .build()
            );
            response.versions().forEach(version -> objects.add(ObjectIdentifier.builder()
                    .key(version.key())
                    .versionId(version.versionId())
                    .build()));
            response.deleteMarkers().forEach(marker -> objects.add(ObjectIdentifier.builder()
                    .key(marker.key())
                    .versionId(marker.versionId())
                    .build()));
            if (!response.isTruncated()) {
                break;
            }
            keyMarker = response.nextKeyMarker();
            versionIdMarker = response.nextVersionIdMarker();
        } while (true);

        for (int start = 0; start < objects.size(); start += 1000) {
            List<ObjectIdentifier> batch = objects.subList(start, Math.min(start + 1000, objects.size()));
            var response = client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(delete -> delete.objects(batch))
                    .build());
            var errors = response.errors();
            if (!errors.isEmpty()) {
                throw new IllegalStateException("B2 benchmark cleanup returned delete errors: " + errors.size());
            }
        }

        ListObjectVersionsResponse remaining = client.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(bucket).prefix(prefix + "/").build()
        );
        if (!remaining.versions().isEmpty() || !remaining.deleteMarkers().isEmpty()
                || remaining.isTruncated()) {
            throw new IllegalStateException("B2 benchmark cleanup could not confirm zero versions");
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Cannot remove benchmark temp path", exception);
                }
            });
        }
    }

    private record BenchmarkSettings(
            URI endpoint,
            String bucket,
            String objectPrefix,
            Region region,
            StaticCredentialsProvider credentials,
            int fileCount,
            int concurrency
    ) {
        private static BenchmarkSettings fromEnvironment() {
            String endpointValue = requiredEnvironment("B2_ENDPOINT");
            String bucket = requiredEnvironment("B2_BUCKET");
            String accessKeyId = requiredEnvironment("B2_ACCESS_KEY_ID");
            String secretAccessKey = requiredEnvironment("B2_SECRET_ACCESS_KEY");
            String prefix = normalizePrefix(System.getenv().getOrDefault("B2_OBJECT_PREFIX", "validator/"));
            URI endpoint = URI.create(endpointValue);
            String host = endpoint.getHost();
            if (host == null || !host.toLowerCase(Locale.ROOT).startsWith("s3.")) {
                throw new IllegalArgumentException("B2_ENDPOINT must be a Backblaze S3 endpoint");
            }
            String[] hostParts = host.split("\\.");
            if (hostParts.length < 4) {
                throw new IllegalArgumentException("B2_ENDPOINT must include a region");
            }
            int fileCount = Integer.getInteger("b2.benchmark.count", DEFAULT_FILE_COUNT);
            int concurrency = Integer.getInteger("b2.benchmark.concurrency", DEFAULT_CONCURRENCY);
            if (fileCount < 1 || fileCount > MAX_FILE_COUNT) {
                throw new IllegalArgumentException("b2.benchmark.count must be between 1 and 500");
            }
            if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
                throw new IllegalArgumentException("b2.benchmark.concurrency must be between 1 and 128");
            }
            return new BenchmarkSettings(
                    endpoint,
                    bucket,
                    prefix,
                    Region.of(hostParts[1]),
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)),
                    fileCount,
                    concurrency
            );
        }

        private static String requiredEnvironment(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " must be exported for the explicit B2 benchmark");
            }
            return value;
        }

        private static String normalizePrefix(String prefix) {
            String normalized = prefix == null ? "" : prefix.trim();
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        }
    }

    private record UploadObservation(boolean success, long latencyNanos) {
        private static UploadObservation success(long latencyNanos) {
            return new UploadObservation(true, latencyNanos);
        }

        private static UploadObservation failure(long latencyNanos) {
            return new UploadObservation(false, latencyNanos);
        }
    }

    private record BenchmarkMetrics(
            String transport,
            int count,
            long totalBytes,
            long elapsedNanos,
            int successes,
            int failures,
            int peakInFlight,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos
    ) {
        private static BenchmarkMetrics from(
                String transport,
                List<UploadObservation> observations,
                long elapsedNanos,
                int peakInFlight
        ) {
            List<Long> latencies = observations.stream()
                    .map(UploadObservation::latencyNanos)
                    .sorted()
                    .toList();
            return new BenchmarkMetrics(
                    transport,
                    observations.size(),
                    (long) observations.size() * SOURCE_SIZE_BYTES,
                    elapsedNanos,
                    (int) observations.stream().filter(UploadObservation::success).count(),
                    (int) observations.stream().filter(observation -> !observation.success()).count(),
                    peakInFlight,
                    percentile(latencies, 0.50),
                    percentile(latencies, 0.95),
                    percentile(latencies, 0.99)
            );
        }

        private static long percentile(List<Long> values, double percentile) {
            if (values.isEmpty()) {
                return 0;
            }
            int index = Math.max(0, (int) Math.ceil(values.size() * percentile) - 1);
            return values.get(index);
        }

        @Override
        public String toString() {
            double seconds = elapsedNanos / 1_000_000_000.0;
            double filesPerSecond = count / seconds;
            double mebibytesPerSecond = totalBytes / 1024.0 / 1024.0 / seconds;
            return "B2 benchmark " + transport
                    + ": count=" + count
                    + ", bytes=" + totalBytes
                    + ", elapsedSeconds=" + String.format(Locale.ROOT, "%.3f", seconds)
                    + ", filesPerSecond=" + String.format(Locale.ROOT, "%.3f", filesPerSecond)
                    + ", MiBPerSecond=" + String.format(Locale.ROOT, "%.3f", mebibytesPerSecond)
                    + ", p50Ms=" + nanosToMillis(p50Nanos)
                    + ", p95Ms=" + nanosToMillis(p95Nanos)
                    + ", p99Ms=" + nanosToMillis(p99Nanos)
                    + ", successes=" + successes
                    + ", failures=" + failures
                    + ", peakInFlight=" + peakInFlight;
        }

        private static long nanosToMillis(long nanos) {
            return nanos / 1_000_000;
        }
    }
}
