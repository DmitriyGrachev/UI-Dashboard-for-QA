package com.introlabsystems.recognitionvalidator.slack;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class SlackRejectedNotificationListener {

    private final SlackRejectedNotificationService notifications;

    @Async("slackNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRejectedDecision(RejectedDecisionEvent event) {
        notifications.refreshRejectedBacklog();
    }

    @Async("slackNotificationExecutor")
    @EventListener
    public void onArchiveDownloaded(RejectedArchiveDownloadedEvent event) {
        notifications.archiveDownloaded(event.adminUsername(), event.exportedCount());
    }
}
