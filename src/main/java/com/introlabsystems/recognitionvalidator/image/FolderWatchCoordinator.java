package com.introlabsystems.recognitionvalidator.image;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        prefix = "validator",
        name = "watch-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class FolderWatchCoordinator {

    private static final Logger log = LoggerFactory.getLogger(FolderWatchCoordinator.class);

    private final Path imageRoot;
    private final ImageIndexer imageIndexer;
    private final ImageFileEventHandler eventHandler;
    private final Duration flushInterval;
    private final Clock clock;
    private final ImageProcessingBackoff processorBackoff = new ImageProcessingBackoff();
    private final ImageProcessingBackoff watcherBackoff = new ImageProcessingBackoff();
    private final ExecutorService watcherExecutor;
    private final ScheduledExecutorService processorExecutor;
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Object watchServiceLock = new Object();
    private volatile boolean processorReady;
    private volatile WatchService watchService;

    public FolderWatchCoordinator(
            ValidatorProperties properties,
            ImageIndexer imageIndexer,
            ImageFileEventHandler eventHandler,
            Clock clock
    ) {
        this.imageRoot = properties.imageRoot().toAbsolutePath().normalize();
        this.imageIndexer = imageIndexer;
        this.eventHandler = eventHandler;
        this.flushInterval = properties.watchFlushInterval();
        this.clock = clock;
        this.watcherExecutor = Executors.newSingleThreadExecutor(
                task -> daemonThread(task, "image-folder-watcher")
        );
        this.processorExecutor = Executors.newSingleThreadScheduledExecutor(
                task -> daemonThread(task, "image-event-processor")
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            installWatchService();
        } catch (IOException | RuntimeException exception) {
            log.error(
                    "Cannot initially watch image directory {}; recovery will retry",
                    imageRoot,
                    exception
            );
        }
        watcherExecutor.submit(this::watch);
        try {
            imageIndexer.scanRoot();
        } catch (RuntimeException exception) {
            processingFailed(exception);
        }
        long delayMillis = Math.max(1, flushInterval.toMillis());
        processorExecutor.scheduleWithFixedDelay(
                this::flushSafely,
                delayMillis,
                delayMillis,
                TimeUnit.MILLISECONDS
        );
        processorReady = true;
        if (eventHandler.shouldFlush()) {
            requestFlush();
        }
    }

    private void watch() {
        boolean reconciliationRequired = watchService == null;
        while (!stopping.get() && !Thread.currentThread().isInterrupted()) {
            WatchService current = watchService;
            if (current == null) {
                try {
                    current = installWatchService();
                    watcherBackoff.reset();
                    log.info("Image directory watcher registered for {}", imageRoot);
                    if (reconciliationRequired) {
                        eventHandler.overflow();
                        requestFlush();
                        reconciliationRequired = false;
                    }
                } catch (IOException | RuntimeException exception) {
                    if (!waitForWatcherRetry("Cannot register image directory watcher", exception)) {
                        return;
                    }
                    continue;
                }
            }

            try {
                consumeEvents(current);
                if (stopping.get()) {
                    return;
                }
                reconciliationRequired = true;
                closeWatchService(current);
                if (!waitForWatcherRetry("Image directory watch key is no longer valid", null)) {
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException exception) {
                closeWatchService(current);
                if (stopping.get()) {
                    log.debug("Image directory watcher closed");
                    return;
                }
                reconciliationRequired = true;
                if (!waitForWatcherRetry("Image directory watcher closed unexpectedly", exception)) {
                    return;
                }
            } catch (RuntimeException exception) {
                closeWatchService(current);
                reconciliationRequired = true;
                if (!waitForWatcherRetry("Image directory watcher stopped unexpectedly", exception)) {
                    return;
                }
            }
        }
    }

    private void consumeEvents(WatchService service) throws InterruptedException {
        while (!stopping.get()) {
            WatchKey key = service.take();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    log.warn("Native image directory event queue overflowed; scheduling reconciliation");
                    eventHandler.overflow();
                    requestFlush();
                    continue;
                }

                Path path = imageRoot.resolve((Path) event.context());
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    if (eventHandler.created(path)) {
                        requestFlush();
                    }
                } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                    if (eventHandler.deleted(path)) {
                        requestFlush();
                    }
                }
            }
            if (!key.reset()) {
                return;
            }
        }
    }

    private WatchService installWatchService() throws IOException {
        Files.createDirectories(imageRoot);
        WatchService candidate = FileSystems.getDefault().newWatchService();
        try {
            imageRoot.register(
                    candidate,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.OVERFLOW
            );
            synchronized (watchServiceLock) {
                if (stopping.get()) {
                    candidate.close();
                    throw new ClosedWatchServiceException();
                }
                watchService = candidate;
            }
            return candidate;
        } catch (IOException | RuntimeException exception) {
            try {
                candidate.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    private boolean waitForWatcherRetry(String message, Exception exception) {
        if (stopping.get()) {
            return false;
        }
        Duration retryDelay = watcherBackoff.recordFailure(clock.instant());
        if (exception == null) {
            log.warn(
                    "{} for {}; retrying in {} seconds",
                    message,
                    imageRoot,
                    retryDelay.toSeconds()
            );
        } else {
            log.warn(
                    "{} for {}; retrying in {} seconds",
                    message,
                    imageRoot,
                    retryDelay.toSeconds(),
                    exception
            );
        }
        try {
            Thread.sleep(retryDelay.toMillis());
            return !stopping.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void closeWatchService(WatchService service) {
        synchronized (watchServiceLock) {
            if (watchService == service) {
                watchService = null;
            }
        }
        try {
            service.close();
        } catch (IOException exception) {
            log.warn("Cannot close image directory watcher", exception);
        }
    }

    private void requestFlush() {
        if (!processorReady
                || !processorBackoff.canAttempt(clock.instant())
                || !flushQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            processorExecutor.execute(() -> {
                try {
                    flushSafely();
                } finally {
                    flushQueued.set(false);
                    if (eventHandler.shouldFlush()
                            && processorBackoff.canAttempt(clock.instant())) {
                        requestFlush();
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            flushQueued.set(false);
            if (processorReady) {
                log.warn("Cannot schedule image event flush", exception);
            }
        }
    }

    private void flushSafely() {
        Instant attemptAt = clock.instant();
        if (!processorBackoff.canAttempt(attemptAt)) {
            return;
        }
        try {
            eventHandler.flush();
            processorBackoff.reset();
        } catch (RuntimeException exception) {
            processingFailed(exception);
        }
    }

    private void processingFailed(RuntimeException exception) {
        eventHandler.overflow();
        Duration retryDelay = processorBackoff.recordFailure(clock.instant());
        log.error(
                "Cannot process image events; one reconciliation will retry in {} seconds",
                retryDelay.toSeconds(),
                exception
        );
    }

    private static Thread daemonThread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    @PreDestroy
    public void close() {
        stopping.set(true);
        processorReady = false;
        WatchService current;
        synchronized (watchServiceLock) {
            current = watchService;
            watchService = null;
        }
        if (current != null) {
            try {
                current.close();
            } catch (IOException exception) {
                log.warn("Cannot close image directory watcher", exception);
            }
        }
        watcherExecutor.shutdownNow();
        processorExecutor.shutdownNow();
    }
}
