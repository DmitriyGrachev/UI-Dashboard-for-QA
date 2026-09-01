package com.introlabsystems.recognitionvalidator.model.value;

import java.time.Instant;
import java.util.List;

public record AdminScreenshotPage(
        List<AdminScreenshotListItem> items,
        Instant nextCreatedAt,
        String nextId
) {
}
