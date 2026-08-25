package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.service.ImageStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageContentController {

    private final ImageStorageService storage;

    @GetMapping("/{imageId}/content")
    ResponseEntity<?> content(@PathVariable String imageId) {
        ImageStorageService.BrowserDelivery delivery = storage.openForBrowser(imageId);
        if (delivery instanceof ImageStorageService.BrowserDelivery.Redirect redirect) {
            return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .header(HttpHeaders.LOCATION, redirect.location().toString())
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        ImageStorageService.ImageContent content =
                ((ImageStorageService.BrowserDelivery.Local) delivery).content();
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(content.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(content.contentLength())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(content.resource());
    }
}
