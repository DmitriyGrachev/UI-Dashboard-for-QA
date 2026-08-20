package com.introlabsystems.recognitionvalidator.slack;

public interface SlackRejectedNotificationService {

    void refreshRejectedBacklog();

    void archiveDownloaded(String adminUsername, int exportedCount);
}
