package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.dao.jdbc.RejectedScreenshotExportRepository;
import com.introlabsystems.recognitionvalidator.slack.RejectedBacklogRepository;
import com.introlabsystems.recognitionvalidator.slack.SlackMessageFormatter;
import com.introlabsystems.recognitionvalidator.slack.SlackNotificationOutboxRepository;
import com.introlabsystems.recognitionvalidator.slack.SlackProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/** Commits the download markers and its durable notification as one short transaction. */
@Service
@RequiredArgsConstructor
public class RejectedScreenshotExportCompletion {

    private final RejectedScreenshotExportRepository exports;
    private final RejectedBacklogRepository backlog;
    private final SlackMessageFormatter formatter;
    private final SlackNotificationOutboxRepository outbox;
    private final SlackProperties slackProperties;

    @Transactional
    public int complete(
            UUID exportId,
            String administrator,
            Collection<String> writtenIds,
            Instant completedAt
    ) {
        int newlyMarked = exports.markDownloaded(writtenIds, completedAt);
        if (newlyMarked > 0 && administrator != null && !administrator.isBlank()) {
            long waiting = backlog.snapshot(slackProperties.rejectedDetailsLimit()).count();
            String text = formatter.archive(
                    administrator,
                    writtenIds.size(),
                    completedAt,
                    waiting,
                    slackProperties.rejectedArchiveUrl()
            );
            outbox.enqueueArchive(exportId, completedAt, text, slackProperties.enabled());
        }
        return newlyMarked;
    }
}
