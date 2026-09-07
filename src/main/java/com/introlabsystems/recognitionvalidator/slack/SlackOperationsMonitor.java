package com.introlabsystems.recognitionvalidator.slack;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SlackOperationsMonitor {
    private final SlackProperties slack;
    private final SlackOperationsProperties properties;
    private final SlackOperationsRepository data;
    private final SlackNotificationOutboxRepository outbox;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${slack.operations.poll-interval:5m}", initialDelayString = "${slack.operations.poll-interval:5m}",
            scheduler = "slackMonitorScheduler")
    public void check() {
        if (!slack.enabled() || slack.botToken() == null || slack.botToken().isBlank()
                || slack.channelId() == null || slack.channelId().isBlank()
                || !properties.dailySummaryEnabled()) return;
        Instant now = clock.instant();
        var dateTime = now.atOffset(ZoneOffset.UTC);
        var day = dateTime.toLocalDate().minusDays(1);
        String dailyKey = "daily:" + day;
        boolean dailyDue = properties.dailySummaryEnabled()
                && !dateTime.toLocalTime().isBefore(properties.summaryTimeUtc())
                && !Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM slack_notification_outbox WHERE dedup_key=?)", Boolean.class, dailyKey));
        if (!dailyDue) return;
        var metrics = data.snapshot(now);
        if (dailyDue) {
            var daily = data.daily(day);
            String text = "*Daily summary — " + day + " (UTC)*\n"
                    + "Operators: *" + daily.operatorChecked() + "* checked; *" + daily.operatorRejected() + "* rejected\n"
                    + "AI: *" + daily.aiChecked() + "* results; matched " + daily.aiMatch() + "; unmatched " + daily.aiRejected()
                    + "; other " + (daily.aiChecked() - daily.aiMatch() - daily.aiRejected()) + "\n"
                    + "*Current status*\n" + dataStatus(metrics);
            outbox.enqueueMessage(dailyKey, UUID.nameUUIDFromBytes(dailyKey.getBytes(StandardCharsets.UTF_8)), text, now);
        }
    }

    private String dataStatus(SlackOperationsRepository.Metrics metrics) {
        var b2 = metrics.b2();
        return "Exportable rejects awaiting download: *" + data.pendingRejects() + "*\n"
                + "AI delivery: " + (metrics.aiEnabled() ? "enabled" : "paused")
                + "; eligible pending: " + metrics.aiEligiblePending() + "; processing: " + metrics.aiProcessing() + "\n"
                + "B2: " + (b2.enabled() ? "enabled" : "disabled") + "; pending: " + b2.backlog()
                + "; repeated attempts: " + metrics.b2RepeatedFailures();
    }
}
