package com.introlabsystems.recognitionvalidator.dto.request;

import com.introlabsystems.recognitionvalidator.ai.model.AiResultState;

import com.introlabsystems.recognitionvalidator.model.enums.AdminReviewState;
import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import com.introlabsystems.recognitionvalidator.model.enums.ImageStorageState;
import com.introlabsystems.recognitionvalidator.model.enums.ParseStatus;
import com.introlabsystems.recognitionvalidator.model.value.AdminScreenshotFilters;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class AdminScreenshotSearchRequest {

    private Instant createdFrom;
    private Instant createdTo;
    private AdminReviewState reviewState = AdminReviewState.ALL;
    private String gameCode;
    private Long tokenId;
    private String sessionId;
    private String imageId;
    private String fileName;
    private Decision decision;
    private String reviewedBy;
    private ImageStorageState storageState;
    private ParseStatus parseStatus;
    private Boolean notification;
    private Boolean hasUserHand;
    private Instant cursorCreatedAt;
    private String cursorId;
    private int limit = 50;
    private AiResultState aiResult;
    private Integer certaintyFrom;
    private Integer certaintyTo;

    public AdminScreenshotFilters toFilters() {
        return new AdminScreenshotFilters(
                createdFrom,
                createdTo,
                reviewState,
                gameCode,
                tokenId,
                sessionId,
                imageId,
                fileName,
                decision,
                reviewedBy,
                storageState,
                parseStatus,
                notification,
                hasUserHand,
                cursorCreatedAt,
                cursorId,
                Math.clamp(limit, 1, 100),
                aiResult,
                certaintyFrom,
                certaintyTo
        );
    }
}
