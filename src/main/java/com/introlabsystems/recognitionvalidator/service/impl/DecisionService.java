package com.introlabsystems.recognitionvalidator.service.impl;

import com.introlabsystems.recognitionvalidator.dao.jdbc.DailyStatisticsRepository;
import com.introlabsystems.recognitionvalidator.exception.DecisionConflictException;
import com.introlabsystems.recognitionvalidator.model.enums.Decision;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DecisionService {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final DailyStatisticsRepository dailyStatistics;

    @Transactional
    public void decide(String imageId, UUID operatorId, Decision decision) {
        Instant reviewedAt = clock.instant();
        int updated = jdbc.update("""
                UPDATE review_task
                SET status = 'COMPLETED',
                    decision = :decision,
                    reviewed_at = :reviewedAt,
                    lease_expires_at = NULL
                WHERE image_id = :imageId
                  AND status = 'ASSIGNED'
                  AND assigned_to = :operatorId
                """, new MapSqlParameterSource()
                .addValue("imageId", imageId)
                .addValue("operatorId", operatorId)
                .addValue("decision", decision.name())
                .addValue("reviewedAt", Timestamp.from(reviewedAt)));
        if (updated != 1) {
            throw new DecisionConflictException(imageId);
        }
        dailyStatistics.increment(
                operatorId,
                reviewedAt.atZone(ZoneOffset.UTC).toLocalDate(),
                decision == Decision.ACCEPTED
        );
    }
}
