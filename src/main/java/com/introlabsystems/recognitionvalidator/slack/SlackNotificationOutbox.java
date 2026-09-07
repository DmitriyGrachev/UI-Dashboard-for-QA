package com.introlabsystems.recognitionvalidator.slack;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "slack_notification_outbox",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_slack_outbox_export",
                columnNames = "export_id"
        ),
        indexes = @Index(
                name = "ix_slack_outbox_due",
                columnList = "delivery_phase,next_attempt_at,id"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlackNotificationOutbox {

    public enum OperationKind { REFRESH, ARCHIVE, MESSAGE }

    public enum DeliveryPhase { PENDING, IN_FLIGHT, DELIVERED, BLOCKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    @Column(name = "export_id")
    private UUID exportId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_kind", nullable = false, length = 16)
    private OperationKind operationKind;

    @Column(name = "archive_payload", columnDefinition = "TEXT")
    private String archivePayload;

    @Column(name = "dedup_key", unique = true, length = 255)
    private String dedupKey;

    @Column(name = "target_message_ts", length = 64)
    private String targetMessageTs;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_phase", nullable = false, length = 16)
    private DeliveryPhase deliveryPhase;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claimed_by", length = 128)
    private String claimedBy;

    @Column(name = "claimed_until")
    private Instant claimedUntil;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
