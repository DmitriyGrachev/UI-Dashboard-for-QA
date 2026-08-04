package com.introlabsystems.recognitionvalidator.model.entity;

import com.introlabsystems.recognitionvalidator.model.enums.ParseStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "image_asset",
        indexes = {
                @Index(
                        name = "ix_image_queue_order",
                        columnList = "file_available,file_created_at,id"
                ),
                @Index(name = "ix_image_game", columnList = "game_code"),
                @Index(name = "ix_image_session", columnList = "session_id"),
                @Index(name = "ix_image_notification", columnList = "is_notification"),
                @Index(name = "ix_image_retention", columnList = "file_created_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageAsset {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "file_name", nullable = false, length = 512)
    private String fileName;

    @Column(name = "relative_path", nullable = false, unique = true, length = 1024)
    private String relativePath;

    @Column(name = "file_created_at", nullable = false)
    private Instant fileCreatedAt;

    @Column(name = "file_modified_at", nullable = false)
    private Instant fileModifiedAt;

    @Column(name = "discovered_at", nullable = false)
    private Instant discoveredAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "file_available", nullable = false)
    private boolean fileAvailable;

    @Column(name = "game_code", nullable = false, length = 100)
    private String gameCode;

    @Column(name = "token_id")
    private Long tokenId;

    @Column(name = "session_uuid")
    private UUID sessionUuid;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "dealer_cards", length = 512)
    private String dealerCards;

    @Column(name = "active_user_cards", length = 512)
    private String activeUserCards;

    @Column(name = "inactive_user_cards", length = 512)
    private String inactiveUserCards;

    @Column(name = "payload_raw", length = 2048)
    private String payloadRaw;

    @Column(name = "buttons_raw", length = 512)
    private String buttonsRaw;

    @Column(name = "is_notification", nullable = false)
    private boolean notification;

    @Column(name = "has_stand", nullable = false)
    private boolean stand;

    @Column(name = "has_hit", nullable = false)
    private boolean hit;

    @Column(name = "has_double", nullable = false)
    private boolean doubleAction;

    @Column(name = "has_split", nullable = false)
    private boolean split;

    @Column(
            name = "has_surrender",
            nullable = false,
            columnDefinition = "boolean default false"
    )
    private boolean surrender;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "recognition_duration_ms")
    private Long recognitionDurationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 16)
    private ParseStatus parseStatus;
}
