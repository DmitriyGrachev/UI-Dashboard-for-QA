package com.introlabsystems.recognitionvalidator.service;

import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import com.introlabsystems.recognitionvalidator.model.value.ReviewQueueSummary;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded per-process snapshots. Assignment queries never wait for a COUNT. */
@Component
@Slf4j
public class ReviewSummaryCache {
    private static final int MAX_FILTERS = 128;
    private static final Duration TTL = Duration.ofSeconds(5);
    private final LinkedHashMap<ReviewFilters, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final ReviewQueueService queue;
    private final Clock clock;
    private final Executor executor;

    @Autowired
    public ReviewSummaryCache(ReviewQueueService queue, Clock clock) {
        this(queue, clock, Executors.newFixedThreadPool(2));
    }

    ReviewSummaryCache(ReviewQueueService queue, Clock clock, Executor executor) {
        this.queue = queue;
        this.clock = clock;
        this.executor = executor;
    }

    public synchronized Snapshot read(ReviewFilters filters) {
        var key = (filters == null ? ReviewFilters.none() : filters).normalized();
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() == MAX_FILTERS) {
                var removable = entries.entrySet().stream().filter(e -> !e.getValue().refreshing).findFirst();
                if (removable.isEmpty()) return new Snapshot(null, null, true, false);
                entries.remove(removable.get().getKey());
            }
            entry = new Entry();
            entries.put(key, entry);
        }
        if (!entry.refreshing && (entry.retryAt == null || !clock.instant().isBefore(entry.retryAt))) {
            entry.refreshing = true;
            Entry target = entry;
            executor.execute(() -> refresh(key, target));
        }
        return new Snapshot(entry.value, entry.asOf, entry.refreshing, entry.failed);
    }

    private void refresh(ReviewFilters filters, Entry entry) {
        try {
            Instant started = clock.instant();
            ReviewQueueSummary value = queue.summarize(null, filters);
            synchronized (this) {
                entry.value = value;
                entry.asOf = started;
                entry.failed = false;
            }
        } catch (RuntimeException error) {
            synchronized (this) { entry.failed = true; }
            log.warn("Could not refresh shared review count", error);
        } finally {
            synchronized (this) {
                entry.refreshing = false;
                entry.retryAt = clock.instant().plus(TTL);
            }
        }
    }

    @PreDestroy
    public void close() {
        if (executor instanceof ExecutorService service) service.shutdownNow();
    }

    public record Snapshot(ReviewQueueSummary value, Instant asOf, boolean refreshing, boolean failed) {}

    private static class Entry {
        ReviewQueueSummary value;
        Instant asOf;
        Instant retryAt;
        boolean refreshing;
        boolean failed;
    }
}
