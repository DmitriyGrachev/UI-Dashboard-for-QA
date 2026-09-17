package com.introlabsystems.recognitionvalidator.ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** One snapshot per rule; independent of task retention and settings replacement. */
@Entity
@Table(name = "ai_rule_activity")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiRuleActivityEntity {
    @Id @Column(name = "rule_id") private UUID ruleId;
    private Instant lastIssuedAt;
    private Integer lastIssuedCount;
    private Instant lastResultAt;
    private Instant lastErrorAt;
    @Column(length = 64) private String lastErrorImageId;
    @Column(length = 64) private String lastErrorCode;
    @Column(length = 1000) private String lastErrorMessage;
}
