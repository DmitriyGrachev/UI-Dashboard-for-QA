package com.introlabsystems.recognitionvalidator.service;

import org.springframework.core.io.InputStreamResource;

public interface ImageStorageService {

    ImageContent open(String imageId);

    record ImageContent(
            InputStreamResource resource,
            long contentLength,
            String fileName
    ) {
    }
}
