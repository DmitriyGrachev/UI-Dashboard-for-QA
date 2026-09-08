package com.introlabsystems.recognitionvalidator.ai.repository;

import com.introlabsystems.recognitionvalidator.ai.model.AiResultState;
import com.introlabsystems.recognitionvalidator.ai.model.AiVerdict;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.web.server.ResponseStatusException;

/** AI rows are unique by image ID, so an ordered completed-result join cannot duplicate a page. */
public final class AiResultFilterSql {
    private AiResultFilterSql() {}

    public static boolean completedOnly(AiResultState state, AiVerdict verdict,
                                       Integer confidenceFrom, Integer confidenceTo) {
        return state == AiResultState.CHECKED || state == AiResultState.MATCHED
                || state == AiResultState.UNMATCHED
                || verdict != null && verdict != AiVerdict.ALL
                || confidenceFrom != null || confidenceTo != null;
    }

    public static void append(StringBuilder sql, MapSqlParameterSource parameters,
                              AiResultState state, AiVerdict verdict,
                              Integer confidenceFrom, Integer confidenceTo, boolean joined) {
        validate(confidenceFrom, confidenceTo);
        boolean hasDetails = verdict != null && verdict != AiVerdict.ALL
                || confidenceFrom != null || confidenceTo != null;
        if ((state == null || state == AiResultState.ALL) && !hasDetails) return;
        if (state == AiResultState.UNCHECKED && hasDetails) {
            sql.append(" AND FALSE");
            return;
        }
        if (joined) sql.append(" AND ai.status = 'COMPLETED'");
        else {
            sql.append(state == AiResultState.UNCHECKED ? " AND NOT EXISTS (" : " AND EXISTS (");
            sql.append("SELECT 1 FROM ai_review_task ai WHERE ai.image_id = rt.image_id AND ai.status = 'COMPLETED'");
        }
        if (state == AiResultState.MATCHED) sql.append(" AND ai.valid = TRUE");
        if (state == AiResultState.UNMATCHED) sql.append(" AND ai.valid = FALSE");
        if (verdict != null && verdict != AiVerdict.ALL) {
            sql.append(" AND ai.verdict = :aiVerdict");
            parameters.addValue("aiVerdict", verdict.name());
        }
        if (confidenceFrom != null) {
            sql.append(" AND ai.confidence >= :aiConfidenceFrom");
            parameters.addValue("aiConfidenceFrom", confidenceFrom);
        }
        if (confidenceTo != null) {
            sql.append(" AND ai.confidence <= :aiConfidenceTo");
            parameters.addValue("aiConfidenceTo", confidenceTo);
        }
        if (!joined) sql.append(")");
    }

    public static boolean completedOnly(AiResultState state) {
        return completedOnly(state, null, null, null);
    }

    public static void append(StringBuilder sql, AiResultState state, boolean joined) {
        append(sql, new MapSqlParameterSource(), state, null, null, null, joined);
    }

    public static void validate(Integer from, Integer to) {
        if ((from != null && (from < 0 || from > 100))
                || (to != null && (to < 0 || to > 100))
                || (from != null && to != null && from > to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid AI confidence range (0–100)");
        }
    }
}
