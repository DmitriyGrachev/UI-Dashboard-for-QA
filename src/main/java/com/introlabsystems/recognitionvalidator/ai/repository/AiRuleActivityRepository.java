package com.introlabsystems.recognitionvalidator.ai.repository;

import com.introlabsystems.recognitionvalidator.ai.dto.AiRuleActivity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository.instant;
import static com.introlabsystems.recognitionvalidator.ai.repository.AiSettingsRepository.timestamp;

@Repository
@RequiredArgsConstructor
public class AiRuleActivityRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public void issued(Map<UUID, Integer> counts, Instant now) {
        // Fixed lock order even when concurrent claims use differently ordered settings snapshots.
        counts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> jdbc.update("""
                INSERT INTO ai_rule_activity(rule_id,last_issued_at,last_issued_count) VALUES (:rule,:now,:count)
                ON CONFLICT (rule_id) DO UPDATE
                SET last_issued_at=EXCLUDED.last_issued_at,last_issued_count=EXCLUDED.last_issued_count
                WHERE ai_rule_activity.last_issued_at IS NULL OR ai_rule_activity.last_issued_at <= EXCLUDED.last_issued_at
                """, new MapSqlParameterSource("rule", entry.getKey()).addValue("now", timestamp(now)).addValue("count", entry.getValue())));
    }

    public void result(String imageId, Instant now) {
        jdbc.update("""
                INSERT INTO ai_rule_activity(rule_id,last_result_at)
                SELECT issued_rule_id,:now FROM ai_review_task WHERE image_id=:id AND issued_rule_id IS NOT NULL
                ON CONFLICT (rule_id) DO UPDATE SET last_result_at=GREATEST(ai_rule_activity.last_result_at,EXCLUDED.last_result_at)
                """, new MapSqlParameterSource("id", imageId).addValue("now", timestamp(now)));
    }

    public void error(String imageId) {
        jdbc.update("""
                INSERT INTO ai_rule_activity(rule_id,last_error_at,last_error_image_id,last_error_code,last_error_message)
                SELECT issued_rule_id,last_error_at,image_id,last_error_code,last_error_message FROM ai_review_task
                WHERE image_id=:id AND issued_rule_id IS NOT NULL AND last_error_at IS NOT NULL
                ON CONFLICT (rule_id) DO UPDATE SET last_error_at=EXCLUDED.last_error_at,
                  last_error_image_id=EXCLUDED.last_error_image_id,last_error_code=EXCLUDED.last_error_code,
                  last_error_message=EXCLUDED.last_error_message
                WHERE ai_rule_activity.last_error_at IS NULL OR ai_rule_activity.last_error_at < EXCLUDED.last_error_at
                """, new MapSqlParameterSource("id", imageId));
    }

    public List<AiRuleActivity> read(Instant now) {
        // Only currently PROCESSING rows are examined. Completed history is never scanned by polling.
        return jdbc.query("""
                WITH overdue AS (
                  SELECT issued_rule_id,COUNT(*) AS expired,MIN(lease_expires_at) AS oldest_deadline,
                         MIN(image_id) AS expired_image_id
                  FROM ai_review_task WHERE status='PROCESSING' AND lease_expires_at <= :now
                  GROUP BY issued_rule_id
                )
                SELECT r.id AS rule_id,a.last_issued_at,a.last_issued_count,a.last_result_at,
                       COALESCE(o.expired,0) AS expired,o.oldest_deadline,o.expired_image_id,
                       a.last_error_at,a.last_error_image_id,a.last_error_code,a.last_error_message
                FROM ai_selection_rule r LEFT JOIN ai_rule_activity a ON a.rule_id=r.id
                LEFT JOIN overdue o ON o.issued_rule_id=r.id ORDER BY r.priority,r.id
                """, new MapSqlParameterSource("now", timestamp(now)), (rs, row) -> new AiRuleActivity(
                rs.getObject("rule_id", UUID.class), instant(rs, "last_issued_at"), rs.getObject("last_issued_count", Integer.class),
                instant(rs, "last_result_at"), rs.getLong("expired"), instant(rs, "oldest_deadline"), rs.getString("expired_image_id"),
                instant(rs, "last_error_at"), rs.getString("last_error_image_id"), rs.getString("last_error_code"), rs.getString("last_error_message")));
    }
}
