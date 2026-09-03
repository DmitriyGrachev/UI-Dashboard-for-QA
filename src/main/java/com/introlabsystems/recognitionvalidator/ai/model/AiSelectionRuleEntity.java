package com.introlabsystems.recognitionvalidator.ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "ai_selection_rule") @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiSelectionRuleEntity {
    @Id private UUID id;
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false) private boolean enabled;
    @Column(nullable = false) private int priority;
    @Column(name = "created_from") private Instant createdFrom;
    @Column(name = "created_to") private Instant createdTo;
    @Column(name = "token_id") private Long tokenId;
    @Column(name = "session_id", length = 128) private String sessionId;
    private Boolean notification;
    @Column(name = "has_user_hand") private Boolean hasUserHand;
}
