package com.introlabsystems.recognitionvalidator.slack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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
@Slf4j
public class SlackOperationsMonitor {
    private final SlackProperties slack;
    private final SlackOperationsProperties properties;
    private final SlackOperationsRepository data;
    private final SlackIncidentRepository incidents;
    private final SlackNotificationOutboxRepository outbox;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${slack.operations.poll-interval:5m}", initialDelayString = "${slack.operations.poll-interval:5m}",
            scheduler = "slackMonitorScheduler")
    public void check() {
        if (!slack.enabled() || slack.botToken() == null || slack.botToken().isBlank()
                || slack.channelId() == null || slack.channelId().isBlank()
                || (!properties.dailySummaryEnabled() && !properties.alertsEnabled())) return;
        Instant now = clock.instant();
        var dateTime = now.atOffset(ZoneOffset.UTC);
        var day = dateTime.toLocalDate().minusDays(1);
        String dailyKey = "daily:" + day;
        boolean dailyDue = properties.dailySummaryEnabled()
                && !dateTime.toLocalTime().isBefore(properties.summaryTimeUtc())
                && !Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM slack_notification_outbox WHERE dedup_key=?)", Boolean.class, dailyKey));
        if (!properties.alertsEnabled() && !dailyDue) return;
        SlackOperationsRepository.Metrics metrics = currentMetrics(now);
        if (dailyDue) {
            var daily = data.daily(day);
            String text = "*Daily summary — " + day + " (UTC)*\n"
                    + "Operators: *" + daily.operatorChecked() + "* checked; *" + daily.operatorRejected() + "* rejected\n"
                    + "AI: *" + daily.aiChecked() + "* results; matched " + daily.aiMatch() + "; unmatched " + daily.aiRejected()
                    + "; other " + (daily.aiChecked() - daily.aiMatch() - daily.aiRejected()) + "\n"
                    + "*AI / operator disagreements* (both reviews completed)\n"
                    + "AI Matched / operator Rejected: *" + daily.aiMatchedOperatorRejected() + "*\n"
                    + "AI Unmatched / operator Accepted: *" + daily.aiUnmatchedOperatorAccepted() + "*\n"
                    + "Counted when the second review completed; disagreement does not identify which reviewer was wrong.\n"
                    + "*Current status*\n" + dataStatusOrUnavailable(metrics);
            outbox.enqueueMessage(dailyKey, UUID.nameUUIDFromBytes(dailyKey.getBytes(StandardCharsets.UTF_8)), text, now);
        }
        if (!properties.alertsEnabled() || metrics == null) return;
        var b2 = metrics.b2();
        String b2Details = b2.enabled()
                ? "Pending uploads: " + b2.backlog() + "; repeated attempts: " + b2.repeatedAttempts()
                : "B2 uploads are disabled.";
        String b2Closure = b2.enabled() ? "Recovered" : "Closed: uploads disabled";
        incidents.observe("b2-errors", "B2 upload retries",
                b2.enabled() && b2.repeatedAttempts() >= properties.failedUploadThreshold(),
                b2Details, b2Closure, properties.incidentDelay(), now);
        incidents.observe("b2-stalled", "B2 uploads stalled",
                b2.enabled() && b2.backlog() > 0 && stale(b2.lastUpload(), now),
                b2Details, b2Closure, properties.stallDuration(), now);
        String aiDetails = metrics.aiEnabled()
                ? "Eligible pending: " + metrics.aiEligiblePending() + "; processing: " + metrics.aiProcessing()
                    + "; expired leases: " + metrics.aiExpired()
                : "AI task delivery is paused.";
        String aiClosure = metrics.aiEnabled() ? "Recovered" : "Closed: AI paused";
        incidents.observe("ai-stalled", "AI results stalled",
                metrics.aiEnabled() && metrics.aiEligiblePending() + metrics.aiProcessing() > 0
                        && stale(metrics.aiLastResult(), now),
                aiDetails, aiClosure, properties.stallDuration(), now);
        incidents.observe("ai-leases", "AI expired leases",
                metrics.aiEnabled() && metrics.aiExpired() >= properties.expiredLeaseThreshold(),
                aiDetails, aiClosure, properties.incidentDelay(), now);
    }

    private boolean stale(Instant lastSuccess, Instant now) {
        return lastSuccess == null || !lastSuccess.isAfter(now.minus(properties.stallDuration()));
    }

    private SlackOperationsRepository.Metrics currentMetrics(Instant now) {
        try {
            return data.snapshot(now);
        } catch (DataAccessException exception) {
            log.warn("Slack operational snapshot unavailable: {}", exception.getMessage());
            return null;
        }
    }

    private String dataStatusOrUnavailable(SlackOperationsRepository.Metrics metrics) {
        if (metrics == null) {
            return "Current status temporarily unavailable.";
        }
        try {
            return dataStatus(metrics);
        } catch (DataAccessException exception) {
            log.warn("Slack current status unavailable: {}", exception.getMessage());
            return "Current status temporarily unavailable.";
        }
    }

    private String dataStatus(SlackOperationsRepository.Metrics metrics) {
        var b2 = metrics.b2();
        return "Exportable rejects awaiting download: *" + data.pendingRejects() + "*\n"
                + "AI delivery: " + (metrics.aiEnabled() ? "enabled" : "paused")
                + "; eligible pending: " + metrics.aiEligiblePending() + "; processing: " + metrics.aiProcessing() + "\n"
                + "B2: " + (b2.enabled() ? "enabled" : "disabled") + "; pending: " + b2.backlog()
                + "; repeated attempts: " + b2.repeatedAttempts();
    }
}
