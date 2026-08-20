package com.introlabsystems.recognitionvalidator.slack;

public class SlackApiException extends RuntimeException {

    private final String errorCode;

    public SlackApiException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public SlackApiException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
    }

    public String errorCode() {
        return errorCode;
    }
}
