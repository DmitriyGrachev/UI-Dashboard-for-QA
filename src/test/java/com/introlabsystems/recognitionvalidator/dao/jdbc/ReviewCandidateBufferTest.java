package com.introlabsystems.recognitionvalidator.dao.jdbc;

import com.introlabsystems.recognitionvalidator.model.value.ReviewFilters;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReviewCandidateBufferTest {
    @Test
    void thirtyAssignmentsReuseOneSearchAndExpiredBuffersRefresh() {
        Clock clock = mock(Clock.class);
        Instant now = Instant.parse("2026-09-14T10:00:00Z");
        when(clock.instant()).thenReturn(now);
        var buffer = new ReviewCandidateBuffer(clock, 30);
        var searches = new AtomicInteger();
        Supplier<List<String>> load = () -> {
            searches.incrementAndGet();
            return IntStream.range(0, 30).mapToObj(Integer::toString).toList();
        };
        for (int i = 0; i < 30; i++) {
            String id = buffer.read(ReviewFilters.none(), load).getFirst();
            assertThat(id).isEqualTo(Integer.toString(i));
            buffer.discard(ReviewFilters.none(), List.of(id));
        }
        assertThat(searches.get()).isOne();
        buffer.read(ReviewFilters.none(), load);
        when(clock.instant()).thenReturn(now.plusSeconds(6));
        buffer.read(ReviewFilters.none(), load);
        assertThat(searches.get()).isEqualTo(3);
    }

    @Test
    void concurrentReadersShareRefillAndDistinctFiltersStayBounded() throws Exception {
        var buffer = new ReviewCandidateBuffer(Clock.systemUTC(), 30);
        var searches = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 24).mapToObj(i -> pool.submit(() ->
                    buffer.read(ReviewFilters.none(), () -> {
                        searches.incrementAndGet();
                        return List.of("id");
                    }))).toList();
            for (var future : futures) assertThat(future.get()).containsExactly("id");
        }
        assertThat(searches.get()).isOne();
        for (int i = 0; i < 128; i++) buffer.read(new ReviewFilters(null, null, (long)i, null, null, null, null), () -> List.of("other"));
        buffer.read(ReviewFilters.none(), () -> { searches.incrementAndGet(); return List.of("new"); });
        assertThat(searches.get()).isEqualTo(2);
    }
}
