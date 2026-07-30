package com.introlabsystems.recognitionvalidator.review;

import com.introlabsystems.recognitionvalidator.auth.AppUser;
import com.introlabsystems.recognitionvalidator.image.ImageAsset;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "review_task")
public class ReviewTask {

    @Id
    @Column(name = "image_id", length = 64)
    private String imageId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_id")
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

    protected ReviewTask() {
    }

    public ReviewTask(ImageAsset image) {
        this.image = image;
        this.imageId = image.getId();
        this.status = ReviewStatus.PENDING;
    }

    public String getImageId() {
        return imageId;
    }

    public ImageAsset getImage() {
        return image;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public void setStatus(ReviewStatus status) {
        this.status = status;
    }

    public AppUser getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(AppUser assignedTo) {
        this.assignedTo = assignedTo;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }
}
