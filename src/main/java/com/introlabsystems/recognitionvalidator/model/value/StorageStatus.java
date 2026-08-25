package com.introlabsystems.recognitionvalidator.model.value;

import java.time.Instant;

public record StorageStatus(
        boolean enabled,
        long uploaded,
        long backlog,
        long dueNow,
        long retrying,
        long localOnly,
        long cloudOnly,
        long bothStores,
        long unavailable,
        Instant oldestPendingAt
) {
}
