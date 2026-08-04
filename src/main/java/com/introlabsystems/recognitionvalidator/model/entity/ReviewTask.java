package com.introlabsystems.recognitionvalidator.model.entity;

import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import com.introlabsystems.recognitionvalidator.model.enums.ReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(
        name = "review_task",
        indexes = {
                @Index(name = "ix_review_queue", columnList = "status,image_id"),
                @Index(name = "ix_review_assignee", columnList = "assigned_to,status"),
                @Index(name = "ix_review_expired", columnList = "status,lease_expires_at"),
                @Index(
                        name = "ix_review_rejected_export",
                        columnList = "decision,rejected_downloaded_at,image_id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewTask {

    @Id
    @Column(name = "image_id", length = 64)
    private String imageId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ImageAsset image;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private AppUser assignedTo;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Decision decision;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "rejected_downloaded_at")
    private Instant rejectedDownloadedAt;
}
