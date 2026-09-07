package com.introlabsystems.recognitionvalidator.slack;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One persistent observation per monitored condition; delivery lives in the existing outbox. */
@Entity
@Table(name = "slack_incident_state")
public class SlackIncidentState {
    @Id @Column(length = 64) private String id;
    private UUID seriesId;
    private boolean active;
    private Instant conditionSince;
    private Instant lastObservedAt;
    private Instant lastSentAt;
    private long sequence;
    @Column(columnDefinition = "TEXT") private String lastDetails;
}
