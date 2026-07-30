package com.introlabsystems.recognitionvalidator.review;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class DecisionService {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public DecisionService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

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
    }
}
