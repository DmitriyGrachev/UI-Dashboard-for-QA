package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.service.ImageStorageService;
import com.introlabsystems.recognitionvalidator.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
@Tag(name = "Images")
public class ImageContentController {

    private final ImageStorageService storage;

    @GetMapping("/api/images/{imageId}/content")
    ResponseEntity<?> browserContent(@PathVariable String imageId) {
        ImageStorageService.BrowserDelivery delivery = storage.openForBrowser(imageId);
        if (delivery instanceof ImageStorageService.BrowserDelivery.Redirect redirect) {
            return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .header(HttpHeaders.LOCATION, redirect.location().toString())
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        ImageStorageService.ImageContent content =
                ((ImageStorageService.BrowserDelivery.Local) delivery).content();
        return imageResponse(content);
    }

    @GetMapping("/api/integration/images/{imageId}/content")
    @Operation(summary = "Download a claimed screenshot as PNG",
            security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_API_KEY))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PNG image", content = @Content(
                    mediaType = MediaType.IMAGE_PNG_VALUE, schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid API key or image signature", content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    examples = @ExampleObject(value = "{\"status\":401,\"code\":\"UNAUTHORIZED\",\"title\":\"Unauthorized\",\"detail\":\"A valid X-API-Key header or image signature is required.\"}"))),
            @ApiResponse(responseCode = "404", description = "Image not found"),
            @ApiResponse(responseCode = "503", description = "Image storage unavailable")
    })
    ResponseEntity<?> integrationContent(@Parameter(example = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                         @PathVariable String imageId) {
        return imageResponse(storage.open(imageId));
    }

    private ResponseEntity<?> imageResponse(ImageStorageService.ImageContent content) {
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

    @GetMapping("/api/images/{imageId}/availability")
    ResponseEntity<Void> availability(@PathVariable String imageId) {
        HttpStatus status = storage.verifyForBrowser(imageId)
                ? HttpStatus.NO_CONTENT
                : HttpStatus.NOT_FOUND;
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
