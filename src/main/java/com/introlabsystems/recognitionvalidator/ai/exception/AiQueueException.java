package com.introlabsystems.recognitionvalidator.ai.exception;

import org.springframework.http.HttpStatus;

public class AiQueueException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AiQueueException(HttpStatus status, String code, String message) {
        super(code + ": " + message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
