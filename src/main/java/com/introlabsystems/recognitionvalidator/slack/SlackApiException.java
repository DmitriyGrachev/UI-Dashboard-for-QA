package com.introlabsystems.recognitionvalidator.slack;

import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

public class SlackApiException extends RuntimeException {

    private final String errorCode;
    private final Duration retryAfter;
    private final Integer statusCode;
    private final boolean retryable;

    public SlackApiException(String message, String errorCode) {
        this(message, errorCode, null, null, false, null);
    }

    public SlackApiException(String message, Throwable cause) {
        this(message, null, null, null, isTransportFailure(cause), cause);
    }

    public SlackApiException(String message, String errorCode, Integer statusCode,
                             Duration retryAfter, boolean retryable) {
        this(message, errorCode, statusCode, retryAfter, retryable, null);
    }

    SlackApiException(String message, String errorCode, Integer statusCode,
                      Duration retryAfter, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryAfter = retryAfter;
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Integer statusCode() {
        return statusCode;
    }

    private static boolean isTransportFailure(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof RestClientException
                    || current instanceof IOException
                    || current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
