package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.slack.SlackIncidentRepository;
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
import java.util.UUID;

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
    private SlackIncidentRepository incidents;

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
        jdbcTemplate.execute("TRUNCATE TABLE slack_incident_state, slack_notification_outbox RESTART IDENTITY CASCADE");
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
        when(data.daily(any())).thenReturn(new SlackOperationsRepository.Daily(12, 3, 5, 4, 1, 2, 1));
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
                .contains("AI: *5* results; matched 4; unmatched 1; other 0")
                .contains("AI Matched / operator Rejected: *2*")
                .contains("AI Unmatched / operator Accepted: *1*")
                .contains("Counted when the second review completed");

        newMonitor(operationsProperties).check();
        assertThat(messageCount()).isOne();
        assertThat(messageRows().getFirst().get("dedup_key")).isEqualTo("daily:2026-09-06");
    }

    @Test
    void persistentIncidentIsQuietThenUpdatesAndRecursWithANewSeries() {
        var monitor = monitor();

        b2Enabled = true;
        b2Failures = 10;
        checkEveryMinute(monitor, "2026-09-07T10:00:00Z", 11);
        assertThat(messageCount()).isOne();
        UUID firstSeries = seriesAt(0);

        checkAt(monitor, "2026-09-07T10:11:01Z");
        assertThat(messageCount()).isOne();

        b2Failures = 11;
        checkAt(monitor, "2026-09-07T10:24:59Z");
        assertThat(messageCount()).isOne();
        checkAt(monitor, "2026-09-07T10:25:01Z");
        assertThat(messageCount()).isEqualTo(2);
        assertThat(seriesAt(1)).isEqualTo(firstSeries);

        b2Failures = 0;
        checkAt(monitor, "2026-09-07T10:26:01Z");
        assertThat(messageCount()).isEqualTo(3);
        assertThat(seriesAt(2)).isEqualTo(firstSeries);
        assertThat(payloadAt(2)).contains("Recovered");

        b2Failures = 10;
        checkEveryMinute(monitor, "2026-09-07T10:27:01Z", 11);
        assertThat(messageCount()).isEqualTo(4);
        assertThat(seriesAt(3)).isNotEqualTo(firstSeries);
    }

    @Test
    void disabledB2AndPausedAiDoNotAlarmAndCloseExistingIncidents() {
        var monitor = monitor();

        checkAt(monitor, "2026-09-07T10:00:00Z");
        assertThat(messageCount()).isZero();

        b2Enabled = true;
        b2Failures = 10;
        checkEveryMinute(monitor, "2026-09-07T10:00:01Z", 11);
        assertThat(messageCount()).isOne();
        b2Enabled = false;
        checkAt(monitor, "2026-09-07T10:11:00Z");
        assertThat(messageCount()).isEqualTo(2);
        assertThat(payloadAt(1)).contains("Closed: uploads disabled");

        aiEnabled = true;
        aiPending = 1;
        checkEveryMinute(monitor, "2026-09-07T10:11:01Z", 16);
        assertThat(messageCount()).isEqualTo(3);
        aiEnabled = false;
        checkAt(monitor, "2026-09-07T10:27:01Z");
        assertThat(messageCount()).isEqualTo(4);
        assertThat(payloadAt(3)).contains("Closed: AI paused");
    }

    @Test
    void monitoringGapResetsPreAlertGrace() {
        var monitor = monitor();
        b2Enabled = true;
        b2Failures = 10;

        checkAt(monitor, "2026-09-07T10:00:00Z");
        checkAt(monitor, "2026-09-07T10:05:00Z");
        checkEveryMinute(monitor, "2026-09-07T10:06:00Z", 9);
        assertThat(messageCount()).isZero();

        checkAt(monitor, "2026-09-07T10:15:00Z");
        assertThat(messageCount()).isOne();
    }

    private SlackOperationsMonitor monitor() {
        return newMonitor(incidentOnlyProperties());
    }

    private SlackOperationsMonitor newMonitor(SlackOperationsProperties properties) {
        return new SlackOperationsMonitor(slackProperties(), properties, data, incidents, outbox, jdbcTemplate, clock);
    }

    private SlackOperationsProperties incidentOnlyProperties() {
        return new SlackOperationsProperties(false, true, operationsProperties.summaryTimeUtc(),
                operationsProperties.pollInterval(), operationsProperties.incidentDelay(),
                operationsProperties.stallDuration(), operationsProperties.updateInterval(),
                operationsProperties.failedUploadThreshold(), operationsProperties.expiredLeaseThreshold());
    }

    private void checkAt(SlackOperationsMonitor monitor, String instant) {
        clock.set(Instant.parse(instant));
        monitor.check();
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

    private UUID seriesAt(int index) {
        return (UUID) messageRows().get(index).get("cycle_id");
    }

    private String payloadAt(int index) {
        return (String) messageRows().get(index).get("archive_payload");
    }

    private void checkEveryMinute(SlackOperationsMonitor monitor, String start, int checks) {
        Instant instant = Instant.parse(start);
        for (int i = 0; i < checks; i++) {
            checkAt(monitor, instant.plus(Duration.ofMinutes(i)).toString());
        }
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
