package com.introlabsystems.recognitionvalidator.ai.model;

import com.introlabsystems.recognitionvalidator.model.entity.ImageAsset;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.UUID;

/** AI ownership is deliberately stored separately from operator assignments. */
@Entity
@Table(name = "ai_review_task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiReviewTask {
    @Id @Column(name = "image_id", length = 64) private String imageId;
    @MapsId @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_id") @OnDelete(action = OnDeleteAction.CASCADE)
    private ImageAsset image;
    @Column(nullable = false, length = 16) @ColumnDefault("'PENDING'") private String status;
    @Column(name = "file_created_at", nullable = false) private Instant fileCreatedAt;
    @Column(name = "game_code", nullable = false, length = 100) private String gameCode;
    @Column(name = "token_id") private Long tokenId;
    @Column(name = "session_id", length = 128) private String sessionId;
    @Column(name = "is_notification", nullable = false) private boolean notification;
    @Column(name = "has_user_hand", nullable = false) private boolean hasUserHand;
    @Column(name = "file_available", nullable = false) @ColumnDefault("false") private boolean fileAvailable;
    @Column(name = "cloud_available_at") private Instant cloudAvailableAt;
    @Column(name = "claim_id") private UUID claimId;
    @Column(name = "lease_expires_at") private Instant leaseExpiresAt;
    @Column(name = "retry_after") private Instant retryAfter;
    @Column(name = "attempt_count", nullable = false) @ColumnDefault("0") private int attemptCount;
    @Column(name = "issued_rule_id") private UUID issuedRuleId;
    @Column(length = 512) private String expected;
    @Column(length = 32) private String game;
    private Boolean valid;
    @Column(length = 32) private String verdict;
    private Integer certainty;
    private Integer confidence;
    @Column(length = 2000) private String message;
    @Column(name = "checked_at") private Instant checkedAt;
    @Column(name = "last_error_code", length = 64) private String lastErrorCode;
    @Column(name = "last_error_message", length = 1000) private String lastErrorMessage;
    @Column(name = "last_error_at") private Instant lastErrorAt;
}
