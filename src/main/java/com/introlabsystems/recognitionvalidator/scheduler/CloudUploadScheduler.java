package com.introlabsystems.recognitionvalidator.scheduler;

import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.dao.jdbc.CloudUploadRepository;
import com.introlabsystems.recognitionvalidator.dao.jdbc.ImageAssetBatchWriter;
import com.introlabsystems.recognitionvalidator.model.value.CloudUploadCandidate;
import com.introlabsystems.recognitionvalidator.storage.CloudObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "validator.b2", name = "enabled", havingValue = "true")
public class CloudUploadScheduler {

    private static final Logger log = LoggerFactory.getLogger(CloudUploadScheduler.class);

    private final CloudUploadRepository uploads;
    private final CloudObjectStorage storage;
    private final ImageAssetBatchWriter images;
    private final B2StorageProperties b2Properties;
    private final Path imageRoot;
    private final Clock clock;
    private final Executor uploadExecutor;
    private final AtomicBoolean running = new AtomicBoolean();

    public CloudUploadScheduler(
            CloudUploadRepository uploads,
            CloudObjectStorage storage,
            ImageAssetBatchWriter images,
            B2StorageProperties b2Properties,
            ValidatorProperties validatorProperties,
            Clock clock,
            @Qualifier("b2UploadExecutor") Executor uploadExecutor
    ) {
        this.uploads = uploads;
        this.storage = storage;
        this.images = images;
        this.b2Properties = b2Properties;
        this.imageRoot = validatorProperties.imageRoot().toAbsolutePath().normalize();
        this.clock = clock;
        this.uploadExecutor = uploadExecutor;
    }

    @Scheduled(fixedDelayString = "${validator.b2.upload-delay:10s}")
    public int runOnce() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Skipping overlapping B2 upload batch");
            return 0;
        }

        long startedNanos = System.nanoTime();
        try {
            Instant now = clock.instant();
            List<CloudUploadCandidate> candidates = uploads.findCandidates(
                    now,
                    b2Properties.uploadBatchSize()
            );
            if (candidates.isEmpty()) {
                return 0;
            }

            List<CompletableFuture<UploadResult>> futures = candidates.stream()
                    .map(candidate -> CompletableFuture.supplyAsync(
                            () -> upload(candidate),
                            uploadExecutor
                    ))
                    .toList();

            int uploaded = 0;
            int missing = 0;
            int failed = 0;
            for (CompletableFuture<UploadResult> future : futures) {
                try {
                    UploadResult result = future.join();
                    uploaded += result == UploadResult.UPLOADED ? 1 : 0;
                    missing += result == UploadResult.MISSING ? 1 : 0;
                    failed += result == UploadResult.FAILED ? 1 : 0;
                } catch (CompletionException exception) {
                    failed++;
                    log.debug("B2 upload task failed unexpectedly", exception.getCause());
                }
            }
            logBatchSummary(candidates.size(), uploaded, missing, failed, startedNanos);
            return uploaded;
        } finally {
            running.set(false);
        }
    }

    private UploadResult upload(CloudUploadCandidate candidate) {
        Path source = imageRoot.resolve(candidate.relativePath()).normalize();
        boolean localAvailable = isSafeRegularFile(source);
        boolean prepared = candidate.objectKey() != null
                && !candidate.objectKey().isBlank()
                && candidate.attemptCount() > 0;
        if (!localAvailable && !prepared) {
            return markUnavailable(candidate);
        }

        String objectKey = prepared
                ? candidate.objectKey()
                : b2Properties.objectKey(candidate.imageId());
        Instant attemptAt = clock.instant();
        try {
            uploads.markAttemptStarted(
                    candidate.imageId(),
                    objectKey,
                    attemptAt.plus(b2Properties.uploadRetryDelay())
            );

            if (prepared && storage.exists(objectKey)) {
                uploads.markUploaded(
                        candidate.imageId(),
                        objectKey,
                        recoveredUploadTime(candidate, attemptAt)
                );
                return UploadResult.UPLOADED;
            }
            if (!localAvailable) {
                images.markUnavailable(candidate.imageId());
                uploads.clearAttempt(candidate.imageId());
                return UploadResult.MISSING;
            }

            storage.upload(objectKey, source);
            uploads.markUploaded(candidate.imageId(), objectKey, attemptAt);
            return UploadResult.UPLOADED;
        } catch (RuntimeException exception) {
            log.debug("B2 upload failed for image {}; retry scheduled", candidate.imageId(), exception);
            return UploadResult.FAILED;
        }
    }

    private Instant recoveredUploadTime(CloudUploadCandidate candidate, Instant fallback) {
        if (candidate.nextAttemptAt() == null) {
            return fallback;
        }
        return candidate.nextAttemptAt().minus(b2Properties.uploadRetryDelay());
    }

    private UploadResult markUnavailable(CloudUploadCandidate candidate) {
        try {
            images.markUnavailable(candidate.imageId());
            return UploadResult.MISSING;
        } catch (RuntimeException exception) {
            Instant nextAttemptAt = clock.instant().plus(b2Properties.uploadRetryDelay());
            try {
                uploads.markFailed(candidate.imageId(), nextAttemptAt);
                log.debug(
                        "B2 markUnavailable failed for image {}; retry scheduled",
                        candidate.imageId(),
                        exception
                );
            } catch (RuntimeException retryException) {
                log.debug(
                        "B2 markUnavailable failed for image {}; retry state also failed",
                        candidate.imageId(),
                        exception
                );
                log.debug(
                        "B2 markFailed failed for image {}; candidate remains failed",
                        candidate.imageId(),
                        retryException
                );
            }
            return UploadResult.FAILED;
        }
    }

    private void logBatchSummary(
            int candidates,
            int uploaded,
            int missing,
            int failed,
            long startedNanos
    ) {
        if (failed > 0) {
            log.warn(
                    "B2 upload batch completed: candidates={}, uploaded={}, missing={}, failed={}, durationMs={}",
                    candidates,
                    uploaded,
                    missing,
                    failed,
                    (System.nanoTime() - startedNanos) / 1_000_000
            );
        } else {
            log.info(
                    "B2 upload batch completed: candidates={}, uploaded={}, missing={}, failed={}, durationMs={}",
                    candidates,
                    uploaded,
                    missing,
                    failed,
                    (System.nanoTime() - startedNanos) / 1_000_000
            );
        }
    }

    private boolean isSafeRegularFile(Path source) {
        try {
            if (!source.startsWith(imageRoot)
                    || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Path current = imageRoot;
            for (Path segment : imageRoot.relativize(source)) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) {
                    return false;
                }
            }
            return true;
        } catch (SecurityException exception) {
            return false;
        }
    }

    private enum UploadResult {
        UPLOADED,
        MISSING,
        FAILED
    }
}
