package com.introlabsystems.recognitionvalidator.slack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@Slf4j
@RequiredArgsConstructor
public class SlackRejectedNotificationServiceImpl implements SlackRejectedNotificationService {

    private final SlackProperties properties;
    private final RejectedBacklogRepository backlog;
    private final SlackMessageFormatter formatter;
    private final SlackWebApiClient slack;
    private final SlackNotificationStateRepository stateRepository;
    private final Clock clock;

    @Override
    public void refreshRejectedBacklog() {
        if (!available()) {
            return;
        }
        try {
            RejectedBacklogSnapshot snapshot = backlog.snapshot(properties.rejectedDetailsLimit());
            if (snapshot.count() == 0) {
                return;
            }
            String text = formatter.backlog(snapshot, properties.rejectedDetailsLimit());
            SlackNotificationState state = stateRepository
                    .findById(SlackNotificationState.SINGLETON_ID)
                    .orElse(null);
            String messageTs = state == null ? null : state.getActiveMessageTs();
            if (messageTs == null || messageTs.isBlank()) {
                stateRepository.save(SlackNotificationState.active(slack.postMessage(text)));
                return;
            }
            try {
                slack.updateMessage(messageTs, text);
            } catch (SlackApiException exception) {
                if (!"message_not_found".equals(exception.errorCode())) {
                    throw exception;
                }
                state.setActiveMessageTs(slack.postMessage(text));
                stateRepository.save(state);
            }
        } catch (RuntimeException exception) {
            log.warn("Slack rejected backlog notification failed: {}", safeMessage(exception));
        }
    }

    @Override
    public void archiveDownloaded(String adminUsername, int exportedCount) {
        if (!available()) {
            return;
        }
        try {
            SlackNotificationState state = stateRepository
                    .findById(SlackNotificationState.SINGLETON_ID)
                    .orElse(null);
            if (state == null || state.getActiveMessageTs() == null
                    || state.getActiveMessageTs().isBlank()) {
                return;
            }
            RejectedBacklogSnapshot snapshot = backlog.snapshot(properties.rejectedDetailsLimit());
            String text = formatter.archive(
                    adminUsername,
                    exportedCount,
                    clock.instant(),
                    snapshot.count()
            );
            try {
                slack.updateMessage(state.getActiveMessageTs(), text);
            } catch (SlackApiException exception) {
                if (!"message_not_found".equals(exception.errorCode())) {
                    throw exception;
                }
                state.setActiveMessageTs(slack.postMessage(text));
            }
            stateRepository.save(state);
        } catch (RuntimeException exception) {
            log.warn("Slack rejected archive notification failed: {}", safeMessage(exception));
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

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message;
    }
}
