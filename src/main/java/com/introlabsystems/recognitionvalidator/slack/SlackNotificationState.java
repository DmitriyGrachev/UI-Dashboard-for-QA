package com.introlabsystems.recognitionvalidator.slack;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "slack_notification_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlackNotificationState {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "active_message_ts", length = 64)
    private String activeMessageTs;

    @Column(name = "active_cycle_id")
    private UUID activeCycleId;

    @Column(name = "legacy_pointer_retired")
    private Boolean legacyPointerRetired;

    private SlackNotificationState(UUID activeCycleId, String activeMessageTs) {
        this.id = SINGLETON_ID;
        this.activeCycleId = activeCycleId;
        this.activeMessageTs = activeMessageTs;
        this.legacyPointerRetired = activeCycleId == null ? false : true;
    }

    public static SlackNotificationState active(String messageTs) {
        return new SlackNotificationState(null, messageTs);
    }

    public static SlackNotificationState cycle(UUID cycleId) {
        return new SlackNotificationState(cycleId, null);
    }

    public UUID ensureCycle() {
        if (activeCycleId == null) {
            activeCycleId = UUID.randomUUID();
            if (!Boolean.TRUE.equals(legacyPointerRetired)) {
                // A pre-outbox pointer cannot be associated with a cycle safely.
                activeMessageTs = null;
                legacyPointerRetired = true;
            }
        }
        return activeCycleId;
    }

    public void rotateTo(UUID cycleId) {
        activeCycleId = cycleId;
        activeMessageTs = null;
        legacyPointerRetired = true;
    }

    public void setActiveMessageTs(String messageTs) {
        this.activeMessageTs = messageTs;
    }
}
