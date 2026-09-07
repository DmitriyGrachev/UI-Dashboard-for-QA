package com.introlabsystems.recognitionvalidator.slack;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SlackIncidentRepository {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm 'UTC'")
            .withLocale(java.util.Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private final JdbcTemplate jdbc;
    private final SlackNotificationOutboxRepository outbox;
    private final SlackOperationsProperties properties;

    @Transactional(timeout = 5)
    public void observe(String key, String title, boolean unhealthy, String details,
                        String closure, Duration delay, Instant now) {
        jdbc.update("INSERT INTO slack_incident_state (id, active, sequence) VALUES (?, false, 0) ON CONFLICT DO NOTHING", key);
        var state = jdbc.queryForObject("SELECT * FROM slack_incident_state WHERE id=? FOR UPDATE", (rs, row) ->
                new State(rs.getObject("series_id", UUID.class), rs.getBoolean("active"),
                        instant(rs.getTimestamp("condition_since")), instant(rs.getTimestamp("last_observed_at")),
                        instant(rs.getTimestamp("last_sent_at")), rs.getLong("sequence"), rs.getString("last_details")), key);
        if (state.lastObserved != null && !now.isAfter(state.lastObserved)) return;
        Instant since = state.since;
        UUID series = state.series;
        boolean active = state.active;
        long sequence = state.sequence;
        Instant sentAt = state.sentAt;
        String lastDetails = state.details;
        String text = null;
        if (unhealthy) {
            // A monitoring gap cannot establish that a newly observed problem persisted throughout it.
            if (since == null || (!active && state.lastObserved != null
                    && Duration.between(state.lastObserved, now).compareTo(properties.pollInterval().multipliedBy(3)) > 0)) {
                since = now;
            }
            if (!active && !now.isBefore(since.plus(delay))) {
                series = UUID.randomUUID();
                sequence = 0;
                active = true;
                text = "*Alert — " + title + "*\n" + details + "\nObserved since " + TIME.format(since);
            } else if (active && !details.equals(lastDetails)
                    && !now.isBefore(sentAt.plus(properties.updateInterval()))) {
                text = "*Alert — " + title + "*\n" + details + "\nUpdated " + TIME.format(now);
            }
        } else {
            since = null;
            if (active) {
                active = false;
                text = "*" + closure + " — " + title + "*\n" + details + "\n" + TIME.format(now);
            }
        }
        if (text != null) {
            sequence++;
            outbox.enqueueMessage("incident:" + series + ":" + sequence, series, text, now);
            sentAt = now;
            lastDetails = details;
        }
        jdbc.update("""
                UPDATE slack_incident_state SET series_id=?, active=?, condition_since=?, last_observed_at=?,
                  last_sent_at=?, sequence=?, last_details=? WHERE id=?
                """, series, active, timestamp(since), timestamp(now), timestamp(sentAt), sequence, lastDetails, key);
    }

    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private record State(UUID series, boolean active, Instant since, Instant lastObserved,
                         Instant sentAt, long sequence, String details) {}
}
