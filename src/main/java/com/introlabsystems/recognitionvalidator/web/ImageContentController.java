package com.introlabsystems.recognitionvalidator.web;

import com.introlabsystems.recognitionvalidator.service.ImageStorageService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/images")
public class ImageContentController {

    private final ImageStorageService storage;

    public ImageContentController(ImageStorageService storage) {
        this.storage = storage;
    }

    @GetMapping("/{imageId}/content")
    ResponseEntity<?> content(@PathVariable String imageId) {
        ImageStorageService.ImageContent content = storage.open(imageId);
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
