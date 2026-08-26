package com.introlabsystems.recognitionvalidator.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.S3CrtAsyncClientBuilder;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit-only real B2 transport benchmark. It is intentionally named *IT so
 * Maven's normal test phase does not run it. Enable with -Db2.benchmark.enabled=true.
 */
class B2TransportBenchmarkIT {

    private static final int SOURCE_SIZE_BYTES = 1_474_560;
    private static final int WARMUP_UPLOAD_COUNT = 1;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration API_CALL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration UPLOAD_WAIT_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration MAX_AGGREGATE_UPLOAD_WAIT = Duration.ofMinutes(15);
    private static final Duration WORKER_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ATTEMPTS = 4;
    private static final long CRT_NATIVE_MEMORY_LIMIT_BYTES = 1L << 30;

    @TempDir
    Path tempDir;

    @Test
    void comparesBoundedB2TransportsAndCleansEveryVersion() throws Exception {
        Assumptions.assumeTrue(
                Boolean.parseBoolean(System.getProperty("b2.benchmark.enabled", "false")),
                "B2 benchmark disabled; use -Db2.benchmark.enabled=true explicitly"
        );

        B2BenchmarkSupport.BenchmarkOptions options =
                B2BenchmarkSupport.optionsFrom(System.getProperties());
        BenchmarkSettings settings = BenchmarkSettings.fromEnvironment(options);
        Path source = createSourceFile();
        String runPrefix = settings.objectPrefix()
                + "/benchmark/"
                + Instant.now().toEpochMilli()
                + "-"
                + UUID.randomUUID();
        long nominalMeasuredPayload = Math.multiplyExact(
                options.fileCount(), (long) SOURCE_SIZE_BYTES);
        long logicalUploadBudget = Math.multiplyExact(
                Math.multiplyExact(
                        (long) options.repetitions(), options.transports().size()),
                WARMUP_UPLOAD_COUNT + (long) options.fileCount());
        long totalPayloadBudget = Math.multiplyExact(logicalUploadBudget, SOURCE_SIZE_BYTES);
        System.out.println("B2 benchmark selection: transports=" + options.transports()
                + ", repetitions=" + options.repetitions()
                + ", seed=" + options.seed()
                + ", count=" + options.fileCount()
                + ", concurrency=" + options.concurrency()
                + ", warmupCount=" + WARMUP_UPLOAD_COUNT
                + ", aggregateUploadWaitCeilingSeconds=" + MAX_AGGREGATE_UPLOAD_WAIT.toSeconds());
        System.out.println("B2 benchmark budget: nominalPeakObjectPayloadBytes="
                + Math.multiplyExact(WARMUP_UPLOAD_COUNT + (long) options.fileCount(), SOURCE_SIZE_BYTES)
                + ", nominalMeasuredPayloadBytesPerRun=" + nominalMeasuredPayload
                + ", totalLogicalUploadBudget=" + logicalUploadBudget
                + ", totalPayloadBudgetBytes=" + totalPayloadBudget);
        if (options.transports().contains("transfer-manager")) {
            System.out.println("B2 benchmark transfer-manager: Netty-backed S3AsyncClient");
        }
        if (options.transports().contains("apache")) {
            System.out.println("B2 benchmark apache: ApacheHttpClient maxConnections="
                    + options.concurrency());
        }
        if (options.transports().contains("crt")) {
            System.out.println("B2 benchmark crt: AWS CRT S3 client, forcePathStyle=true, maxConcurrency="
                    + options.concurrency()
                    + ", maxNativeMemoryLimitBytes=" + CRT_NATIVE_MEMORY_LIMIT_BYTES
                    + ", retries=" + (MAX_ATTEMPTS - 1)
                    + "; API-attempt timeout and HTTP attempt/retry metrics are unavailable");
        }

        Map<String, List<B2BenchmarkSupport.BenchmarkMetrics>> metricsByTransport = new TreeMap<>();
        try (BenchmarkClients clients = BenchmarkClients.create(settings, options.transports())) {
            for (int repetition = 1; repetition <= options.repetitions(); repetition++) {
                List<String> order = B2BenchmarkSupport.orderFor(
                        options.transports(), repetition, options.seed());
                System.out.println("B2 benchmark repetition=" + repetition + " order=" + order);
                for (String transport : order) {
                    String prefix = runPrefix + "/r" + repetition + "/" + transport + "/" + UUID.randomUUID();
                    B2BenchmarkSupport.BenchmarkMetrics metrics = runTransport(
                            transport, repetition, settings, prefix, source, clients);
                    metricsByTransport.computeIfAbsent(transport, ignored -> new ArrayList<>())
                            .add(metrics);
                }
            }
        } finally {
            deleteRecursively(tempDir);
        }

        metricsByTransport.forEach((transport, metrics) -> {
            B2BenchmarkSupport.TransportSummary summary =
                    B2BenchmarkSupport.summary(transport, metrics);
            System.out.println("B2 benchmark summary " + transport
                    + ": repetitions=" + summary.repetitions()
                    + ", medianFilesPerSecond=" + format(summary.medianFilesPerSecond())
                    + ", rangeFilesPerSecond=[" + format(summary.minFilesPerSecond())
                    + "," + format(summary.maxFilesPerSecond()) + "]");
        });
    }

    private B2BenchmarkSupport.BenchmarkMetrics runTransport(
            String transport,
            int repetition,
            BenchmarkSettings settings,
            String prefix,
            Path source,
            BenchmarkClients clients
    ) throws Exception {
        System.out.println("B2 benchmark run prefix (repetition=" + repetition
                + ", transport=" + transport + "): " + prefix);
        System.out.println("B2 benchmark run budget: nominalPeakObjectPayloadBytes="
                + Math.multiplyExact(WARMUP_UPLOAD_COUNT + (long) settings.fileCount(), SOURCE_SIZE_BYTES)
                + ", logicalUploadBudget=" + (WARMUP_UPLOAD_COUNT + settings.fileCount())
                + ", warmupCount=" + WARMUP_UPLOAD_COUNT);
        // A collision must not make the benchmark delete someone else's objects.
        ensurePrefixEmpty(clients.cleanupClient, settings.bucket(), prefix);

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(settings.bucket())
                .contentType("image/png")
                .contentLength((long) SOURCE_SIZE_BYTES)
                .build();
        ExecutorService executor = Executors.newFixedThreadPool(settings.concurrency());
        List<Future<B2BenchmarkSupport.UploadObservation>> uploads = new ArrayList<>(settings.fileCount());
        B2BenchmarkSupport.AttemptTracker attemptCounter = clients.attemptCounter(transport);
        AtomicBoolean asyncCompletionUnconfirmed = new AtomicBoolean();
        boolean captureStarted = false;
        boolean safeCleanup = true;
        try {
            for (int index = 0; index < WARMUP_UPLOAD_COUNT; index++) {
                upload(transport, request.toBuilder().key(prefix + "/warmup-" + index + ".png").build(),
                        source, clients, asyncCompletionUnconfirmed);
            }

            if (attemptCounter != null) {
                attemptCounter.begin();
                captureStarted = true;
            }
            long started = System.nanoTime();
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger peakInFlight = new AtomicInteger();
            for (int index = 0; index < settings.fileCount(); index++) {
                String key = prefix + "/" + index + ".png";
                uploads.add(executor.submit(() -> measure(
                        () -> upload(transport, request.toBuilder().key(key).build(), source, clients,
                                asyncCompletionUnconfirmed),
                        inFlight,
                        peakInFlight
                )));
            }
            List<B2BenchmarkSupport.UploadObservation> observations = collectUploads(
                    uploads, prefix, settings.concurrency());
            B2BenchmarkSupport.RetryMetrics retryMetrics = attemptCounter == null
                    ? B2BenchmarkSupport.RetryMetrics.unavailable()
                    : attemptCounter.end(observations.size());
            captureStarted = false;
            B2BenchmarkSupport.BenchmarkMetrics metrics = B2BenchmarkSupport.metrics(
                    transport,
                    observations,
                    System.nanoTime() - started,
                    peakInFlight.get(),
                    SOURCE_SIZE_BYTES,
                    retryMetrics
            );
            System.out.println(metrics);
            VersionInventory inventory = inspectVersions(clients.cleanupClient, settings.bucket(), prefix);
            System.out.println("B2 benchmark " + transport
                    + ": versions=" + inventory.versionCount()
                    + ", objects=" + inventory.objectCount()
                    + ", versionsPerObject=" + String.format(
                    Locale.ROOT, "%.3f", inventory.versionsPerObject())
                    + ", deleteMarkers=" + inventory.deleteMarkerCount());
            Set<String> expectedKeys = new HashSet<>();
            for (int index = 0; index < WARMUP_UPLOAD_COUNT; index++) {
                expectedKeys.add(prefix + "/warmup-" + index + ".png");
            }
            for (int index = 0; index < settings.fileCount(); index++) {
                expectedKeys.add(prefix + "/" + index + ".png");
            }
            assertThat(metrics.successes() + metrics.failures()).isEqualTo(settings.fileCount());
            assertThat(metrics.failures()).isZero();
            assertThat(metrics.successes()).isEqualTo(settings.fileCount());
            assertThat(inventory.objectCount()).isEqualTo(settings.fileCount() + WARMUP_UPLOAD_COUNT);
            assertThat(inventory.versionCount()).isEqualTo(settings.fileCount() + WARMUP_UPLOAD_COUNT);
            assertThat(inventory.deleteMarkerCount()).isZero();
            assertThat(inventory.versionsByKey()).containsOnlyKeys(expectedKeys);
            assertThat(inventory.versionsByKey().values()).allMatch(count -> count == 1);
            return metrics;
        } catch (UnsafeCleanupException exception) {
            safeCleanup = false;
            throw exception;
        } finally {
            if (captureStarted && attemptCounter != null) {
                attemptCounter.stop();
            }
            try {
                awaitWorkers(executor, uploads, prefix);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new UnsafeCleanupException(prefix, "upload worker shutdown was interrupted");
            }
            if (asyncCompletionUnconfirmed.get()) {
                safeCleanup = false;
            }
            if (safeCleanup) {
                cleanupVersions(clients.cleanupClient, settings.bucket(), prefix);
            } else {
                throw new UnsafeCleanupException(prefix,
                        "an interrupted or timed-out async PUT may still be in flight");
            }
        }
    }

    private static List<B2BenchmarkSupport.UploadObservation> collectUploads(
            List<Future<B2BenchmarkSupport.UploadObservation>> uploads,
            String prefix,
            int concurrency
    ) {
        long rounds = (uploads.size() + concurrency - 1L) / concurrency;
        long aggregateWait = Math.min(
                MAX_AGGREGATE_UPLOAD_WAIT.toNanos(),
                Math.multiplyExact(rounds, UPLOAD_WAIT_TIMEOUT.toNanos()));
        long deadline = System.nanoTime() + aggregateWait;
        List<B2BenchmarkSupport.UploadObservation> observations = new ArrayList<>(uploads.size());
        for (Future<B2BenchmarkSupport.UploadObservation> upload : uploads) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                uploads.forEach(future -> future.cancel(true));
                throw new UnsafeCleanupException(prefix,
                        "bounded upload wait expired before all workers completed");
            }
            try {
                observations.add(upload.get(remaining, TimeUnit.NANOSECONDS));
            } catch (InterruptedException exception) {
                uploads.forEach(future -> future.cancel(true));
                Thread.currentThread().interrupt();
                throw new UnsafeCleanupException(prefix, "upload wait was interrupted");
            } catch (ExecutionException exception) {
                throw new UnsafeCleanupException(prefix, "upload worker failed");
            } catch (TimeoutException exception) {
                uploads.forEach(future -> future.cancel(true));
                throw new UnsafeCleanupException(prefix,
                        "bounded upload wait expired before all workers completed");
            }
        }
        return observations;
    }

    private static void awaitWorkers(
            ExecutorService executor,
            List<Future<B2BenchmarkSupport.UploadObservation>> uploads,
            String prefix
    ) throws InterruptedException {
        executor.shutdown();
        if (executor.awaitTermination(WORKER_SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            return;
        }
        uploads.forEach(future -> future.cancel(true));
        executor.shutdownNow();
        if (!executor.awaitTermination(WORKER_SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(
                    "B2 benchmark could not stop upload workers; cleanup skipped for prefix " + prefix
                            + ". Recover by listing and deleting every version and delete marker under that prefix.");
        }
    }

    private static void upload(
            String transport,
            PutObjectRequest request,
            Path source,
            BenchmarkClients clients,
            AtomicBoolean asyncCompletionUnconfirmed
    ) {
        switch (transport) {
            case "sync" -> clients.syncClient.putObject(request, RequestBody.fromFile(source));
            case "async" -> await(clients.asyncClient.putObject(request, AsyncRequestBody.fromFile(source)),
                    asyncCompletionUnconfirmed);
            case "transfer-manager" -> await(clients.transferManager.uploadFile(
                    UploadFileRequest.builder()
                            .putObjectRequest(request)
                            .source(source)
                            .build()
            ).completionFuture(), asyncCompletionUnconfirmed);
            case "apache" -> clients.apacheClient.putObject(request, RequestBody.fromFile(source));
            case "crt" -> await(clients.crtClient.putObject(request, AsyncRequestBody.fromFile(source)),
                    asyncCompletionUnconfirmed);
            default -> throw new IllegalArgumentException("Unknown benchmark transport: " + transport);
        }
    }

    private static <T> void await(CompletableFuture<T> future, AtomicBoolean asyncCompletionUnconfirmed) {
        try {
            future.get(UPLOAD_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            asyncCompletionUnconfirmed.set(true);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CompletionException(exception);
        } catch (ExecutionException | TimeoutException exception) {
            if (exception instanceof TimeoutException) {
                asyncCompletionUnconfirmed.set(true);
            }
            future.cancel(true);
            throw new CompletionException(exception);
        }
    }

    private static B2BenchmarkSupport.UploadObservation measure(
            Runnable operation,
            AtomicInteger inFlight,
            AtomicInteger peakInFlight
    ) {
        int active = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(active, Math::max);
        long started = System.nanoTime();
        try {
            operation.run();
            return B2BenchmarkSupport.UploadObservation.success(System.nanoTime() - started);
        } catch (Exception exception) {
            return B2BenchmarkSupport.UploadObservation.failure(
                    System.nanoTime() - started, classifyFailure(exception));
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

    private static S3Client createSyncClient(
            BenchmarkSettings settings, B2BenchmarkSupport.AttemptTracker counter) {
        return S3Client.builder()
                .region(settings.region())
                .endpointOverride(settings.endpoint())
                .credentialsProvider(settings.credentials())
                .serviceConfiguration(pathStyleConfiguration())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(CONNECT_TIMEOUT)
                        .socketTimeout(SOCKET_TIMEOUT))
                .overrideConfiguration(clientOverrideConfiguration(counter))
                .build();
    }

    private static S3Client createApacheClient(
            BenchmarkSettings settings, B2BenchmarkSupport.AttemptTracker counter) {
        return S3Client.builder()
                .region(settings.region())
                .endpointOverride(settings.endpoint())
                .credentialsProvider(settings.credentials())
                .serviceConfiguration(pathStyleConfiguration())
                .httpClientBuilder(ApacheHttpClient.builder()
                        .maxConnections(settings.concurrency())
                        .connectionTimeout(CONNECT_TIMEOUT)
                        .socketTimeout(SOCKET_TIMEOUT))
                .overrideConfiguration(clientOverrideConfiguration(counter))
                .build();
    }

    private static S3AsyncClient createAsyncClient(
            BenchmarkSettings settings, B2BenchmarkSupport.AttemptTracker counter) {
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
                .overrideConfiguration(clientOverrideConfiguration(counter))
                .build();
    }

    private static S3AsyncClient createCrtClient(BenchmarkSettings settings) {
        S3CrtAsyncClientBuilder builder = S3AsyncClient.crtBuilder()
                .region(settings.region())
                .endpointOverride(settings.endpoint())
                .credentialsProvider(settings.credentials())
                .forcePathStyle(true)
                .maxConcurrency(settings.concurrency())
                .maxNativeMemoryLimitInBytes(CRT_NATIVE_MEMORY_LIMIT_BYTES)
                .httpConfiguration(http -> http.connectionTimeout(CONNECT_TIMEOUT))
                .retryConfiguration(retry -> retry.numRetries(MAX_ATTEMPTS - 1));
        return builder.build();
    }

    private static S3TransferManager createTransferManager(S3AsyncClient asyncClient) {
        return S3TransferManager.builder().s3Client(asyncClient).build();
    }

    private static ClientOverrideConfiguration clientOverrideConfiguration(
            B2BenchmarkSupport.AttemptTracker counter) {
        ClientOverrideConfiguration.Builder builder = ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                .apiCallTimeout(API_CALL_TIMEOUT)
                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(MAX_ATTEMPTS).build());
        if (counter != null) {
            builder.addExecutionInterceptor(counter);
        }
        return builder.build();
    }

    private static S3Configuration pathStyleConfiguration() {
        return S3Configuration.builder().pathStyleAccessEnabled(true).build();
    }

    private static String classifyFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException || cause instanceof ExecutionException) {
            if (cause.getCause() == null) {
                break;
            }
            cause = cause.getCause();
        }
        if (cause instanceof S3Exception serviceException) {
            return "S3:" + serviceException.statusCode();
        }
        if (cause instanceof SdkClientException) {
            return "SdkClientException";
        }
        return cause.getClass().getSimpleName();
    }

    static void cleanupVersions(S3Client client, String bucket, String prefix) {
        try {
            List<ObjectIdentifier> objects = listVersionRecords(client, bucket, prefix).stream()
                    .map(record -> ObjectIdentifier.builder()
                            .key(record.key())
                            .versionId(record.versionId())
                            .build())
                    .toList();

            for (int start = 0; start < objects.size(); start += 1000) {
                List<ObjectIdentifier> batch = objects.subList(start, Math.min(start + 1000, objects.size()));
                var response = client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(delete -> delete.objects(batch))
                        .build());
                if (!response.errors().isEmpty()) {
                    throw cleanupFailure(prefix, "delete returned errors");
                }
            }

            if (!listVersionRecords(client, bucket, prefix).isEmpty()) {
                throw cleanupFailure(prefix, "could not confirm zero versions and delete markers");
            }
        } catch (CleanupFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw cleanupFailure(prefix, "SDK list/delete request failed", exception);
        }
        System.out.println("B2 benchmark cleanup confirmed zero versions and delete markers: prefix=" + prefix);
    }

    static void ensurePrefixEmpty(S3Client client, String bucket, String prefix) {
        final boolean empty;
        try {
            empty = listVersionRecords(client, bucket, prefix).isEmpty();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("B2 benchmark preflight failed for prefix " + prefix
                    + "; no objects were uploaded or deleted. Check access before retrying.", exception);
        }
        if (!empty) {
            throw new IllegalStateException("B2 benchmark prefix was not empty before the run: " + prefix
                    + "; no objects were deleted. Leave existing objects untouched and use a new run prefix.");
        }
        System.out.println("B2 benchmark preflight confirmed zero versions and delete markers: prefix="
                + prefix);
    }

    private static CleanupFailureException cleanupFailure(String prefix, String reason) {
        return new CleanupFailureException(prefix, reason);
    }

    private static CleanupFailureException cleanupFailure(
            String prefix, String reason, RuntimeException cause) {
        return new CleanupFailureException(prefix, reason, cause);
    }

    private static final class CleanupFailureException extends IllegalStateException {
        private CleanupFailureException(String prefix, String reason) {
            super("B2 benchmark cleanup failed for prefix " + prefix + ": " + reason
                    + ". Recover by listing and deleting every version and delete marker under that prefix.");
        }

        private CleanupFailureException(String prefix, String reason, RuntimeException cause) {
            super("B2 benchmark cleanup failed for prefix " + prefix + ": " + reason
                    + ". Recover by listing and deleting every version and delete marker under that prefix.", cause);
        }
    }

    private static final class UnsafeCleanupException extends IllegalStateException {
        private UnsafeCleanupException(String prefix, String reason) {
            super("B2 benchmark cannot confirm safe cleanup for prefix " + prefix + ": " + reason
                    + ". Close the benchmark clients, then recover by listing and deleting every version "
                    + "and delete marker under that prefix.");
        }
    }

    private static VersionInventory inspectVersions(S3Client client, String bucket, String prefix) {
        return VersionInventory.from(listVersionRecords(client, bucket, prefix));
    }

    private static List<VersionRecord> listVersionRecords(S3Client client, String bucket, String prefix) {
        List<VersionRecord> records = new ArrayList<>();
        String keyMarker = null;
        String versionIdMarker = null;
        do {
            var builder = ListObjectVersionsRequest.builder()
                    .bucket(bucket)
                    .prefix(prefix + "/");
            if (keyMarker != null) {
                builder.keyMarker(keyMarker);
            }
            if (versionIdMarker != null) {
                builder.versionIdMarker(versionIdMarker);
            }
            ListObjectVersionsResponse response = client.listObjectVersions(builder.build());
            response.versions().forEach(version -> records.add(
                    new VersionRecord(version.key(), version.versionId(), false)));
            response.deleteMarkers().forEach(marker -> records.add(
                    new VersionRecord(marker.key(), marker.versionId(), true)));
            if (!response.isTruncated()) {
                break;
            }
            keyMarker = response.nextKeyMarker();
            versionIdMarker = response.nextVersionIdMarker();
        } while (true);
        return records;
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

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private record VersionRecord(String key, String versionId, boolean deleteMarker) {
    }

    private record VersionInventory(
            int objectCount,
            int versionCount,
            int deleteMarkerCount,
            Map<String, Integer> versionsByKey
    ) {
        private static VersionInventory from(List<VersionRecord> records) {
            Set<String> keys = new HashSet<>();
            Map<String, Integer> versionsByKey = new TreeMap<>();
            int versions = 0;
            int deleteMarkers = 0;
            for (VersionRecord record : records) {
                keys.add(record.key());
                if (record.deleteMarker()) {
                    deleteMarkers++;
                } else {
                    versions++;
                    versionsByKey.merge(record.key(), 1, Integer::sum);
                }
            }
            return new VersionInventory(keys.size(), versions, deleteMarkers, versionsByKey);
        }

        private double versionsPerObject() {
            return objectCount == 0 ? 0.0 : (double) versionCount / objectCount;
        }
    }

    private static final class BenchmarkClients implements AutoCloseable {
        private final S3Client cleanupClient;
        private final S3Client syncClient;
        private final S3Client apacheClient;
        private final S3AsyncClient asyncClient;
        private final S3AsyncClient transferAsyncClient;
        private final S3TransferManager transferManager;
        private final S3AsyncClient crtClient;
        private final B2BenchmarkSupport.AttemptTracker syncCounter;
        private final B2BenchmarkSupport.AttemptTracker apacheCounter;
        private final B2BenchmarkSupport.AttemptTracker asyncCounter;
        private final B2BenchmarkSupport.AttemptTracker transferCounter;

        private BenchmarkClients(
                S3Client cleanupClient,
                S3Client syncClient,
                S3Client apacheClient,
                S3AsyncClient asyncClient,
                S3AsyncClient transferAsyncClient,
                S3TransferManager transferManager,
                S3AsyncClient crtClient,
                B2BenchmarkSupport.AttemptTracker syncCounter,
                B2BenchmarkSupport.AttemptTracker apacheCounter,
                B2BenchmarkSupport.AttemptTracker asyncCounter,
                B2BenchmarkSupport.AttemptTracker transferCounter
        ) {
            this.cleanupClient = cleanupClient;
            this.syncClient = syncClient;
            this.apacheClient = apacheClient;
            this.asyncClient = asyncClient;
            this.transferAsyncClient = transferAsyncClient;
            this.transferManager = transferManager;
            this.crtClient = crtClient;
            this.syncCounter = syncCounter;
            this.apacheCounter = apacheCounter;
            this.asyncCounter = asyncCounter;
            this.transferCounter = transferCounter;
        }

        private static BenchmarkClients create(
                BenchmarkSettings settings,
                List<String> transports
        ) {
            B2BenchmarkSupport.AttemptTracker syncCounter = transports.contains("sync")
                    ? new B2BenchmarkSupport.AttemptTracker() : null;
            B2BenchmarkSupport.AttemptTracker apacheCounter = transports.contains("apache")
                    ? new B2BenchmarkSupport.AttemptTracker() : null;
            B2BenchmarkSupport.AttemptTracker asyncCounter = transports.contains("async")
                    ? new B2BenchmarkSupport.AttemptTracker() : null;
            B2BenchmarkSupport.AttemptTracker transferCounter = transports.contains("transfer-manager")
                    ? new B2BenchmarkSupport.AttemptTracker() : null;
            S3Client cleanupClient = createSyncClient(settings, null);
            S3Client syncClient = syncCounter == null ? null : createSyncClient(settings, syncCounter);
            S3Client apacheClient = apacheCounter == null ? null : createApacheClient(settings, apacheCounter);
            S3AsyncClient asyncClient = asyncCounter == null ? null : createAsyncClient(settings, asyncCounter);
            S3AsyncClient transferAsyncClient = transferCounter == null
                    ? null : createAsyncClient(settings, transferCounter);
            S3TransferManager transferManager = transferAsyncClient == null
                    ? null : createTransferManager(transferAsyncClient);
            S3AsyncClient crtClient = transports.contains("crt") ? createCrtClient(settings) : null;
            return new BenchmarkClients(
                    cleanupClient, syncClient, apacheClient, asyncClient, transferAsyncClient,
                    transferManager, crtClient, syncCounter, apacheCounter, asyncCounter, transferCounter);
        }

        private B2BenchmarkSupport.AttemptTracker attemptCounter(String transport) {
            return switch (transport) {
                case "sync" -> syncCounter;
                case "apache" -> apacheCounter;
                case "async" -> asyncCounter;
                case "transfer-manager" -> transferCounter;
                case "crt" -> null;
                default -> throw new IllegalArgumentException("Unknown benchmark transport: " + transport);
            };
        }

        @Override
        public void close() {
            if (transferManager != null) {
                transferManager.close();
            }
            if (transferAsyncClient != null) {
                transferAsyncClient.close();
            }
            if (asyncClient != null) {
                asyncClient.close();
            }
            if (crtClient != null) {
                crtClient.close();
            }
            if (apacheClient != null) {
                apacheClient.close();
            }
            if (syncClient != null) {
                syncClient.close();
            }
            cleanupClient.close();
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
        private static BenchmarkSettings fromEnvironment(B2BenchmarkSupport.BenchmarkOptions options) {
            String endpointValue = requiredEnvironment("B2_ENDPOINT");
            String bucket = requiredEnvironment("B2_BUCKET");
            String accessKeyId = requiredEnvironment("B2_ACCESS_KEY_ID");
            String secretAccessKey = requiredEnvironment("B2_SECRET_ACCESS_KEY");
            String prefix = normalizePrefix(System.getenv().getOrDefault("B2_OBJECT_PREFIX", "validator/"));
            URI endpoint = URI.create(endpointValue);
            String host = endpoint.getHost();
            if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("B2_ENDPOINT must use HTTPS");
            }
            if (host == null || !host.toLowerCase(Locale.ROOT)
                    .matches("s3\\.[^.]+\\.backblazeb2\\.com")) {
                throw new IllegalArgumentException("B2_ENDPOINT must be s3.<region>.backblazeb2.com");
            }
            String[] hostParts = host.split("\\.");
            if (hostParts.length < 4) {
                throw new IllegalArgumentException("B2_ENDPOINT must include a region");
            }
            return new BenchmarkSettings(
                    endpoint,
                    bucket,
                    prefix,
                    Region.of(hostParts[1]),
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)),
                    options.fileCount(),
                    options.concurrency()
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
}
