package com.introlabsystems.recognitionvalidator.slack;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SlackNotificationOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final SlackNotificationStateRepository stateRepository;

    @Transactional
    public void initializeState() {
        SlackNotificationState state = lockedState();
        state.ensureCycle();
        stateRepository.saveAndFlush(state);
    }

    @Transactional
    public void enqueueRefresh(Instant now) {
        SlackNotificationState state = lockedState();
        UUID cycleId = state.ensureCycle();
        stateRepository.saveAndFlush(state);

        int updated = jdbc.update("""
                UPDATE slack_notification_outbox
                   SET last_error = NULL
                       ,target_message_ts = :targetTs
                 WHERE cycle_id = :cycleId
                   AND operation_kind = 'REFRESH'
                   AND delivery_phase = 'PENDING'
                """, params(cycleId, now).addValue("targetTs", blankToNull(state.getActiveMessageTs())));
        if (updated == 0) {
            updated = jdbc.update("""
                    UPDATE slack_notification_outbox
                       SET last_error = NULL,
                           target_message_ts = :targetTs
                     WHERE cycle_id = :cycleId
                       AND operation_kind = 'REFRESH'
                       AND delivery_phase = 'BLOCKED'
                    """, params(cycleId, now)
                    .addValue("targetTs", blankToNull(state.getActiveMessageTs())));
        }
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO slack_notification_outbox
                        (cycle_id, operation_kind, target_message_ts, delivery_phase, attempts,
                         next_attempt_at, created_at)
                    VALUES (:cycleId, 'REFRESH', :targetTs, 'PENDING', 0, :now, :now)
                    """, params(cycleId, now)
                    .addValue("targetTs", blankToNull(state.getActiveMessageTs())));
        }
    }

    @Transactional
    public boolean enqueueMessage(String dedupKey, UUID seriesId, String text, Instant now) {
        if (dedupKey == null || dedupKey.isBlank()) {
            throw new IllegalArgumentException("dedupKey must not be blank");
        }
        if (seriesId == null) {
            throw new IllegalArgumentException("seriesId must not be null");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        lockSeries(seriesId);
        String targetTs = latestMessageTarget(seriesId);
        return jdbc.update("""
                INSERT INTO slack_notification_outbox
                    (cycle_id, operation_kind, archive_payload, dedup_key,
                     target_message_ts, delivery_phase, attempts,
                     next_attempt_at, created_at)
                VALUES (:cycleId, 'MESSAGE', :text, :dedupKey,
                        :targetTs, 'PENDING', 0, :now, :now)
                ON CONFLICT (dedup_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("cycleId", seriesId)
                .addValue("text", text)
                .addValue("dedupKey", dedupKey)
                .addValue("targetTs", targetTs)
                .addValue("now", Timestamp.from(now))) == 1;
    }

    /** Creates the archive boundary and rotates the active cycle atomically. */
    @Transactional
    public boolean enqueueArchive(
            UUID exportId,
            Instant completedAt,
            String payload,
            boolean notificationsEnabled
    ) {
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM slack_notification_outbox WHERE export_id = :exportId
                """, new MapSqlParameterSource("exportId", exportId), Long.class) > 0) {
            return false;
        }
        SlackNotificationState state = lockedState();
        UUID completedCycle = state.ensureCycle();
        String targetTs = state.getActiveMessageTs();
        if (!notificationsEnabled && blankToNull(targetTs) == null
                && !Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM slack_notification_outbox
                         WHERE cycle_id = :cycleId AND operation_kind = 'REFRESH'
                           AND delivery_phase <> 'DELIVERED')
                        """, params(completedCycle, completedAt), Boolean.class))) {
            return false;
        }
        UUID nextCycle = UUID.randomUUID();
        MapSqlParameterSource parameters = params(completedCycle, completedAt)
                .addValue("exportId", exportId)
                .addValue("targetTs", blankToNull(targetTs))
                .addValue("payload", payload);
        int inserted = jdbc.update("""
                INSERT INTO slack_notification_outbox
                    (cycle_id, export_id, operation_kind, archive_payload,
                     target_message_ts, delivery_phase, attempts,
                     next_attempt_at, created_at)
                VALUES (:cycleId, :exportId, 'ARCHIVE', :payload,
                        :targetTs, 'PENDING', 0, :completedAt, :completedAt)
                ON CONFLICT (export_id) DO NOTHING
                """, parameters);
        if (inserted == 1) {
            state.rotateTo(nextCycle);
            stateRepository.saveAndFlush(state);
            if (notificationsEnabled) {
                jdbc.update("""
                    INSERT INTO slack_notification_outbox
                        (cycle_id, operation_kind, delivery_phase, attempts,
                         next_attempt_at, created_at)
                    VALUES (:cycleId, 'REFRESH', 'PENDING', 0, :completedAt, :completedAt)
                    """, new MapSqlParameterSource()
                    .addValue("cycleId", nextCycle)
                    .addValue("completedAt", Timestamp.from(completedAt)));
            }
            return true;
        }
        return false;
    }

    @Transactional
    public Optional<OutboxItem> claimNext(String workerId, Instant now, Duration lease) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("workerId", workerId)
                .addValue("claimedUntil", Timestamp.from(now.plus(lease)));
        return jdbc.query("""
                WITH candidate AS (
                    SELECT id
                      FROM slack_notification_outbox
                     WHERE delivery_phase <> 'DELIVERED'
                     ORDER BY id
                     FOR UPDATE
                     LIMIT 1
                )
                UPDATE slack_notification_outbox o
                   SET delivery_phase = 'IN_FLIGHT',
                       claimed_by = :workerId,
                       claimed_until = :claimedUntil
                  FROM candidate c
                 WHERE o.id = c.id
                   AND (((o.delivery_phase = 'PENDING' OR o.delivery_phase = 'BLOCKED')
                         AND o.next_attempt_at <= :now)
                     OR (o.delivery_phase = 'IN_FLIGHT' AND o.claimed_until <= :now))
                RETURNING o.id, o.cycle_id, o.export_id, o.operation_kind,
                          o.archive_payload, o.target_message_ts,
                          o.delivery_phase, o.attempts, o.next_attempt_at,
                          o.created_at
                """, parameters, (rs, row) -> new OutboxItem(
                rs.getLong("id"),
                rs.getObject("cycle_id", UUID.class),
                (UUID) rs.getObject("export_id"),
                SlackNotificationOutbox.OperationKind.valueOf(rs.getString("operation_kind")),
                rs.getString("archive_payload"),
                rs.getString("target_message_ts"),
                SlackNotificationOutbox.DeliveryPhase.valueOf(rs.getString("delivery_phase")),
                rs.getInt("attempts"),
                rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()
        )).stream().findFirst();
    }

    @Transactional
    public void attachMessageToCycle(UUID cycleId, String messageTs) {
        if (messageTs == null || messageTs.isBlank()) {
            return;
        }
        lockSeries(cycleId);
        jdbc.update("""
                UPDATE slack_notification_state
                   SET active_message_ts = :messageTs
                 WHERE active_cycle_id = :cycleId
                """, new MapSqlParameterSource()
                .addValue("cycleId", cycleId)
                .addValue("messageTs", messageTs));
        jdbc.update("""
                UPDATE slack_notification_outbox
                   SET target_message_ts = :messageTs
                 WHERE cycle_id = :cycleId
                   AND delivery_phase <> 'DELIVERED'
                   AND operation_kind IN ('ARCHIVE', 'REFRESH', 'MESSAGE')
                """, new MapSqlParameterSource()
                .addValue("cycleId", cycleId)
                .addValue("messageTs", messageTs));
    }

    public String targetMessageTs(long id) {
        var values = jdbc.query("""
                SELECT target_message_ts FROM slack_notification_outbox WHERE id = :id
                """, new MapSqlParameterSource("id", id),
                (rs, row) -> rs.getString("target_message_ts"))
                ;
        return values.isEmpty() ? null : values.get(0);
    }

    @Transactional
    public void markDelivered(long id, String workerId) {
        int refreshDeleted = jdbc.update("""
                DELETE FROM slack_notification_outbox
                 WHERE id = :id AND operation_kind = 'REFRESH'
                   AND delivery_phase = 'IN_FLIGHT' AND claimed_by = :workerId
                """, new MapSqlParameterSource("id", id).addValue("workerId", workerId));
        if (refreshDeleted == 1) {
            return;
        }
        jdbc.update("""
                UPDATE slack_notification_outbox
                   SET delivery_phase = 'DELIVERED', claimed_by = NULL,
                       claimed_until = NULL, last_error = NULL
                 WHERE id = :id AND delivery_phase = 'IN_FLIGHT' AND claimed_by = :workerId
                """, new MapSqlParameterSource("id", id).addValue("workerId", workerId));
    }

    @Transactional
    public void markRetry(long id, String workerId, Instant nextAttemptAt, String error) {
        jdbc.update("""
                UPDATE slack_notification_outbox
                   SET delivery_phase = 'PENDING', attempts = attempts + 1,
                       next_attempt_at = :nextAttemptAt, claimed_by = NULL,
                       claimed_until = NULL, last_error = :error
                 WHERE id = :id AND delivery_phase = 'IN_FLIGHT' AND claimed_by = :workerId
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("workerId", workerId)
                .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .addValue("error", error));
    }

    @Transactional
    public void markBlocked(long id, String workerId, String error) {
        jdbc.update("""
                UPDATE slack_notification_outbox
                   SET delivery_phase = 'BLOCKED', claimed_by = NULL,
                       claimed_until = NULL, next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                       last_error = :error
                 WHERE id = :id AND delivery_phase = 'IN_FLIGHT' AND claimed_by = :workerId
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("workerId", workerId).addValue("error", error));
    }

    public long pendingCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM slack_notification_outbox
                 WHERE delivery_phase <> 'DELIVERED'
                """, new MapSqlParameterSource(), Long.class);
    }

    private MapSqlParameterSource params(UUID cycleId, Instant now) {
        return new MapSqlParameterSource()
                .addValue("cycleId", cycleId)
                .addValue("now", Timestamp.from(now))
                .addValue("completedAt", Timestamp.from(now));
    }

    private SlackNotificationState lockedState() {
        jdbc.update("""
                INSERT INTO slack_notification_state (id, legacy_pointer_retired)
                VALUES (:id, TRUE)
                ON CONFLICT (id) DO NOTHING
                """, new MapSqlParameterSource("id", SlackNotificationState.SINGLETON_ID));
        return stateRepository.findLockedById(SlackNotificationState.SINGLETON_ID).orElseThrow();
    }

    private void lockSeries(UUID seriesId) {
        jdbc.query("""
                SELECT pg_advisory_xact_lock(
                    hashtextextended(CAST(:seriesId AS text), 0)
                )
                """, new MapSqlParameterSource("seriesId", seriesId),
                (rs, row) -> null);
    }

    private String latestMessageTarget(UUID seriesId) {
        var values = jdbc.query("""
                SELECT target_message_ts
                  FROM slack_notification_outbox
                 WHERE cycle_id = :seriesId
                   AND operation_kind = 'MESSAGE'
                   AND target_message_ts IS NOT NULL
                 ORDER BY id DESC
                 LIMIT 1
                """, new MapSqlParameterSource("seriesId", seriesId),
                (rs, row) -> rs.getString("target_message_ts"));
        return values.isEmpty() ? null : values.get(0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record OutboxItem(
            long id,
            UUID cycleId,
            UUID exportId,
            SlackNotificationOutbox.OperationKind operationKind,
            String archivePayload,
            String targetMessageTs,
            SlackNotificationOutbox.DeliveryPhase deliveryPhase,
            int attempts,
            Instant nextAttemptAt,
            Instant createdAt
    ) {
    }
}
