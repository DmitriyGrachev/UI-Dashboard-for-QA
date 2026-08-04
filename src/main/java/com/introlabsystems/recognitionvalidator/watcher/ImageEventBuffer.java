package com.introlabsystems.recognitionvalidator.watcher;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ImageEventBuffer {

    private final int flushThreshold;
    private final int maximumSize;
    private final Map<Path, ImageFileChange> changes = new LinkedHashMap<>();
    private boolean reconcile;

    @Autowired
    public ImageEventBuffer(ValidatorProperties properties) {
        this(properties.batchSize(), properties.watchMaxPendingEvents());
    }

    public ImageEventBuffer(int maximumSize) {
        this(maximumSize, maximumSize);
    }

    ImageEventBuffer(int flushThreshold, int maximumSize) {
        if (flushThreshold < 1 || maximumSize < flushThreshold) {
            throw new IllegalArgumentException(
                    "Flush threshold must be positive and no greater than maximum event count"
            );
        }
        this.flushThreshold = flushThreshold;
        this.maximumSize = maximumSize;
    }

    public synchronized boolean record(Path path, ImageFileChange change) {
        if (reconcile) {
            return true;
        }
        changes.put(path.toAbsolutePath().normalize(), change);
        if (changes.size() > maximumSize) {
            changes.clear();
            reconcile = true;
            return true;
        }
        return changes.size() >= flushThreshold;
    }

    public synchronized void overflow() {
        changes.clear();
        reconcile = true;
    }

    public synchronized boolean shouldFlush() {
        return reconcile || changes.size() >= flushThreshold;
    }

    public synchronized ImageEventBatch drain() {
        ImageEventBatch batch = new ImageEventBatch(changes, reconcile);
        changes.clear();
        reconcile = false;
        return batch;
    }
}
