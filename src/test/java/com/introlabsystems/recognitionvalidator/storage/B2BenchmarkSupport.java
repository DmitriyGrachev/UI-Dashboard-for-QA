package com.introlabsystems.recognitionvalidator.storage;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Small, DB-free value/validation helpers used only by the B2 benchmark. */
final class B2BenchmarkSupport {

    static final String TRANSPORTS_PROPERTY = "b2.benchmark.transports";
    static final String REPETITIONS_PROPERTY = "b2.benchmark.repetitions";
    static final String SEED_PROPERTY = "b2.benchmark.seed";
    static final String COUNT_PROPERTY = "b2.benchmark.count";
    static final String CONCURRENCY_PROPERTY = "b2.benchmark.concurrency";
    static final int DEFAULT_FILE_COUNT = 10;
    static final int MAX_FILE_COUNT = 500;
    static final int DEFAULT_CONCURRENCY = 8;
    static final int MAX_CONCURRENCY = 128;
    static final int DEFAULT_REPETITIONS = 1;
    static final int MAX_REPETITIONS = 5;
    static final long DEFAULT_SEED = 20260826L;
    static final List<String> DEFAULT_TRANSPORTS = List.of("sync", "async", "transfer-manager");
    static final List<String> ALL_TRANSPORTS = List.of(
            "sync", "async", "transfer-manager", "apache", "crt");

    private B2BenchmarkSupport() {
    }

    static BenchmarkOptions optionsFrom(Properties properties) {
        String transportValue = properties.getProperty(TRANSPORTS_PROPERTY);
        List<String> transports = transportValue == null
                ? DEFAULT_TRANSPORTS
                : parseTransports(transportValue);
        int count = boundedInt(properties, COUNT_PROPERTY, DEFAULT_FILE_COUNT, 1, MAX_FILE_COUNT);
        int concurrency = boundedInt(
                properties, CONCURRENCY_PROPERTY, DEFAULT_CONCURRENCY, 1, MAX_CONCURRENCY);
        int repetitions = boundedInt(
                properties, REPETITIONS_PROPERTY, DEFAULT_REPETITIONS, 1, MAX_REPETITIONS);
        long seed = boundedLong(properties, SEED_PROPERTY, DEFAULT_SEED);
        return new BenchmarkOptions(transports, count, concurrency, repetitions, seed);
    }

    static List<String> parseTransports(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(TRANSPORTS_PROPERTY + " must not be empty");
        }
        String trimmed = raw.trim();
        if (trimmed.equals("all")) {
            return ALL_TRANSPORTS;
        }
        if (trimmed.contains("all")) {
            throw new IllegalArgumentException(
                    TRANSPORTS_PROPERTY + " uses 'all' alone; it cannot be combined");
        }

        List<String> transports = new ArrayList<>();
        for (String token : raw.split(",", -1)) {
            String transport = token.trim();
            if (transport.isEmpty() || !ALL_TRANSPORTS.contains(transport)) {
                throw new IllegalArgumentException(
                        "Unknown or empty B2 benchmark transport: '" + transport + "'");
            }
            if (transports.contains(transport)) {
                throw new IllegalArgumentException(
                        "Duplicate B2 benchmark transport: '" + transport + "'");
            }
            transports.add(transport);
        }
        return List.copyOf(transports);
    }

    static List<String> orderFor(List<String> selected, int repetition, long seed) {
        if (selected == null || selected.isEmpty()) {
            throw new IllegalArgumentException("At least one B2 benchmark transport is required");
        }
        if (repetition < 1 || repetition > MAX_REPETITIONS) {
            throw new IllegalArgumentException("B2 benchmark repetition must be between 1 and 5");
        }
        List<String> order = new ArrayList<>(selected);
        Collections.shuffle(order, new Random(seed));
        Collections.rotate(order, -(repetition - 1) % order.size());
        return List.copyOf(order);
    }

    static BenchmarkMetrics metrics(
            String transport,
            List<UploadObservation> observations,
            long elapsedNanos,
            int peakInFlight,
            long payloadBytes,
            RetryMetrics retryMetrics
    ) {
        if (elapsedNanos < 0 || payloadBytes < 0) {
            throw new IllegalArgumentException("Benchmark metric values cannot be negative");
        }
        List<Long> latencies = observations.stream()
                .map(UploadObservation::latencyNanos)
                .sorted()
                .toList();
        int successes = (int) observations.stream().filter(UploadObservation::success).count();
        int failures = observations.size() - successes;
        return new BenchmarkMetrics(
                transport,
                observations.size(),
                Math.multiplyExact(successes, payloadBytes),
                elapsedNanos,
                successes,
                failures,
                peakInFlight,
                percentile(latencies, 0.50),
                percentile(latencies, 0.95),
                percentile(latencies, 0.99),
                failureCategories(observations),
                retryMetrics
        );
    }

    static TransportSummary summary(String transport, List<BenchmarkMetrics> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            throw new IllegalArgumentException("At least one run is required for a summary");
        }
        List<Double> rates = metrics.stream().map(BenchmarkMetrics::filesPerSecond).sorted().toList();
        int middle = rates.size() / 2;
        double median = rates.size() % 2 == 0
                ? (rates.get(middle - 1) + rates.get(middle)) / 2.0
                : rates.get(middle);
        return new TransportSummary(transport, metrics.size(), median,
                rates.get(0), rates.get(rates.size() - 1));
    }

    private static int boundedInt(
            Properties properties, String name, int defaultValue, int min, int max) {
        String raw = properties.getProperty(name);
        if (raw == null) {
            return defaultValue;
        }
        if (raw.isBlank()) {
            throw new IllegalArgumentException(name + " must be an integer between " + min + " and " + max);
        }
        final int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    name + " must be an integer between " + min + " and " + max, exception);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static long boundedLong(Properties properties, String name, long defaultValue) {
        String raw = properties.getProperty(name);
        if (raw == null) {
            return defaultValue;
        }
        if (raw.isBlank()) {
            throw new IllegalArgumentException(name + " must be a valid long seed");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a valid long seed", exception);
        }
    }

    private static Map<String, Integer> failureCategories(List<UploadObservation> observations) {
        Map<String, Integer> categories = new TreeMap<>();
        observations.stream()
                .filter(observation -> !observation.success())
                .map(observation -> observation.failureCategory() == null
                        ? "Unknown" : observation.failureCategory())
                .forEach(category -> categories.merge(category, 1, Integer::sum));
        return Map.copyOf(categories);
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        int index = Math.max(0, (int) Math.ceil(values.size() * percentile) - 1);
        return values.get(index);
    }

    record BenchmarkOptions(
            List<String> transports,
            int fileCount,
            int concurrency,
            int repetitions,
            long seed
    ) {
        BenchmarkOptions {
            transports = List.copyOf(transports);
        }
    }

    record UploadObservation(boolean success, long latencyNanos, String failureCategory) {
        static UploadObservation success(long latencyNanos) {
            return new UploadObservation(true, latencyNanos, null);
        }

        static UploadObservation failure(long latencyNanos, String failureCategory) {
            return new UploadObservation(false, latencyNanos, failureCategory);
        }
    }

    record RetryMetrics(boolean available, int httpAttempts, int retryAttempts) {
        static RetryMetrics observed(int logicalCalls, int httpAttempts) {
            if (logicalCalls < 0 || httpAttempts < 0) {
                throw new IllegalArgumentException("Retry counts cannot be negative");
            }
            return new RetryMetrics(true, httpAttempts, Math.max(0, httpAttempts - logicalCalls));
        }

        static RetryMetrics observed(int logicalCalls, int httpAttempts, int retryAttempts) {
            if (logicalCalls < 0 || httpAttempts < 0 || retryAttempts < 0) {
                throw new IllegalArgumentException("Retry counts cannot be negative");
            }
            return new RetryMetrics(true, httpAttempts, retryAttempts);
        }

        static RetryMetrics observedPerCall(List<Integer> attemptsPerCall) {
            int httpAttempts = 0;
            int retries = 0;
            for (Integer attempts : attemptsPerCall) {
                if (attempts == null || attempts < 0) {
                    throw new IllegalArgumentException("Retry counts cannot be negative");
                }
                httpAttempts += attempts;
                retries += Math.max(0, attempts - 1);
            }
            return new RetryMetrics(true, httpAttempts, retries);
        }

        static RetryMetrics unavailable() {
            return new RetryMetrics(false, -1, -1);
        }

        String display() {
            return available
                    ? "httpAttempts=" + httpAttempts + ", retryAttempts=" + retryAttempts
                    : "httpAttempts=unavailable, retryAttempts=unavailable";
        }
    }

    record BenchmarkMetrics(
            String transport,
            int count,
            long totalBytes,
            long elapsedNanos,
            int successes,
            int failures,
            int peakInFlight,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            Map<String, Integer> failureCategories,
            RetryMetrics retryMetrics
    ) {
        double filesPerSecond() {
            double seconds = elapsedNanos / 1_000_000_000.0;
            return seconds <= 0 ? successes : successes / seconds;
        }

        double mebibytesPerSecond() {
            double seconds = elapsedNanos / 1_000_000_000.0;
            return seconds <= 0 ? totalBytes / 1024.0 / 1024.0 : totalBytes / 1024.0 / 1024.0 / seconds;
        }

        @Override
        public String toString() {
            return "B2 benchmark " + transport
                    + ": count=" + count
                    + ", bytes=" + totalBytes
                    + ", elapsedSeconds=" + String.format(Locale.ROOT, "%.3f", elapsedNanos / 1_000_000_000.0)
                    + ", filesPerSecond=" + String.format(Locale.ROOT, "%.3f", filesPerSecond())
                    + ", MiBPerSecond=" + String.format(Locale.ROOT, "%.3f", mebibytesPerSecond())
                    + ", p50Ms=" + p50Nanos / 1_000_000
                    + ", p95Ms=" + p95Nanos / 1_000_000
                    + ", p99Ms=" + p99Nanos / 1_000_000
                    + ", successes=" + successes
                    + ", failures=" + failures
                    + ", failureCategories=" + failureCategories
                    + ", peakInFlight=" + peakInFlight
                    + ", " + retryMetrics.display();
        }
    }

    record TransportSummary(
            String transport,
            int repetitions,
            double medianFilesPerSecond,
            double minFilesPerSecond,
            double maxFilesPerSecond
    ) {
    }

    static final class AttemptTracker implements ExecutionInterceptor {
        private static final ExecutionAttribute<AtomicInteger> CALL_ATTEMPTS =
                new ExecutionAttribute<>("b2BenchmarkCallAttempts");
        private static final ExecutionAttribute<AtomicBoolean> CALL_FINISHED =
                new ExecutionAttribute<>("b2BenchmarkCallFinished");
        private final AtomicBoolean capturing = new AtomicBoolean();
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger retryAttempts = new AtomicInteger();

        @Override
        public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes executionAttributes) {
            if (capturing.get()) {
                executionAttributes.putAttribute(CALL_ATTEMPTS, new AtomicInteger());
                executionAttributes.putAttribute(CALL_FINISHED, new AtomicBoolean());
            }
        }

        @Override
        public void beforeTransmission(
                Context.BeforeTransmission context,
                ExecutionAttributes executionAttributes
        ) {
            if (capturing.get()) {
                attempts.incrementAndGet();
                AtomicInteger callAttempts = executionAttributes.getAttribute(CALL_ATTEMPTS);
                if (callAttempts == null) {
                    callAttempts = new AtomicInteger();
                    executionAttributes.putAttribute(CALL_ATTEMPTS, callAttempts);
                }
                callAttempts.incrementAndGet();
            }
        }

        @Override
        public void afterExecution(Context.AfterExecution context, ExecutionAttributes executionAttributes) {
            finishCall(executionAttributes);
        }

        @Override
        public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes executionAttributes) {
            finishCall(executionAttributes);
        }

        private void finishCall(ExecutionAttributes executionAttributes) {
            AtomicInteger callAttempts = executionAttributes.getAttribute(CALL_ATTEMPTS);
            AtomicBoolean finished = executionAttributes.getAttribute(CALL_FINISHED);
            if (capturing.get() && callAttempts != null && finished != null
                    && finished.compareAndSet(false, true)) {
                retryAttempts.addAndGet(Math.max(0, callAttempts.get() - 1));
            }
        }

        void begin() {
            attempts.set(0);
            retryAttempts.set(0);
            capturing.set(true);
        }

        RetryMetrics end(int logicalCalls) {
            capturing.set(false);
            return RetryMetrics.observed(logicalCalls, attempts.get(), retryAttempts.get());
        }

        void stop() {
            capturing.set(false);
        }
    }
}
