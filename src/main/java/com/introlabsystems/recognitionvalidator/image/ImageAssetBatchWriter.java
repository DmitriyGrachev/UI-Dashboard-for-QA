package com.introlabsystems.recognitionvalidator.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Component
public class ImageAssetBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(ImageAssetBatchWriter.class);
    private static final String UPSERT_ASSET = """
            INSERT INTO image_asset (
                id, file_name, relative_path, file_created_at, file_modified_at,
                discovered_at, last_seen_at, file_available, game_code, token_id,
                session_uuid, session_id, dealer_cards, active_user_cards,
                inactive_user_cards, payload_raw, buttons_raw, is_notification,
                has_stand, has_hit, has_double, has_split, processed_at,
                recognition_duration_ms, parse_status
            ) VALUES (
                :id, :fileName, :relativePath, :fileCreatedAt, :fileModifiedAt,
                :discoveredAt, :lastSeenAt, TRUE, :gameCode, :tokenId,
                :sessionUuid, :sessionId, :dealerCards, :activeUserCards,
                :inactiveUserCards, :payloadRaw, :buttonsRaw, :notification,
                :stand, :hit, :doubleAction, :split, :processedAt,
                :recognitionDurationMs, :parseStatus
            )
            ON CONFLICT (id) DO UPDATE SET
                file_name = EXCLUDED.file_name,
                file_modified_at = EXCLUDED.file_modified_at,
                last_seen_at = EXCLUDED.last_seen_at,
                file_available = TRUE
            """;
    private static final String INSERT_TASK = """
            INSERT INTO review_task (image_id, status)
            VALUES (:id, 'PENDING')
            ON CONFLICT (image_id) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ImageAssetBatchWriter(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void upsert(List<ImageMetadata> batch) {
        if (batch.isEmpty()) {
            return;
        }

        try {
            transactions.executeWithoutResult(status -> write(batch));
        } catch (DataAccessException batchError) {
            log.warn("Image metadata batch failed; retrying {} rows separately", batch.size());
            for (ImageMetadata metadata : batch) {
                try {
                    transactions.executeWithoutResult(status -> write(List.of(metadata)));
                } catch (DataAccessException rowError) {
                    log.error("Cannot index image metadata for {}", metadata.relativePath(), rowError);
                }
            }
        }
    }

    public void markMissingBefore(Instant scanStartedAt) {
        jdbc.update("""
                UPDATE image_asset
                SET file_available = FALSE
                WHERE file_available = TRUE
                  AND last_seen_at < :scanStartedAt
                """, new MapSqlParameterSource(
                "scanStartedAt", Timestamp.from(scanStartedAt)
        ));
    }

    public void markUnavailable(String imageId) {
        jdbc.update("""
                UPDATE image_asset
                SET file_available = FALSE
                WHERE id = :id
                """, new MapSqlParameterSource("id", imageId));
    }

    private void write(List<ImageMetadata> batch) {
        MapSqlParameterSource[] parameters = batch.stream()
                .map(ImageAssetBatchWriter::parameters)
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT_ASSET, parameters);
        jdbc.batchUpdate(INSERT_TASK, parameters);
    }

    private static MapSqlParameterSource parameters(ImageMetadata metadata) {
        return new MapSqlParameterSource()
                .addValue("id", metadata.id())
                .addValue("fileName", metadata.fileName())
                .addValue("relativePath", metadata.relativePath())
                .addValue("fileCreatedAt", timestamp(metadata.fileCreatedAt()))
                .addValue("fileModifiedAt", timestamp(metadata.fileModifiedAt()))
                .addValue("discoveredAt", timestamp(metadata.discoveredAt()))
                .addValue("lastSeenAt", timestamp(metadata.lastSeenAt()))
                .addValue("gameCode", metadata.gameCode())
                .addValue("tokenId", metadata.tokenId())
                .addValue("sessionUuid", metadata.sessionUuid())
                .addValue("sessionId", metadata.sessionId())
                .addValue("dealerCards", metadata.dealerCards())
                .addValue("activeUserCards", metadata.activeUserCards())
                .addValue("inactiveUserCards", metadata.inactiveUserCards())
                .addValue("payloadRaw", metadata.payloadRaw())
                .addValue("buttonsRaw", metadata.buttonsRaw())
                .addValue("notification", metadata.notification())
                .addValue("stand", metadata.stand())
                .addValue("hit", metadata.hit())
                .addValue("doubleAction", metadata.doubleAction())
                .addValue("split", metadata.split())
                .addValue("processedAt", timestamp(metadata.processedAt()))
                .addValue("recognitionDurationMs", metadata.recognitionDurationMs())
                .addValue("parseStatus", metadata.parseStatus().name());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
