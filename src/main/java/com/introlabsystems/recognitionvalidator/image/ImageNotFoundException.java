package com.introlabsystems.recognitionvalidator.image;

public class ImageNotFoundException extends RuntimeException {

    public ImageNotFoundException(String imageId) {
        super("Image is unavailable: " + imageId);
    }
}
