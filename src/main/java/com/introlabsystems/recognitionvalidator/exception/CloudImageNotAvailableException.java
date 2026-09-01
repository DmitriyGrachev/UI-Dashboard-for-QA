package com.introlabsystems.recognitionvalidator.exception;

public class CloudImageNotAvailableException extends RuntimeException {

    public CloudImageNotAvailableException() {
        super("Image is not available in B2");
    }
}
