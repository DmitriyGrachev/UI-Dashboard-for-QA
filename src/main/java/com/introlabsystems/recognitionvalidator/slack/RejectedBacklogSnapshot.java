package com.introlabsystems.recognitionvalidator.slack;

import java.util.List;

public record RejectedBacklogSnapshot(long count, List<RejectedBacklogItem> items) {

    public RejectedBacklogSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
