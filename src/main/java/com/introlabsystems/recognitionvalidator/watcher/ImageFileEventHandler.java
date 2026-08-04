package com.introlabsystems.recognitionvalidator.watcher;

import com.introlabsystems.recognitionvalidator.indexing.ImageIndexer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ImageFileEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ImageFileEventHandler.class);

    private final ImageIndexer imageIndexer;
    private final ImageEventBuffer buffer;

    public boolean created(Path path) {
        return buffer.record(path, ImageFileChange.CREATED);
    }

    public boolean deleted(Path path) {
        return buffer.record(path, ImageFileChange.DELETED);
    }

    public void overflow() {
        buffer.overflow();
    }

    public boolean shouldFlush() {
        return buffer.shouldFlush();
    }

    public void flush() {
        ImageEventBatch batch = buffer.drain();
        if (batch.reconcile()) {
            log.info("Starting full image directory reconciliation");
            imageIndexer.scanRoot();
            return;
        }
        if (batch.changes().isEmpty()) {
            return;
        }
        List<Path> created = pathsWith(batch.changes(), ImageFileChange.CREATED);
        List<Path> deleted = pathsWith(batch.changes(), ImageFileChange.DELETED);
        long startedAt = System.nanoTime();
        imageIndexer.indexBatch(created);
        imageIndexer.markUnavailableBatch(deleted);
        long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
        if (batch.changes().size() >= 1_000) {
            log.info(
                    "Processed image event batch: created={}, deleted={}, durationMs={}",
                    created.size(),
                    deleted.size(),
                    durationMillis
            );
        } else {
            log.debug(
                    "Processed image event batch: created={}, deleted={}, durationMs={}",
                    created.size(),
                    deleted.size(),
                    durationMillis
            );
        }
    }

    private List<Path> pathsWith(
            Map<Path, ImageFileChange> changes,
            ImageFileChange expected
    ) {
        return changes.entrySet().stream()
                .filter(entry -> entry.getValue() == expected)
                .map(Map.Entry::getKey)
                .toList();
    }
}
