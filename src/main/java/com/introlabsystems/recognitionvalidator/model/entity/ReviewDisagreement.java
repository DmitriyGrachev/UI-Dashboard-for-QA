package com.introlabsystems.recognitionvalidator.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Durable comparison result; deliberately has no image foreign key so retention cannot erase it. */
@Entity
@Table(
        name = "review_disagreement",
        indexes = @Index(name = "ix_review_disagreement_observed_at", columnList = "observed_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewDisagreement {

    @Id
    @Column(name = "image_id", length = 64)
    private String imageId;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "ai_matched", nullable = false)
    private boolean aiMatched;
}
