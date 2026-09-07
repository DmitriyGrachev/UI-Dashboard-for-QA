package com.introlabsystems.recognitionvalidator.slack;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface SlackNotificationStateRepository
        extends JpaRepository<SlackNotificationState, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SlackNotificationState s where s.id = :id")
    java.util.Optional<SlackNotificationState> findLockedById(@Param("id") Long id);
}
