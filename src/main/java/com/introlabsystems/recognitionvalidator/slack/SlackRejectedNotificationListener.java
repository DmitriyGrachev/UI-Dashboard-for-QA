package com.introlabsystems.recognitionvalidator.slack;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class SlackRejectedNotificationListener {

    private final SlackRejectedNotificationService notifications;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onRejectedDecision(RejectedDecisionEvent event) {
        notifications.refreshRejectedBacklog();
    }
}
