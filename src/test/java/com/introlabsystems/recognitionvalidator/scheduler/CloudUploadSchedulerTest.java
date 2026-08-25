package com.introlabsystems.recognitionvalidator.scheduler;

import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.CloudUploadRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ImageAssetBatchWriter;
import com.introlabsystems.recognitionvalidator.model.value.CloudUploadCandidate;
import com.introlabsystems.recognitionvalidator.storage.CloudObjectStorage;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class CloudUploadSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");
    private static final URI ENDPOINT = URI.create("https://s3.eu-central-003.backblazeb2.com");

    @Autowired
    private ObjectProvider<CloudUploadScheduler> schedulerProvider;

    private ExecutorService executor;

    @TempDir
    private Path imageRoot;

    @AfterEach
    void stopExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void disabledConfigurationDoesNotCreateSchedulerBean() {
        assertThat(schedulerProvider.getIfAvailable()).isNull();
    }

    @Test
    void runOnceUsesConfiguredBatchLimitAndMarksSuccessfulPut() throws Exception {
        Path first = write("first.png");
        Path second = write("second.png");
        CloudUploadRepository repository = mock(CloudUploadRepository.class);
        ImageAssetBatchWriter writer = mock(ImageAssetBatchWriter.class);
        RecordingCloudStorage cloud = new RecordingCloudStorage();
        B2StorageProperties b2 = b2Properties(2, 2, Duration.ofMinutes(5));
        when(repository.findCandidates(NOW, 2)).thenReturn(List.of(
                candidate("first", "first.png"),
                candidate("second", "second.png")
        ));
        executor = Executors.newFixedThreadPool(2);

        int uploaded = scheduler(repository, writer, cloud, b2, executor).runOnce();

        assertThat(uploaded).isEqualTo(2);
        assertThat(cloud.keys()).containsExactlyInAnyOrder("validator/first.png", "validator/second.png");
        assertThat(cloud.paths()).containsExactlyInAnyOrder(first, second);
        verify(repository).markUploaded("first", "validator/first.png", NOW);
        verify(repository).markUploaded("second", "validator/second.png", NOW);
    }

    @Test
    void missingLocalFileMarksItUnavailableWithoutUploading() {
        CloudUploadRepository repository = mock(CloudUploadRepository.class);
        ImageAssetBatchWriter writer = mock(ImageAssetBatchWriter.class);
        RecordingCloudStorage cloud = new RecordingCloudStorage();
        B2StorageProperties b2 = b2Properties(10, 1, Duration.ofMinutes(5));
        when(repository.findCandidates(NOW, 10)).thenReturn(List.of(
                candidate("missing", "missing.png")
        ));
        executor = Executors.newFixedThreadPool(1);

        int uploaded = scheduler(repository, writer, cloud, b2, executor).runOnce();

        assertThat(uploaded).isZero();
        assertThat(cloud.keys()).isEmpty();
        verify(writer).markUnavailable("missing");
        verify(repository, never()).markUploaded(any(), any(), any());
        verify(repository, never()).markFailed(any(), any());
    }

    @Test
    void failedUploadDoesNotStopSiblingsAndSchedulesRetry() throws Exception {
        write("failed.png");
        write("sibling.png");
        CloudUploadRepository repository = mock(CloudUploadRepository.class);
        ImageAssetBatchWriter writer = mock(ImageAssetBatchWriter.class);
        RecordingCloudStorage cloud = new RecordingCloudStorage("validator/failed.png");
        B2StorageProperties b2 = b2Properties(10, 2, Duration.ofMinutes(5));
        when(repository.findCandidates(NOW, 10)).thenReturn(List.of(
                candidate("failed", "failed.png"),
                candidate("sibling", "sibling.png")
        ));
        executor = Executors.newFixedThreadPool(2);

        int uploaded = scheduler(repository, writer, cloud, b2, executor).runOnce();

        assertThat(uploaded).isEqualTo(1);
        verify(repository).markUploaded("sibling", "validator/sibling.png", NOW);
        verify(repository).markAttemptStarted(
                "failed",
                "validator/failed.png",
                NOW.plus(Duration.ofMinutes(5))
        );
    }

    @Test
    void successfulPutIsNotDiscardedWhenDatabaseMarkerFailsAndLocalFileVanishes()
            throws Exception {
        Path source = write("uploaded-before-db-failure.png");
        CloudUploadRepository repository = mock(CloudUploadRepository.class);
        ImageAssetBatchWriter writer = mock(ImageAssetBatchWriter.class);
        RecordingCloudStorage cloud = new RecordingCloudStorage();
        B2StorageProperties b2 = b2Properties(1, 1, Duration.ofMinutes(5));
        when(repository.findCandidates(NOW, 1)).thenReturn(List.of(
                candidate("recoverable", "uploaded-before-db-failure.png")
        ));
        doAnswer(invocation -> {
            Files.delete(source);
            throw new IllegalStateException("database failed after successful PUT");
        }).when(repository).markUploaded(any(), any(), any());
        executor = Executors.newFixedThreadPool(1);

        int uploaded = scheduler(repository, writer, cloud, b2, executor).runOnce();

        assertThat(uploaded).isZero();
        assertThat(cloud.keys()).containsExactly("validator/recoverable.png");
        verify(writer, never()).markUnavailable("recoverable");
    }

    @Test
    void preparedRemoteObjectIsFinalizedWhenLocalFileHasDisappeared() {
        String objectKey = "validator/recover-after-restart.png";
        CloudUploadRepository repository = mock(CloudUploadRepository.class);
        ImageAssetBatchWriter writer = mock(ImageAssetBatchWriter.class);
        CloudObjectStorage cloud = mock(CloudObjectStorage.class);
        B2StorageProperties b2 = b2Properties(1, 1, Duration.ofMinutes(5));
        CloudUploadCandidate prepared = new CloudUploadCandidate(
                "recover-after-restart",
                "missing-after-restart.png",
                NOW.minusSeconds(600),
                objectKey,
                NOW,
                1
        );
        when(repository.findCandidates(NOW, 1)).thenReturn(List.of(prepared));
        when(cloud.exists(objectKey)).thenReturn(true);
        executor = Executors.newFixedThreadPool(1);

        int uploaded = scheduler(repository, writer, cloud, b2, executor).runOnce();

        assertThat(uploaded).isEqualTo(1);
        verify(repository).markAttemptStarted(
                "recover-after-restart",
                objectKey,
                NOW.plus(Duration.ofMinutes(5))
        );
        verify(cloud).exists(objectKey);
        verify(cloud, never()).upload(any(), any());
        verify(repository).markUploaded(
                "recover-after-restart",
                objectKey,
                NOW.minus(Duration.ofMinutes(5))
        );
        verify(writer, never()).markUnavailable("recover-after-restart");
    }

    @Test
    void failedBatchEmitsOneWarningSummaryAndKeepsPerImageDiagnosticsAtDebug() throws Exception {
        write("failed-one.png");
        write("failed-two.png");
        CloudUploadRepository repository = mock(CloudUploadRepository.class);
        ImageAssetBatchWriter writer = mock(ImageAssetBatchWriter.class);
        CloudObjectStorage cloud = new FailingCloudStorage();
        B2StorageProperties b2 = b2Properties(10, 2, Duration.ofMinutes(5));
        when(repository.findCandidates(NOW, 10)).thenReturn(List.of(
                candidate("failed-one", "failed-one.png"),
                candidate("failed-two", "failed-two.png")
        ));
        executor = Executors.newFixedThreadPool(2);
        Logger logger = (Logger) LoggerFactory.getLogger(CloudUploadScheduler.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            scheduler(repository, writer, cloud, b2, executor).runOnce();

            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .toList();
            assertThat(warnings).singleElement()
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .asString()
                    .contains("B2 upload batch completed", "failed=2");
            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.DEBUG)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("failed-one"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void emptyBatchDoesNotEmitPeriodicInfoLog() {
        CloudUploadRepository repository = mock(CloudUploadRepository.class);
        ImageAssetBatchWriter writer = mock(ImageAssetBatchWriter.class);
        B2StorageProperties b2 = b2Properties(10, 1, Duration.ofMinutes(5));
        when(repository.findCandidates(NOW, 10)).thenReturn(List.of());
        executor = Executors.newFixedThreadPool(1);
        Logger logger = (Logger) LoggerFactory.getLogger(CloudUploadScheduler.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(scheduler(repository, writer, new RecordingCloudStorage(), b2, executor)
                    .runOnce()).isZero();
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void missingFileStateWriteFailureSchedulesRetryAndIsReportedAsFailed() {
        CloudUploadRepository repository = mock(CloudUploadRepository.class);
        ImageAssetBatchWriter writer = mock(ImageAssetBatchWriter.class);
        B2StorageProperties b2 = b2Properties(10, 1, Duration.ofMinutes(5));
        when(repository.findCandidates(NOW, 10)).thenReturn(List.of(
                candidate("missing", "missing.png")
        ));
        doThrow(new IllegalStateException("database failure"))
                .when(writer).markUnavailable("missing");
        executor = Executors.newFixedThreadPool(1);
        Logger logger = (Logger) LoggerFactory.getLogger(CloudUploadScheduler.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(scheduler(repository, writer, new RecordingCloudStorage(), b2, executor)
                    .runOnce()).isZero();
            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .singleElement()
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .asString()
                    .contains("B2 upload batch completed", "missing=0", "failed=1");
            verify(repository).markFailed("missing", NOW.plus(Duration.ofMinutes(5)));
            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.DEBUG)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("markUnavailable")
                            && message.contains("missing"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void retryStateWriteFailureIsLoggedAndDoesNotEscapeTheCandidateTask() {
        CloudUploadRepository repository = mock(CloudUploadRepository.class);
        ImageAssetBatchWriter writer = mock(ImageAssetBatchWriter.class);
        B2StorageProperties b2 = b2Properties(10, 1, Duration.ofMinutes(5));
        when(repository.findCandidates(NOW, 10)).thenReturn(List.of(
                candidate("missing", "missing.png")
        ));
        doThrow(new IllegalStateException("database failure"))
                .when(writer).markUnavailable("missing");
        doThrow(new IllegalStateException("retry database failure"))
                .when(repository).markFailed("missing", NOW.plus(Duration.ofMinutes(5)));
        executor = Executors.newFixedThreadPool(1);
        Logger logger = (Logger) LoggerFactory.getLogger(CloudUploadScheduler.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(scheduler(repository, writer, new RecordingCloudStorage(), b2, executor)
                    .runOnce()).isZero();
            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .singleElement()
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .asString()
                    .contains("B2 upload batch completed", "missing=0", "failed=1");
            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.DEBUG)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("markFailed")
                            && message.contains("missing"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void unsafePathAndSymlinkAreMarkedUnavailable() throws Exception {
        Path outside = imageRoot.getParent().resolve("outside.png");
        Files.write(outside, new byte[]{1});
        Path symlink = imageRoot.resolve("symlink.png");
        boolean symlinkCreated;
        try {
            Files.createSymbolicLink(symlink, outside);
            symlinkCreated = true;
        } catch (FileSystemException exception) {
            symlinkCreated = false;
        }
        CloudUploadRepository repository = mock(CloudUploadRepository.class);
        ImageAssetBatchWriter writer = mock(ImageAssetBatchWriter.class);
        RecordingCloudStorage cloud = new RecordingCloudStorage();
        B2StorageProperties b2 = b2Properties(10, 1, Duration.ofMinutes(5));
        List<CloudUploadCandidate> candidates = new ArrayList<>();
        candidates.add(candidate("escape", "../outside.png"));
        if (symlinkCreated) {
            candidates.add(candidate("symlink", "symlink.png"));
        }
        when(repository.findCandidates(NOW, 10)).thenReturn(candidates);
        executor = Executors.newFixedThreadPool(1);

        scheduler(repository, writer, cloud, b2, executor).runOnce();

        assertThat(cloud.keys()).isEmpty();
        verify(writer).markUnavailable("escape");
        if (symlinkCreated) {
            verify(writer).markUnavailable("symlink");
        }
    }

    @Test
    void uploadConcurrencyNeverExceedsConfiguredMaximum() throws Exception {
        int concurrency = 2;
        List<CloudUploadCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            String id = "image-" + index;
            String fileName = id + ".png";
            write(fileName);
            candidates.add(candidate(id, fileName));
        }
        CloudUploadRepository repository = mock(CloudUploadRepository.class);
        ImageAssetBatchWriter writer = mock(ImageAssetBatchWriter.class);
        BlockingCloudStorage cloud = new BlockingCloudStorage(concurrency);
        B2StorageProperties b2 = b2Properties(10, concurrency, Duration.ofMinutes(5));
        when(repository.findCandidates(NOW, 10)).thenReturn(candidates);
        executor = Executors.newFixedThreadPool(concurrency);

        Thread run = new Thread(() -> scheduler(repository, writer, cloud, b2, executor).runOnce());
        run.start();
        assertThat(cloud.started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(cloud.maximumConcurrent()).isEqualTo(concurrency);
        cloud.release.countDown();
        run.join(2_000);
    }

    @Test
    void processesOneThousandCandidatesWithinConfiguredConcurrency() throws Exception {
        int candidateCount = 1_000;
        int concurrency = 8;
        write("shared.png");
        List<CloudUploadCandidate> candidates = new ArrayList<>(candidateCount);
        for (int index = 0; index < candidateCount; index++) {
            candidates.add(candidate("image-" + index, "shared.png"));
        }
        CloudUploadRepository repository = mock(CloudUploadRepository.class);
        ImageAssetBatchWriter writer = mock(ImageAssetBatchWriter.class);
        CountingCloudStorage cloud = new CountingCloudStorage();
        B2StorageProperties b2 = b2Properties(candidateCount, concurrency, Duration.ofMinutes(5));
        when(repository.findCandidates(NOW, candidateCount)).thenReturn(candidates);
        executor = Executors.newFixedThreadPool(concurrency);

        int uploaded = scheduler(repository, writer, cloud, b2, executor).runOnce();

        assertThat(uploaded).isEqualTo(candidateCount);
        assertThat(cloud.completed()).isEqualTo(candidateCount);
        assertThat(cloud.maximumConcurrent()).isBetween(1, concurrency);
    }

    @Test
    void overlappingRunIsSkippedUntilFirstBatchCompletes() throws Exception {
        write("held.png");
        CloudUploadRepository repository = mock(CloudUploadRepository.class);
        ImageAssetBatchWriter writer = mock(ImageAssetBatchWriter.class);
        BlockingCloudStorage cloud = new BlockingCloudStorage(1);
        B2StorageProperties b2 = b2Properties(10, 1, Duration.ofMinutes(5));
        when(repository.findCandidates(NOW, 10)).thenReturn(List.of(candidate("held", "held.png")));
        executor = Executors.newFixedThreadPool(1);
        CloudUploadScheduler scheduler = scheduler(repository, writer, cloud, b2, executor);

        Thread first = new Thread(scheduler::runOnce);
        first.start();
        assertThat(cloud.started.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(scheduler.runOnce()).isZero();

        cloud.release.countDown();
        first.join(2_000);
        verify(repository).findCandidates(NOW, 10);
    }

    private Path write(String fileName) throws Exception {
        Files.createDirectories(imageRoot);
        Path path = imageRoot.resolve(fileName);
        Files.write(path, new byte[]{1, 2, 3});
        return path.toAbsolutePath().normalize();
    }

    private CloudUploadScheduler scheduler(
            CloudUploadRepository repository,
            ImageAssetBatchWriter writer,
            CloudObjectStorage cloud,
            B2StorageProperties b2,
            ExecutorService executor
    ) {
        return new CloudUploadScheduler(
                repository,
                cloud,
                writer,
                b2,
                validatorProperties(imageRoot, b2.uploadBatchSize()),
                Clock.fixed(NOW, ZoneOffset.UTC),
                executor
        );
    }

    private static CloudUploadCandidate candidate(String id, String relativePath) {
        return new CloudUploadCandidate(id, relativePath, NOW, null, null, 0);
    }

    private static B2StorageProperties b2Properties(
            int batchSize,
            int concurrency,
            Duration retryDelay
    ) {
        return new B2StorageProperties(
                true,
                ENDPOINT,
                "bucket",
                "access",
                "secret",
                "validator/",
                batchSize,
                concurrency,
                Duration.ofSeconds(10),
                retryDelay,
                Duration.ofDays(3),
                Duration.ofMinutes(30),
                Duration.ofDays(21)
        );
    }

    private static ValidatorProperties validatorProperties(Path root, int batchSize) {
        return new ValidatorProperties(
                root,
                List.of("bj_igt"),
                batchSize,
                Duration.ofMinutes(30),
                Duration.ofDays(4),
                100,
                0,
                false,
                Duration.ofSeconds(1),
                100,
                true
        );
    }

    private static class RecordingCloudStorage implements CloudObjectStorage {

        private final String failingKey;
        private final List<String> keys = new ArrayList<>();
        private final List<Path> paths = new ArrayList<>();

        protected RecordingCloudStorage() {
            this(null);
        }

        protected RecordingCloudStorage(String failingKey) {
            this.failingKey = failingKey;
        }

        @Override
        public synchronized void upload(String objectKey, Path source) {
            if (objectKey.equals(failingKey)) {
                throw new IllegalStateException("transient upload failure");
            }
            keys.add(objectKey);
            paths.add(source);
        }

        @Override
        public synchronized boolean exists(String objectKey) {
            return keys.contains(objectKey);
        }

        @Override
        public URI presignGet(String objectKey, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CloudContent open(String objectKey) {
            throw new UnsupportedOperationException();
        }

        synchronized List<String> keys() {
            return List.copyOf(keys);
        }

        synchronized List<Path> paths() {
            return List.copyOf(paths);
        }
    }

    private static final class BlockingCloudStorage extends RecordingCloudStorage {

        private final CountDownLatch started;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximum = new AtomicInteger();

        private BlockingCloudStorage(int expectedStarts) {
            this.started = new CountDownLatch(expectedStarts);
        }

        @Override
        public void upload(String objectKey, Path source) {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                active.decrementAndGet();
            }
        }

        private int maximumConcurrent() {
            return maximum.get();
        }
    }

    private static final class FailingCloudStorage extends RecordingCloudStorage {

        @Override
        public void upload(String objectKey, Path source) {
            throw new IllegalStateException("temporary failure");
        }
    }

    private static final class CountingCloudStorage extends RecordingCloudStorage {

        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximum = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();

        @Override
        public void upload(String objectKey, Path source) {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(1);
                completed.incrementAndGet();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                active.decrementAndGet();
            }
        }

        private int completed() {
            return completed.get();
        }

        private int maximumConcurrent() {
            return maximum.get();
        }
    }
}
