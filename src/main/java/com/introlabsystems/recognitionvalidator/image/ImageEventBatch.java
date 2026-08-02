package com.introlabsystems.recognitionvalidator.image;

import java.nio.file.Path;
import java.util.Map;

public record ImageEventBatch(
        Map<Path, ImageFileChange> changes,
        boolean reconcile
) {
    public ImageEventBatch {
        changes = Map.copyOf(changes);
    }
}
