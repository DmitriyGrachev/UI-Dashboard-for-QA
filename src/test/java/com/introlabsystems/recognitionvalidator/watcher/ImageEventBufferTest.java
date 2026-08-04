package com.introlabsystems.recognitionvalidator.watcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ImageEventBufferTest {

    @Test
    void recommendsFlushAtBatchThresholdWithoutRequestingReconciliation() {
        ImageEventBuffer buffer = new ImageEventBuffer(3, 10);

        assertThat(buffer.record(Path.of("one.png"), ImageFileChange.CREATED)).isFalse();
        assertThat(buffer.record(Path.of("two.png"), ImageFileChange.CREATED)).isFalse();
        assertThat(buffer.record(Path.of("three.png"), ImageFileChange.CREATED)).isTrue();

        ImageEventBatch batch = buffer.drain();
        assertThat(batch.reconcile()).isFalse();
        assertThat(batch.changes()).hasSize(3);
    }

    @Test
    void keepsOnlyTheLatestStateForEachNormalizedPath() {
        ImageEventBuffer buffer = new ImageEventBuffer(10);
        Path path = Path.of("shots", "..", "shots", "image.png");

        buffer.record(path, ImageFileChange.CREATED);
        buffer.record(Path.of("shots", "image.png"), ImageFileChange.DELETED);

        ImageEventBatch batch = buffer.drain();
        assertThat(batch.reconcile()).isFalse();
        assertThat(batch.changes()).containsExactlyEntriesOf(
                java.util.Map.of(
                        Path.of("shots", "image.png").toAbsolutePath().normalize(),
                        ImageFileChange.DELETED
                )
        );
        assertThat(buffer.drain().changes()).isEmpty();
    }

    @Test
    void switchesToOneReconciliationWhenTheBoundedBufferOverflows() {
        ImageEventBuffer buffer = new ImageEventBuffer(2);

        buffer.record(Path.of("one.png"), ImageFileChange.CREATED);
        buffer.record(Path.of("two.png"), ImageFileChange.CREATED);
        buffer.record(Path.of("three.png"), ImageFileChange.CREATED);
        buffer.record(Path.of("four.png"), ImageFileChange.DELETED);

        ImageEventBatch batch = buffer.drain();
        assertThat(batch.reconcile()).isTrue();
        assertThat(batch.changes()).isEmpty();
        assertThat(buffer.drain().reconcile()).isFalse();
    }

    @Test
    void coalescesTenThousandDistinctEventsWithoutReconciliation() {
        ImageEventBuffer buffer = new ImageEventBuffer(1_000, 50_000);

        for (int index = 0; index < 10_000; index++) {
            buffer.record(Path.of("image-%04d.png".formatted(index)), ImageFileChange.CREATED);
        }

        ImageEventBatch batch = buffer.drain();
        assertThat(batch.reconcile()).isFalse();
        assertThat(batch.changes()).hasSize(10_000);
    }
}
