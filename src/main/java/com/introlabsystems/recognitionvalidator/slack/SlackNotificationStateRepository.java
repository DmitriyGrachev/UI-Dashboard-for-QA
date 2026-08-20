package com.introlabsystems.recognitionvalidator.slack;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SlackNotificationStateRepository
        extends JpaRepository<SlackNotificationState, Long> {
}
