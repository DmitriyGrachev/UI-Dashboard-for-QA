package com.introlabsystems.recognitionvalidator.model.value;

import java.time.Instant;
import java.util.List;

public record AdminScreenshotPage(
        List<AdminScreenshotListItem> items,
        long totalCount,
        Instant oldestCreatedAt,
        Instant newestCreatedAt,
        Instant nextCreatedAt,
        String nextId
) {
}
