package com.introlabsystems.recognitionvalidator.image;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

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

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "recognition_duration_ms")
    private Long recognitionDurationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 16)
    private ParseStatus parseStatus;

    protected ImageAsset() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public Instant getFileCreatedAt() {
        return fileCreatedAt;
    }

    public void setFileCreatedAt(Instant fileCreatedAt) {
        this.fileCreatedAt = fileCreatedAt;
    }

    public Instant getFileModifiedAt() {
        return fileModifiedAt;
    }

    public void setFileModifiedAt(Instant fileModifiedAt) {
        this.fileModifiedAt = fileModifiedAt;
    }

    public Instant getDiscoveredAt() {
        return discoveredAt;
    }

    public void setDiscoveredAt(Instant discoveredAt) {
        this.discoveredAt = discoveredAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public boolean isFileAvailable() {
        return fileAvailable;
    }

    public void setFileAvailable(boolean fileAvailable) {
        this.fileAvailable = fileAvailable;
    }

    public String getGameCode() {
        return gameCode;
    }

    public void setGameCode(String gameCode) {
        this.gameCode = gameCode;
    }

    public Long getTokenId() {
        return tokenId;
    }

    public void setTokenId(Long tokenId) {
        this.tokenId = tokenId;
    }

    public UUID getSessionUuid() {
        return sessionUuid;
    }

    public void setSessionUuid(UUID sessionUuid) {
        this.sessionUuid = sessionUuid;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getDealerCards() {
        return dealerCards;
    }

    public void setDealerCards(String dealerCards) {
        this.dealerCards = dealerCards;
    }

    public String getActiveUserCards() {
        return activeUserCards;
    }

    public void setActiveUserCards(String activeUserCards) {
        this.activeUserCards = activeUserCards;
    }

    public String getInactiveUserCards() {
        return inactiveUserCards;
    }

    public void setInactiveUserCards(String inactiveUserCards) {
        this.inactiveUserCards = inactiveUserCards;
    }

    public String getPayloadRaw() {
        return payloadRaw;
    }

    public void setPayloadRaw(String payloadRaw) {
        this.payloadRaw = payloadRaw;
    }

    public String getButtonsRaw() {
        return buttonsRaw;
    }

    public void setButtonsRaw(String buttonsRaw) {
        this.buttonsRaw = buttonsRaw;
    }

    public boolean isNotification() {
        return notification;
    }

    public void setNotification(boolean notification) {
        this.notification = notification;
    }

    public boolean hasStand() {
        return stand;
    }

    public void setStand(boolean stand) {
        this.stand = stand;
    }

    public boolean hasHit() {
        return hit;
    }

    public void setHit(boolean hit) {
        this.hit = hit;
    }

    public boolean hasDoubleAction() {
        return doubleAction;
    }

    public void setDoubleAction(boolean doubleAction) {
        this.doubleAction = doubleAction;
    }

    public boolean hasSplit() {
        return split;
    }

    public void setSplit(boolean split) {
        this.split = split;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public Long getRecognitionDurationMs() {
        return recognitionDurationMs;
    }

    public void setRecognitionDurationMs(Long recognitionDurationMs) {
        this.recognitionDurationMs = recognitionDurationMs;
    }

    public ParseStatus getParseStatus() {
        return parseStatus;
    }

    public void setParseStatus(ParseStatus parseStatus) {
        this.parseStatus = parseStatus;
    }
}
