package com.introlabsystems.recognitionvalidator.image;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class ImageFileEventHandler {

    private final ImageIndexer imageIndexer;

    public ImageFileEventHandler(ImageIndexer imageIndexer) {
        this.imageIndexer = imageIndexer;
    }

    public void created(Path path) {
        imageIndexer.index(path);
    }

    public void deleted(Path path) {
        imageIndexer.markUnavailable(path);
    }

    public void overflow() {
        imageIndexer.scanRoot();
    }
}
