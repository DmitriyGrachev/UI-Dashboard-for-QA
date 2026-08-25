package com.introlabsystems.recognitionvalidator.slack;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    private SlackNotificationState(String activeMessageTs) {
        this.id = SINGLETON_ID;
        this.activeMessageTs = activeMessageTs;
    }

    public static SlackNotificationState active(String messageTs) {
        return new SlackNotificationState(messageTs);
    }

    public void setActiveMessageTs(String messageTs) {
        this.activeMessageTs = messageTs;
    }
}
