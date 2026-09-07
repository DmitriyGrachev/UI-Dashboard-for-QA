package com.introlabsystems.recognitionvalidator.dao.jdbc;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Captures each completed AI/operator mismatch once, independently of image retention. */
@Repository
@RequiredArgsConstructor
public class ReviewDisagreementRepository {

    private static final String LOCK_PREFIX = "review-comparison:";
    private static final String INSERT = """
            INSERT INTO review_disagreement (image_id, observed_at, ai_matched)
            SELECT ai.image_id,
                   GREATEST(ai.checked_at, rt.reviewed_at),
                   ai.valid
            FROM ai_review_task ai
            JOIN review_task rt ON rt.image_id = ai.image_id
            WHERE %s
            ON CONFLICT (image_id) DO NOTHING
            """;
    private static final String ELIGIBLE = """
            ai.status = 'COMPLETED'
              AND rt.status = 'COMPLETED'
              AND ai.valid IS NOT NULL
              AND ai.checked_at IS NOT NULL
              AND rt.reviewed_at IS NOT NULL
              AND ((ai.valid = TRUE AND rt.decision = 'REJECTED')
                OR (ai.valid = FALSE AND rt.decision = 'ACCEPTED'))
            """;
    private final NamedParameterJdbcTemplate jdbc;

    public void lockImage(String imageId) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                new MapSqlParameterSource("lockKey", LOCK_PREFIX + imageId),
                rs -> null
        );
    }

    public int capture(String imageId) {
        return jdbc.update(INSERT.formatted("ai.image_id = :imageId AND " + ELIGIBLE),
                new MapSqlParameterSource("imageId", imageId));
    }

    public int backfill() {
        return jdbc.update(INSERT.formatted(ELIGIBLE), new MapSqlParameterSource());
    }
}
