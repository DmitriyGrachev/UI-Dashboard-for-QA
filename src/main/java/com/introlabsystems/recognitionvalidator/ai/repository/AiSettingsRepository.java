package com.introlabsystems.recognitionvalidator.ai.repository;

import com.introlabsystems.recognitionvalidator.ai.dto.AiRule;
import com.introlabsystems.recognitionvalidator.ai.dto.AiSettings;
import com.introlabsystems.recognitionvalidator.ai.exception.AiQueueException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class AiSettingsRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public AiSettingsRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AiSettings read() {
        // One statement sees either the entire old configuration or the entire new one.
        return jdbc.query("""
                SELECT s.revision, s.enabled AS queue_enabled, r.*
                FROM ai_queue_settings s LEFT JOIN ai_selection_rule r ON TRUE
                WHERE s.id=1 ORDER BY r.priority, r.id
                """, rs -> {
            if (!rs.next()) return new AiSettings(0, false, List.of());
            long revision = rs.getLong("revision");
            boolean enabled = rs.getBoolean("queue_enabled");
            List<AiRule> rules = new ArrayList<>();
            do {
                if (rs.getObject("id") != null) rules.add(new AiRule(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getBoolean("enabled"), rs.getInt("priority"),
                        instant(rs, "created_from"), instant(rs, "created_to"), rs.getObject("token_id", Long.class),
                        rs.getString("session_id"), rs.getObject("notification", Boolean.class), rs.getObject("has_user_hand", Boolean.class)));
            } while (rs.next());
            return new AiSettings(revision, enabled, rules);
        });
    }

    public AiSettings save(AiSettings settings) {
        return transactions.execute(tx -> {
            jdbc.update("INSERT INTO ai_queue_settings(id,revision,enabled) VALUES (1,0,false) ON CONFLICT DO NOTHING");
            if (jdbc.update("UPDATE ai_queue_settings SET revision=revision+1,enabled=? WHERE id=1 AND revision=?",
                    settings.enabled(), settings.revision()) != 1) {
                throw new AiQueueException(HttpStatus.CONFLICT, "SETTINGS_CONFLICT", "Settings changed; reload before saving");
            }
            jdbc.update("DELETE FROM ai_selection_rule");
            for (AiRule r : settings.rules()) jdbc.update("""
                    INSERT INTO ai_selection_rule(id,name,enabled,priority,created_from,created_to,token_id,session_id,notification,has_user_hand)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, r.id(), r.name(), r.enabled(), r.priority(), timestamp(r.createdFrom()), timestamp(r.createdTo()),
                    r.tokenId(), r.sessionId(), r.notification(), r.hasUserHand());
            return read();
        });
    }

    static Instant instant(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }
    static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
}
