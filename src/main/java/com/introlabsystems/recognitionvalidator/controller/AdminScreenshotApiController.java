package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.ai.service.AiQueueService;
import com.introlabsystems.recognitionvalidator.ai.repository.AiResultFilterSql;

import com.introlabsystems.recognitionvalidator.dto.request.AdminScreenshotSearchRequest;
import com.introlabsystems.recognitionvalidator.dto.response.AdminScreenshotDetailsResponse;
import com.introlabsystems.recognitionvalidator.dto.response.TemporaryImageLinkResponse;
import com.introlabsystems.recognitionvalidator.model.enums.AdminReviewState;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotPage;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotSummary;
import com.introlabsystems.recognitionvalidator.service.AdminScreenshotService;
import com.introlabsystems.recognitionvalidator.service.ImageStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/admin/api/screenshots")
@RequiredArgsConstructor
public class AdminScreenshotApiController {

    private final AdminScreenshotService screenshots;
    private final ImageStorageService storage;
    private final AiQueueService aiQueue;

    @GetMapping
    AdminScreenshotPage search(@ModelAttribute AdminScreenshotSearchRequest request) {
        validate(request, true);
        return screenshots.search(request.toFilters());
    }

    @GetMapping("/summary")
    AdminScreenshotSummary summary(@ModelAttribute AdminScreenshotSearchRequest request) {
        validate(request, false);
        return screenshots.summary(request.toFilters());
    }

    private static void validate(AdminScreenshotSearchRequest request, boolean cursorAllowed) {
        if (request.getCreatedFrom() != null
                && request.getCreatedTo() != null
                && !request.getCreatedFrom().isBefore(request.getCreatedTo())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Created from must be earlier than created to"
            );
        }
        if (request.getReviewState() == AdminReviewState.UNCHECKED
                && (request.getDecision() != null || hasText(request.getReviewedBy()))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Decision and reviewer filters require Checked screenshots"
            );
        }
        AiResultFilterSql.validate(request.getConfidenceFrom(), request.getConfidenceTo());
        boolean hasCursorTime = request.getCursorCreatedAt() != null;
        boolean hasCursorId = hasText(request.getCursorId());
        if (!cursorAllowed && (hasCursorTime || hasCursorId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Summary does not accept a cursor"
            );
        }
        if (hasCursorTime != hasCursorId) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cursor timestamp and image ID must be provided together"
            );
        }
    }

    @GetMapping("/{imageId}")
    AdminScreenshotDetailsResponse details(@PathVariable String imageId) {
        return AdminScreenshotDetailsResponse.from(screenshots.details(imageId), aiQueue.details(imageId));
    }

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
        return imageResponse(content, ContentDisposition.inline());
    }

    @GetMapping("/{imageId}/download")
    ResponseEntity<?> download(@PathVariable String imageId) {
        return imageResponse(storage.open(imageId), ContentDisposition.attachment());
    }

    @GetMapping("/{imageId}/availability")
    ResponseEntity<Void> availability(@PathVariable String imageId) {
        HttpStatus status = storage.verifyForBrowser(imageId)
                ? HttpStatus.NO_CONTENT
                : HttpStatus.NOT_FOUND;
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/{imageId}/temporary-link")
    TemporaryImageLinkResponse temporaryLink(@PathVariable String imageId) {
        return TemporaryImageLinkResponse.from(storage.temporaryCloudLink(imageId));
    }

    private static ResponseEntity<?> imageResponse(
            ImageStorageService.ImageContent content,
            ContentDisposition.Builder disposition
    ) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(content.contentLength())
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        disposition.filename(content.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(content.resource());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
