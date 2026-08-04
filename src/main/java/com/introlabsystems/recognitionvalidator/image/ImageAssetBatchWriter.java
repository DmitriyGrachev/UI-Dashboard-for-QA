package com.introlabsystems.recognitionvalidator.image;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class ImageAssetBatchWriter {

    private static final String UPSERT_ASSET = """
            INSERT INTO image_asset (
                id, file_name, relative_path, file_created_at, file_modified_at,
                discovered_at, last_seen_at, file_available, game_code, token_id,
                session_uuid, session_id, dealer_cards, active_user_cards,
                inactive_user_cards, payload_raw, buttons_raw, is_notification,
                has_stand, has_hit, has_double, has_split, has_surrender,
                processed_at, recognition_duration_ms, parse_status
            ) VALUES (
                :id, :fileName, :relativePath, :fileCreatedAt, :fileModifiedAt,
                :discoveredAt, :lastSeenAt, TRUE, :gameCode, :tokenId,
                :sessionUuid, :sessionId, :dealerCards, :activeUserCards,
                :inactiveUserCards, :payloadRaw, :buttonsRaw, :notification,
                :stand, :hit, :doubleAction, :split, :surrender,
                :processedAt, :recognitionDurationMs, :parseStatus
            )
            ON CONFLICT (id) DO UPDATE SET
                file_name = EXCLUDED.file_name,
                file_modified_at = EXCLUDED.file_modified_at,
                last_seen_at = EXCLUDED.last_seen_at,
                file_available = TRUE,
                has_surrender = EXCLUDED.has_surrender
            WHERE image_asset.file_available = FALSE
               OR image_asset.file_modified_at IS DISTINCT FROM EXCLUDED.file_modified_at
               OR image_asset.has_surrender IS DISTINCT FROM EXCLUDED.has_surrender
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

    public boolean hasAvailableAssets() {
        Boolean present = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM image_asset
                    WHERE file_available = TRUE
                )
                """, new MapSqlParameterSource(), Boolean.class);
        return Boolean.TRUE.equals(present);
    }

    public Set<String> findAvailableIds(Collection<String> imageIds) {
        if (imageIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("""
                SELECT id
                FROM image_asset
                WHERE file_available = TRUE
                  AND id IN (:ids)
                """, new MapSqlParameterSource("ids", imageIds), String.class));
    }

    public List<AvailableImageFile> findAvailableFilesAfter(String afterId, int limit) {
        return jdbc.query("""
                SELECT id, relative_path
                FROM image_asset
                WHERE file_available = TRUE
                  AND id > :afterId
                ORDER BY id
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("afterId", afterId)
                .addValue("limit", limit),
                (resultSet, rowNumber) -> new AvailableImageFile(
                        resultSet.getString("id"),
                        resultSet.getString("relative_path")
                ));
    }

    public void upsert(List<ImageMetadata> batch) {
        if (batch.isEmpty()) {
            return;
        }

        transactions.executeWithoutResult(status -> write(batch));
    }

    public void markUnavailable(String imageId) {
        markUnavailable(List.of(imageId));
    }

    public void markUnavailable(List<String> imageIds) {
        if (imageIds.isEmpty()) {
            return;
        }
        jdbc.update("""
                UPDATE image_asset
                SET file_available = FALSE
                WHERE id IN (:ids)
                """, new MapSqlParameterSource("ids", imageIds));
    }

    private void write(List<ImageMetadata> batch) {
        MapSqlParameterSource[] parameters = batch.stream()
                .map(ImageAssetBatchWriter::parameters)
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT_ASSET, parameters);
        jdbc.batchUpdate(INSERT_TASK, parameters);
    }

    private static MapSqlParameterSource parameters(ImageMetadata metadata) {
        RecognitionResult recognition = metadata.recognition();
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
                .addValue("dealerCards", recognition.dealerCards())
                .addValue("activeUserCards", recognition.activeUserCards())
                .addValue("inactiveUserCards", recognition.inactiveUserCards())
                .addValue("payloadRaw", recognition.payloadRaw())
                .addValue("buttonsRaw", recognition.buttonsRaw())
                .addValue("notification", recognition.notification())
                .addValue("stand", recognition.stand())
                .addValue("hit", recognition.hit())
                .addValue("doubleAction", recognition.doubleAction())
                .addValue("split", recognition.split())
                .addValue("surrender", recognition.surrender())
                .addValue("processedAt", timestamp(recognition.processedAt()))
                .addValue("recognitionDurationMs", recognition.recognitionDurationMs())
                .addValue("parseStatus", recognition.parseStatus().name());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    record AvailableImageFile(String id, String relativePath) {
    }
}
