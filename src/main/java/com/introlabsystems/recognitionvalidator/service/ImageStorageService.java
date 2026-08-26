package com.introlabsystems.recognitionvalidator.service;

import org.springframework.core.io.InputStreamResource;

import java.net.URI;

public interface ImageStorageService {

    BrowserDelivery openForBrowser(String imageId);

    boolean verifyForBrowser(String imageId);

    ImageContent open(String imageId);

    sealed interface BrowserDelivery permits BrowserDelivery.Local, BrowserDelivery.Redirect {

        record Local(ImageContent content) implements BrowserDelivery {
        }

        record Redirect(URI location) implements BrowserDelivery {
        }
    }

    record ImageContent(
            InputStreamResource resource,
            long contentLength,
            String fileName
    ) {
    }
}
