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
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

            AtomicReference<String> sampleFailure = new AtomicReference<>();
            List<CompletableFuture<UploadResult>> futures = candidates.stream()
                    .map(candidate -> CompletableFuture.supplyAsync(
                            () -> upload(candidate, sampleFailure),
                            uploadExecutor
                    ))
                    .toList();

            int uploaded = 0;
            int missing = 0;
            int failed = 0;
            for (int index = 0; index < futures.size(); index++) {
                try {
                    UploadResult result = futures.get(index).join();
                    uploaded += result == UploadResult.UPLOADED ? 1 : 0;
                    missing += result == UploadResult.MISSING ? 1 : 0;
                    failed += result == UploadResult.FAILED ? 1 : 0;
                } catch (CompletionException exception) {
                    failed++;
                    sampleFailure.compareAndSet(null, failureDetails(
                            candidates.get(index).imageId(), "worker", exception.getCause()
                    ));
                    log.debug("B2 upload task failed unexpectedly", exception.getCause());
                }
            }
            logBatchSummary(candidates.size(), uploaded, missing, failed, startedNanos, sampleFailure.get());
            return uploaded;
        } finally {
            running.set(false);
        }
    }

    private UploadResult upload(CloudUploadCandidate candidate, AtomicReference<String> sampleFailure) {
        Path source = imageRoot.resolve(candidate.relativePath()).normalize();
        boolean localAvailable = isSafeRegularFile(source);
        boolean prepared = candidate.objectKey() != null
                && !candidate.objectKey().isBlank()
                && candidate.attemptCount() > 0;
        if (!localAvailable && !prepared) {
            return markUnavailable(candidate, sampleFailure);
        }

        String objectKey = prepared
                ? candidate.objectKey()
                : b2Properties.objectKey(candidate.imageId());
        Instant attemptAt = clock.instant();
        String stage = "markAttemptStarted";
        try {
            uploads.markAttemptStarted(
                    candidate.imageId(),
                    objectKey,
                    attemptAt.plus(b2Properties.uploadRetryDelay())
            );

            stage = "headObject";
            if (prepared && storage.exists(objectKey)) {
                stage = "markUploaded";
                uploads.markUploaded(
                        candidate.imageId(),
                        objectKey,
                        recoveredUploadTime(candidate, attemptAt)
                );
                return UploadResult.UPLOADED;
            }
            if (!localAvailable) {
                stage = "markUnavailable";
                images.markUnavailable(candidate.imageId());
                stage = "clearAttempt";
                uploads.clearAttempt(candidate.imageId());
                return UploadResult.MISSING;
            }

            stage = "putObject";
            storage.upload(objectKey, source);
            stage = "markUploaded";
            uploads.markUploaded(candidate.imageId(), objectKey, attemptAt);
            return UploadResult.UPLOADED;
        } catch (RuntimeException exception) {
            sampleFailure.compareAndSet(null, failureDetails(candidate.imageId(), stage, exception));
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

    private UploadResult markUnavailable(CloudUploadCandidate candidate, AtomicReference<String> sampleFailure) {
        try {
            images.markUnavailable(candidate.imageId());
            return UploadResult.MISSING;
        } catch (RuntimeException exception) {
            sampleFailure.compareAndSet(null, failureDetails(candidate.imageId(), "markUnavailable", exception));
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
            long startedNanos,
            String sampleFailure
    ) {
        if (failed > 0) {
            log.warn(
                    "B2 upload batch completed: candidates={}, uploaded={}, missing={}, failed={}, durationMs={}, sampleFailure={}",
                    candidates,
                    uploaded,
                    missing,
                    failed,
                    (System.nanoTime() - startedNanos) / 1_000_000,
                    sampleFailure
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

    private static String failureDetails(String imageId, String stage, Throwable exception) {
        // Keep one bounded sample per batch; raw messages can contain URLs, SQL or credentials.
        StringBuilder details = new StringBuilder("imageId=").append(diagnosticToken(imageId))
                .append(" stage=").append(stage).append(" cause=");
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (depth > 0) {
                details.append(" <- ");
            }
            details.append(cause.getClass().getSimpleName());
            if (cause instanceof S3Exception s3) {
                details.append("[httpStatus=").append(s3.statusCode())
                        .append(",errorCode=").append(diagnosticToken(
                                s3.awsErrorDetails() == null ? null : s3.awsErrorDetails().errorCode()
                        )).append(']');
            }
            if (cause instanceof SQLException sql) {
                details.append("[sqlState=").append(diagnosticToken(sql.getSQLState())).append(']');
            }
        }
        return details.toString();
    }

    private static String diagnosticToken(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{1,128}") ? value : "unavailable";
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
