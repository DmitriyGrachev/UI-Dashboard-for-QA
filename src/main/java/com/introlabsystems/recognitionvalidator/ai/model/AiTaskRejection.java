package com.introlabsystems.recognitionvalidator.ai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_task_rejection", indexes = {
        @Index(name = "uk_ai_task_rejection_claim", columnList = "image_id,claim_id", unique = true)
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiTaskRejection {
    @Id
    private UUID id;
    @Column(name = "image_id", nullable = false, length = 64)
    private String imageId;
    @Column(name = "claim_id", nullable = false)
    private UUID claimId;
    @Column(nullable = false, length = 1000)
    private String message;
    @Column(name = "rejected_at", nullable = false)
    private Instant rejectedAt;
}
