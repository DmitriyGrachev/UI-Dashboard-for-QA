package com.introlabsystems.recognitionvalidator.slack;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SlackRejectedNotificationListenerTest {

    @Test
    void rejectedDecisionEnqueuesARefresh() {
        SlackRejectedNotificationService notifications =
                mock(SlackRejectedNotificationService.class);
        SlackRejectedNotificationListener listener =
                new SlackRejectedNotificationListener(notifications);

        listener.onRejectedDecision(new RejectedDecisionEvent("image-1"));

        verify(notifications).refreshRejectedBacklog();
    }
}
