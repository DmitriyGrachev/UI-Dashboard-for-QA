package com.introlabsystems.recognitionvalidator.slack;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
@Slf4j
public class SlackRejectedNotificationServiceImpl implements SlackRejectedNotificationService {

    private final SlackProperties properties;
    private final RejectedBacklogRepository backlog;
    private final SlackMessageFormatter formatter;
    private final SlackWebApiClient slack;
    private final SlackNotificationStateRepository stateRepository;
    private final Clock clock;
    private final SlackNotificationOutboxRepository outbox;

    @Autowired
    public SlackRejectedNotificationServiceImpl(
            SlackProperties properties,
            RejectedBacklogRepository backlog,
            SlackMessageFormatter formatter,
            SlackWebApiClient slack,
            SlackNotificationStateRepository stateRepository,
            Clock clock,
            SlackNotificationOutboxRepository outbox
    ) {
        this.properties = properties;
        this.backlog = backlog;
        this.formatter = formatter;
        this.slack = slack;
        this.stateRepository = stateRepository;
        this.clock = clock;
        this.outbox = outbox;
    }

    @Override
    public void refreshRejectedBacklog() {
        if (!available()) {
            return;
        }
        outbox.enqueueRefresh(clock.instant());
    }

    /** Called by the database scheduler after it has claimed an outbox row. */
    public void deliver(SlackNotificationOutboxRepository.OutboxItem operation) {
        switch (operation.operationKind()) {
            case REFRESH -> deliverRefresh(operation);
            case ARCHIVE -> deliverArchive(operation);
        }
    }

    public boolean canDeliver() {
        return available();
    }

    private void deliverRefresh(SlackNotificationOutboxRepository.OutboxItem operation) {
        SlackNotificationState state = stateRepository
                .findById(SlackNotificationState.SINGLETON_ID).orElse(null);
        if (state == null || !operation.cycleId().equals(state.getActiveCycleId())) {
            return;
        }
        RejectedBacklogSnapshot snapshot = backlog.snapshot(properties.rejectedDetailsLimit());
        if (snapshot.count() == 0) {
            return;
        }
        String text = formatter.backlog(
                snapshot,
                properties.rejectedDetailsLimit(),
                properties.rejectedArchiveUrl()
        );
        String messageTs = state.getActiveMessageTs();
        if (messageTs == null || messageTs.isBlank()) {
            outbox.attachMessageToCycle(operation.cycleId(), slack.postMessage(text));
            return;
        }
        try {
            slack.updateMessage(messageTs, text);
        } catch (SlackApiException exception) {
            if (!"message_not_found".equals(exception.errorCode())) {
                throw exception;
            }
            outbox.attachMessageToCycle(operation.cycleId(), slack.postMessage(text));
        }
    }

    private void deliverArchive(SlackNotificationOutboxRepository.OutboxItem operation) {
        String target = outbox.targetMessageTs(operation.id());
        if (target == null || target.isBlank()) {
            slack.postMessage(operation.archivePayload());
            return;
        }
        try {
            slack.updateMessage(target, operation.archivePayload());
        } catch (SlackApiException exception) {
            if (!"message_not_found".equals(exception.errorCode())) {
                throw exception;
            }
            // A replacement is a closed historical record, never the next active pointer.
            slack.postMessage(operation.archivePayload());
        }
    }

    private boolean available() {
        if (!properties.enabled()) {
            return false;
        }
        if (properties.botToken() == null || properties.botToken().isBlank()
                || properties.channelId() == null || properties.channelId().isBlank()) {
            log.warn("Slack rejected notifications are enabled but credentials are incomplete");
            return false;
        }
        return true;
    }

}
