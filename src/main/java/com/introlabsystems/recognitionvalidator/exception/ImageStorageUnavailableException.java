package com.introlabsystems.recognitionvalidator.exception;

public class ImageStorageUnavailableException extends RuntimeException {

    public ImageStorageUnavailableException() {
        super("Image storage is temporarily unavailable");
    }

    public ImageStorageUnavailableException(Throwable cause) {
        super("Image storage is temporarily unavailable", cause);
    }
}
