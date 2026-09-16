package com.introlabsystems.recognitionvalidator.dao.jdbc;

import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

/** Hints only: callers must recheck eligibility and acquire the database row lock. */
@Component
public class ReviewCandidateBuffer {
    private final LinkedHashMap<ReviewFilters, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final Clock clock;
    private final int batchSize;

    public ReviewCandidateBuffer(Clock clock, @Value("${validator.review-candidate-batch-size:30}") int batchSize) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("Candidate batch size must be 1..100");
        this.clock = clock;
        this.batchSize = batchSize;
    }

    public int batchSize() { return batchSize; }

    public List<String> read(ReviewFilters filters, Supplier<List<String>> load) {
        var key = filters.normalized();
        Entry entry;
        synchronized (entries) {
            entry = entries.get(key);
            if (entry == null) {
                if (entries.size() == 128) {
                    var idle = entries.entrySet().stream().filter(e -> e.getValue().users == 0).findFirst();
                    if (idle.isEmpty()) return List.of();
                    entries.remove(idle.get().getKey());
                }
                entry = new Entry();
                entries.put(key, entry);
            }
            entry.users++;
        }
        try {
            synchronized (entry) {
                if (entry.ids.isEmpty() || entry.expires == null || !clock.instant().isBefore(entry.expires)) {
                    entry.ids = new ArrayList<>(load.get().stream().limit(batchSize).toList());
                    entry.expires = clock.instant().plus(Duration.ofSeconds(5));
                }
                return List.copyOf(entry.ids);
            }
        } finally {
            synchronized (entries) { entry.users--; }
        }
    }

    public void discard(ReviewFilters filters, Collection<String> ids) {
        Entry entry;
        synchronized (entries) { entry = entries.get(filters.normalized()); }
        if (entry != null) synchronized (entry) { entry.ids.removeAll(ids); }
    }

    /** Opportunistic preview only: never refills, removes an ID, or reserves a task. */
    public String peek(ReviewFilters filters, String currentImageId) {
        Entry entry;
        synchronized (entries) { entry = entries.get(filters.normalized()); }
        if (entry == null) return null;
        synchronized (entry) {
            if (entry.expires == null || !clock.instant().isBefore(entry.expires)) return null;
            return entry.ids.stream().filter(id -> !id.equals(currentImageId)).findFirst().orElse(null);
        }
    }

    public void clear() {
        synchronized (entries) { entries.clear(); }
    }

    private static class Entry {
        List<String> ids = new ArrayList<>();
        Instant expires;
        int users;
    }
}
