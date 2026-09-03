package com.introlabsystems.recognitionvalidator.ai.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity @Table(name = "ai_queue_settings") @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiQueueSettings {
    @Id private int id;
    @Column(nullable = false) private long revision;
    @Column(nullable = false) private boolean enabled;
}
