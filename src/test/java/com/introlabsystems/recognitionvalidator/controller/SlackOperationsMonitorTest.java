package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.slack.SlackNotificationOutboxRepository;
import com.introlabsystems.recognitionvalidator.slack.SlackOperationsMonitor;
import com.introlabsystems.recognitionvalidator.slack.SlackOperationsProperties;
import com.introlabsystems.recognitionvalidator.slack.SlackOperationsRepository;
import com.introlabsystems.recognitionvalidator.slack.SlackProperties;
import com.introlabsystems.recognitionvalidator.model.value.StorageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@org.springframework.test.context.TestPropertySource(properties = "slack.operations.poll-interval=1m")
class SlackOperationsMonitorTest extends AbstractWebIntegrationTest {

    private static final Instant START = Instant.parse("2026-09-07T08:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SlackNotificationOutboxRepository outbox;

    @Autowired
    private SlackOperationsProperties operationsProperties;

    private MutableClock clock;
    private SlackOperationsRepository data;
    private boolean b2Enabled;
    private boolean aiEnabled;
    private long b2Failures;
    private long aiPending;
    private long aiProcessing;

    @BeforeEach
    void resetOperationsState() {
        jdbcTemplate.execute("TRUNCATE TABLE slack_notification_outbox RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO slack_notification_state (id, legacy_pointer_retired)
                VALUES (1, TRUE)
                ON CONFLICT (id) DO NOTHING
                """);
        clock = new MutableClock(START);
        b2Enabled = false;
        aiEnabled = false;
        b2Failures = 0;
        aiPending = 0;
        aiProcessing = 0;
        data = mock(SlackOperationsRepository.class);
        when(data.snapshot(any())).thenAnswer(invocation -> new SlackOperationsRepository.Metrics(
                new StorageStatus(b2Enabled, 0, 0, 0, 0, 0, 0, 0, 0, null),
                aiEnabled, aiPending, aiProcessing, 0, null, b2Failures, null));
        when(data.daily(any())).thenReturn(new SlackOperationsRepository.Daily(12, 3, 5, 4, 1));
        when(data.pendingRejects()).thenReturn(0L);
    }

    @Test
    void dailySummaryWaitsForUtcCutoffUsesYesterdayAndSurvivesRestart() {
        var monitor = newMonitor(operationsProperties);

        clock.set(Instant.parse("2026-09-07T08:59:59Z"));
        monitor.check();
        assertThat(messageCount()).isZero();

        clock.set(Instant.parse("2026-09-07T09:00:00Z"));
        monitor.check();
        assertThat(messageCount()).isOne();
        verify(data).daily(LocalDate.of(2026, 9, 6));
        assertThat(payloadAt(0))
                .contains("Operators: *12* checked; *3* rejected")
                .contains("AI: *5* results; matched 4; unmatched 1; other 0");

        newMonitor(operationsProperties).check();
        assertThat(messageCount()).isOne();
        assertThat(messageRows().getFirst().get("dedup_key")).isEqualTo("daily:2026-09-06");
    }

    private SlackOperationsMonitor newMonitor(SlackOperationsProperties properties) {
        return new SlackOperationsMonitor(slackProperties(), properties, data, outbox, jdbcTemplate, clock);
    }

    private static SlackProperties slackProperties() {
        return new SlackProperties(true, "token", "channel", 10, "", Duration.ofSeconds(2),
                Duration.ofSeconds(3), "https://slack.test");
    }

    private long messageCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM slack_notification_outbox WHERE operation_kind='MESSAGE'",
                Long.class);
    }

    private List<Map<String, Object>> messageRows() {
        return jdbcTemplate.queryForList("""
                SELECT dedup_key, cycle_id, archive_payload
                FROM slack_notification_outbox
                WHERE operation_kind='MESSAGE'
                ORDER BY id
                """);
    }

    private String payloadAt(int index) {
        return (String) messageRows().get(index).get("archive_payload");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
