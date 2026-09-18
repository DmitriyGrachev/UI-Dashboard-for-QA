package com.introlabsystems.recognitionvalidator.ai.mapper;

import com.introlabsystems.recognitionvalidator.ai.dto.AiResultDetails;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

public final class AiJdbcMapping {
    private AiJdbcMapping() {}

    public static Instant instant(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    public static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    public static AiResultDetails resultDetails(ResultSet rs, int row) throws SQLException {
        return new AiResultDetails(rs.getString("status"), rs.getObject("valid", Boolean.class), rs.getString("verdict"),
                rs.getObject("certainty", Integer.class), rs.getObject("confidence", Integer.class), rs.getString("message"),
                instant(rs, "checked_at"), rs.getInt("attempt_count"), rs.getString("last_error_code"),
                rs.getString("last_error_message"), instant(rs, "last_error_at"),
                rs.getObject("issued_rule_id", java.util.UUID.class), rs.getString("issued_rule_name"));
    }
}
