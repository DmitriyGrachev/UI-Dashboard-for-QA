package com.introlabsystems.recognitionvalidator.slack;

public record RejectedArchiveDownloadedEvent(String adminUsername, int exportedCount) {
}
