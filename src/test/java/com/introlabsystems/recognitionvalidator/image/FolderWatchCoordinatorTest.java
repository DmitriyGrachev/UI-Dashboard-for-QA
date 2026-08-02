package com.introlabsystems.recognitionvalidator.image;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FolderWatchCoordinatorTest {

    @TempDir
    private Path imageRoot;

    @Test
    void flushesImmediatelyWhenBatchThresholdIsReached() throws Exception {
        CountDownLatch indexed = new CountDownLatch(1);
        RecordingImageIndexer indexer = new RecordingImageIndexer(imageRoot, indexed);
        ValidatorProperties properties = new ValidatorProperties(
                imageRoot,
                List.of("bj_igt"),
                2,
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                10,
                10,
                true,
                Duration.ofHours(1),
                100,
                true
        );
        ImageFileEventHandler handler = new ImageFileEventHandler(
                indexer,
                new ImageEventBuffer(2, 100)
        );
        FolderWatchCoordinator coordinator = new FolderWatchCoordinator(
                properties,
                indexer,
                handler,
                Clock.systemUTC()
        );

        coordinator.start();
        try {
            Files.write(imageRoot.resolve("one.png"), new byte[]{1});
            Files.write(imageRoot.resolve("two.png"), new byte[]{2});

            assertThat(indexed.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            coordinator.close();
        }
    }

    @Test
    void backsOffAfterProcessorFailureInsteadOfBusyLooping() throws Exception {
        RecordingImageIndexer indexer = new RecordingImageIndexer(
                imageRoot,
                new CountDownLatch(1)
        );
        ValidatorProperties properties = new ValidatorProperties(
                imageRoot,
                List.of("bj_igt"),
                1,
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                10,
                10,
                true,
                Duration.ofMillis(10),
                10,
                true
        );
        FailingEventHandler handler = new FailingEventHandler(indexer);
        FolderWatchCoordinator coordinator = new FolderWatchCoordinator(
                properties,
                indexer,
                handler,
                Clock.systemUTC()
        );

        coordinator.start();
        try {
            assertThat(handler.firstAttempt.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(250);

            assertThat(handler.attempts).hasValue(1);
        } finally {
            coordinator.close();
        }
    }

    @Test
    void reRegistersAndReconcilesAfterWatchKeyBecomesInvalid() throws Exception {
        CountDownLatch recoveredReconciliation = new CountDownLatch(1);
        RecoveringImageIndexer indexer = new RecoveringImageIndexer(
                imageRoot,
                recoveredReconciliation
        );
        ValidatorProperties properties = new ValidatorProperties(
                imageRoot,
                List.of("bj_igt"),
                10,
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                10,
                10,
                true,
                Duration.ofMillis(10),
                100,
                true
        );
        ImageFileEventHandler handler = new ImageFileEventHandler(
                indexer,
                new ImageEventBuffer(10, 100)
        );
        FolderWatchCoordinator coordinator = new FolderWatchCoordinator(
                properties,
                indexer,
                handler,
                Clock.systemUTC()
        );

        coordinator.start();
        try {
            assertThat(indexer.scanCount).hasValue(1);

            Files.delete(imageRoot);

            assertThat(recoveredReconciliation.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.isDirectory(imageRoot)).isTrue();
            assertThat(indexer.scanCount).hasValueGreaterThanOrEqualTo(2);
        } finally {
            coordinator.close();
        }
    }

    private static final class RecordingImageIndexer extends ImageIndexer {

        private final CountDownLatch indexed;

        private RecordingImageIndexer(Path imageRoot, CountDownLatch indexed) {
            super(imageRoot, 2, null, null, Clock.systemUTC());
            this.indexed = indexed;
        }

        @Override
        public void scanRoot() {
        }

        @Override
        public void indexBatch(Collection<Path> absolutePaths) {
            if (absolutePaths.size() >= 2) {
                indexed.countDown();
            }
        }
    }

    private static final class FailingEventHandler extends ImageFileEventHandler {

        private final AtomicInteger attempts = new AtomicInteger();
        private final CountDownLatch firstAttempt = new CountDownLatch(1);

        private FailingEventHandler(ImageIndexer indexer) {
            super(indexer, new ImageEventBuffer(1, 10));
        }

        @Override
        public void flush() {
            attempts.incrementAndGet();
            firstAttempt.countDown();
            throw new IllegalStateException("database unavailable");
        }
    }

    private static final class RecoveringImageIndexer extends ImageIndexer {

        private final AtomicInteger scanCount = new AtomicInteger();
        private final CountDownLatch recoveredReconciliation;

        private RecoveringImageIndexer(
                Path imageRoot,
                CountDownLatch recoveredReconciliation
        ) {
            super(imageRoot, 10, null, null, Clock.systemUTC());
            this.recoveredReconciliation = recoveredReconciliation;
        }

        @Override
        public void scanRoot() {
            if (scanCount.incrementAndGet() >= 2) {
                recoveredReconciliation.countDown();
            }
        }
    }
}
