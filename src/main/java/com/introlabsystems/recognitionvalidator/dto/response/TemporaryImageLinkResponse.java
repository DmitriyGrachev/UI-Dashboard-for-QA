package com.introlabsystems.recognitionvalidator.dto.response;

import com.introlabsystems.recognitionvalidator.service.ImageStorageService;

import java.time.Instant;

public record TemporaryImageLinkResponse(String url, Instant expiresAt) {

    public static TemporaryImageLinkResponse from(ImageStorageService.TemporaryLink link) {
        return new TemporaryImageLinkResponse(link.url().toString(), link.expiresAt());
    }
}
