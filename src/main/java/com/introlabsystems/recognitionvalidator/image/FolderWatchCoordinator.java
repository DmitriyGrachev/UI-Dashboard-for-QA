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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final ExecutorService executor;
    private WatchService watchService;

    public FolderWatchCoordinator(
            ValidatorProperties properties,
            ImageIndexer imageIndexer,
            ImageFileEventHandler eventHandler
    ) {
        this.imageRoot = properties.imageRoot().toAbsolutePath().normalize();
        this.imageIndexer = imageIndexer;
        this.eventHandler = eventHandler;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "image-folder-watcher");
            thread.setDaemon(true);
            return thread;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            Files.createDirectories(imageRoot);
            watchService = FileSystems.getDefault().newWatchService();
            imageRoot.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.OVERFLOW
            );
            executor.submit(this::watch);
            imageIndexer.scanRoot();
        } catch (IOException exception) {
            log.error("Cannot watch image directory {}", imageRoot, exception);
        }
    }

    private void watch() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        eventHandler.overflow();
                        continue;
                    }

                    Path path = imageRoot.resolve((Path) event.context());
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        eventHandler.created(path);
                    } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                        eventHandler.deleted(path);
                    }
                }
                if (!key.reset()) {
                    log.warn("Image directory watch key is no longer valid for {}", imageRoot);
                    return;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException exception) {
            log.debug("Image directory watcher closed");
        } catch (RuntimeException exception) {
            log.error("Image directory watcher stopped unexpectedly", exception);
        }
    }

    @PreDestroy
    public void close() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException exception) {
                log.warn("Cannot close image directory watcher", exception);
            }
        }
        executor.shutdownNow();
    }
}
