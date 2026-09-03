package com.introlabsystems.recognitionvalidator.ai.repository;

import com.introlabsystems.recognitionvalidator.ai.model.AiResultState;

/** AI rows are unique by image ID, so an ordered completed-result join cannot duplicate a page. */
public final class AiResultFilterSql {
    private AiResultFilterSql() {}

    public static boolean completedOnly(AiResultState state) {
        return state == AiResultState.CHECKED || state == AiResultState.MATCHED
                || state == AiResultState.UNMATCHED;
    }

    public static void append(StringBuilder sql, AiResultState state, boolean joined) {
        if (state == null || state == AiResultState.ALL) return;
        if (joined) sql.append(" AND ai.status = 'COMPLETED'");
        else {
            sql.append(state == AiResultState.UNCHECKED ? " AND NOT EXISTS (" : " AND EXISTS (");
            sql.append("SELECT 1 FROM ai_review_task ai WHERE ai.image_id = rt.image_id AND ai.status = 'COMPLETED'");
        }
        if (state == AiResultState.MATCHED) sql.append(" AND ai.valid = TRUE");
        if (state == AiResultState.UNMATCHED) sql.append(" AND ai.valid = FALSE");
        if (!joined) sql.append(")");
    }
}
