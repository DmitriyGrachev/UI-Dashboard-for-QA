package com.introlabsystems.recognitionvalidator.exception;

public final class AdminUserException extends RuntimeException {

    private final String code;

    public AdminUserException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
