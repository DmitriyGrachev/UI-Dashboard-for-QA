package com.introlabsystems.recognitionvalidator.ai.repository;

import com.introlabsystems.recognitionvalidator.ai.model.AiResultState;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.web.server.ResponseStatusException;

/** Shared by the operator queue and screenshot browser; never joins result rows into pagination. */
public final class AiResultFilterSql {
    private AiResultFilterSql() {}

    public static void validate(Integer from, Integer to) {
        if ((from != null && (from < 0 || from > 100))
                || (to != null && (to < 0 || to > 100))
                || (from != null && to != null && from > to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid AI certainty range (0–100)");
        }
    }

    public static void append(StringBuilder sql, MapSqlParameterSource parameters,
                              AiResultState state, Integer from, Integer to) {
        validate(from, to);
        if (state == AiResultState.UNCHECKED && (from != null || to != null)) {
            sql.append(" AND FALSE");
            return;
        }
        if ((state == null || state == AiResultState.ALL) && from == null && to == null) return;
        sql.append(state == AiResultState.UNCHECKED ? " AND NOT EXISTS (" : " AND EXISTS (");
        sql.append("SELECT 1 FROM ai_review_task ai WHERE ai.image_id = rt.image_id AND ai.status = 'COMPLETED'");
        if (state == AiResultState.MATCHED) sql.append(" AND ai.valid = TRUE");
        if (state == AiResultState.UNMATCHED) sql.append(" AND ai.valid = FALSE");
        if (from != null) {
            sql.append(" AND ai.certainty >= :aiCertaintyFrom");
            parameters.addValue("aiCertaintyFrom", from);
        }
        if (to != null) {
            sql.append(" AND ai.certainty <= :aiCertaintyTo");
            parameters.addValue("aiCertaintyTo", to);
        }
        sql.append(")");
    }
}
