package com.introlabsystems.recognitionvalidator.exception;

public class ImageNotFoundException extends RuntimeException {

    public ImageNotFoundException(String imageId) {
        super("Image is unavailable: " + imageId);
    }
}
