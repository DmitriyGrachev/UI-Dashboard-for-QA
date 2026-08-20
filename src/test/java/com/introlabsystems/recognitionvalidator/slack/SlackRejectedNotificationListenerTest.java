package com.introlabsystems.recognitionvalidator.slack;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SlackRejectedNotificationListenerTest {

    @Test
    void archiveEventDelegatesToNotificationService() {
        SlackRejectedNotificationService notifications =
                mock(SlackRejectedNotificationService.class);
        SlackRejectedNotificationListener listener =
                new SlackRejectedNotificationListener(notifications);

        listener.onArchiveDownloaded(new RejectedArchiveDownloadedEvent("alice", 4));

        verify(notifications).archiveDownloaded("alice", 4);
    }
}
