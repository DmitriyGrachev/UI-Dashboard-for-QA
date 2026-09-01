\set ON_ERROR_STOP on
\echo 'Preparing review queue creation timestamps'

SET lock_timeout = '10s';
SET statement_timeout = 0;

ALTER TABLE review_task
    ADD COLUMN IF NOT EXISTS file_created_at timestamptz;

CREATE OR REPLACE PROCEDURE backfill_review_task_file_created_at(batch_size integer)
LANGUAGE plpgsql
AS $$
DECLARE
    updated_rows integer;
BEGIN
    LOOP
        WITH batch AS (
            SELECT rt.image_id
            FROM review_task rt
            JOIN image_asset ia ON ia.id = rt.image_id
            WHERE rt.file_created_at IS NULL
              AND ia.file_created_at IS NOT NULL
            ORDER BY rt.image_id
            LIMIT batch_size
            FOR UPDATE OF rt SKIP LOCKED
        )
        UPDATE review_task rt
        SET file_created_at = ia.file_created_at
        FROM batch
        JOIN image_asset ia ON ia.id = batch.image_id
        WHERE rt.image_id = batch.image_id;

        GET DIAGNOSTICS updated_rows = ROW_COUNT;
        COMMIT;
        EXIT WHEN updated_rows = 0;
    END LOOP;
END;
$$;

CALL backfill_review_task_file_created_at(10000);
DROP PROCEDURE backfill_review_task_file_created_at(integer);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM review_task WHERE file_created_at IS NULL) THEN
        RAISE EXCEPTION 'review_task.file_created_at backfill is incomplete';
    END IF;
END;
$$;

ALTER TABLE review_task
    ALTER COLUMN file_created_at SET NOT NULL;

\echo 'Creating queue and B2 candidate indexes'

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM pg_class index_relation
    JOIN pg_index index_metadata ON index_metadata.indexrelid = index_relation.oid
    WHERE index_relation.relname = 'ix_review_pending_order'
      AND NOT index_metadata.indisvalid
) THEN 'true' ELSE 'false' END AS review_index_invalid \gset

\if :review_index_invalid
DROP INDEX CONCURRENTLY ix_review_pending_order;
\endif

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_review_pending_order
    ON review_task (file_created_at, image_id)
    WHERE status = 'PENDING';

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM pg_class index_relation
    JOIN pg_index index_metadata ON index_metadata.indexrelid = index_relation.oid
    WHERE index_relation.relname = 'ix_image_cloud_pending_order'
      AND NOT index_metadata.indisvalid
) THEN 'true' ELSE 'false' END AS cloud_index_invalid \gset

\if :cloud_index_invalid
DROP INDEX CONCURRENTLY ix_image_cloud_pending_order;
\endif

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_image_cloud_pending_order
    ON image_asset (file_created_at, id)
    INCLUDE (
        relative_path,
        cloud_object_key,
        cloud_upload_next_attempt_at,
        cloud_upload_attempt_count
    )
    WHERE cloud_uploaded_at IS NULL
      AND (
          file_available = TRUE
          OR (cloud_object_key IS NOT NULL AND cloud_upload_attempt_count > 0)
      );

CREATE STATISTICS IF NOT EXISTS st_image_user_hand_presence
    ON (NULLIF(BTRIM(active_user_cards), '')),
       (NULLIF(BTRIM(inactive_user_cards), ''))
    FROM image_asset;

ANALYZE review_task;
ANALYZE image_asset;

\echo 'Review queue performance migration completed'
