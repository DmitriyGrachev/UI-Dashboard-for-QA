\set ON_ERROR_STOP on
\echo 'Preparing review queue filter projections'

SET lock_timeout = '10s';
SET statement_timeout = 0;

ALTER TABLE review_task
    ADD COLUMN IF NOT EXISTS game_code varchar(100),
    ADD COLUMN IF NOT EXISTS token_id bigint,
    ADD COLUMN IF NOT EXISTS session_id varchar(128),
    ADD COLUMN IF NOT EXISTS is_notification boolean,
    ADD COLUMN IF NOT EXISTS has_user_hand boolean;

CREATE OR REPLACE PROCEDURE backfill_review_task_filters(batch_size integer)
LANGUAGE plpgsql
AS $$
DECLARE
    updated_rows integer;
    processed_rows bigint := 0;
BEGIN
    LOOP
        WITH batch AS (
            SELECT rt.image_id
            FROM review_task rt
            WHERE rt.game_code IS NULL
               OR rt.is_notification IS NULL
               OR rt.has_user_hand IS NULL
            ORDER BY rt.image_id
            LIMIT batch_size
            FOR UPDATE OF rt SKIP LOCKED
        )
        UPDATE review_task rt
        SET game_code = ia.game_code,
            token_id = ia.token_id,
            session_id = ia.session_id,
            is_notification = ia.is_notification,
            has_user_hand = (
                NULLIF(BTRIM(ia.active_user_cards), '') IS NOT NULL
                OR NULLIF(BTRIM(ia.inactive_user_cards), '') IS NOT NULL
            )
        FROM batch
        JOIN image_asset ia ON ia.id = batch.image_id
        WHERE rt.image_id = batch.image_id;

        GET DIAGNOSTICS updated_rows = ROW_COUNT;
        processed_rows := processed_rows + updated_rows;
        IF updated_rows > 0
           AND processed_rows % (batch_size * 10) = 0 THEN
            RAISE NOTICE 'Backfilled % review task filter projections', processed_rows;
        END IF;
        COMMIT;
        EXIT WHEN updated_rows = 0;
    END LOOP;

    RAISE NOTICE 'Review task filter backfill finished: % rows updated', processed_rows;
END;
$$;

CALL backfill_review_task_filters(10000);
DROP PROCEDURE backfill_review_task_filters(integer);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM review_task
        WHERE game_code IS NULL
           OR is_notification IS NULL
           OR has_user_hand IS NULL
    ) THEN
        RAISE EXCEPTION 'review_task filter projection backfill is incomplete';
    END IF;
END;
$$;

\echo 'Creating selective pending queue indexes'

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM pg_class index_relation
    JOIN pg_index index_metadata ON index_metadata.indexrelid = index_relation.oid
    JOIN pg_namespace namespace ON namespace.oid = index_relation.relnamespace
    WHERE namespace.nspname = 'public'
      AND index_relation.relname = 'ix_review_pending_game_order'
      AND NOT index_metadata.indisvalid
) THEN 'true' ELSE 'false' END AS game_index_invalid \gset

\if :game_index_invalid
DROP INDEX CONCURRENTLY public.ix_review_pending_game_order;
\endif

\echo 'Creating Game queue index'
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_review_pending_game_order
    ON review_task (game_code, file_created_at, image_id)
    WHERE status = 'PENDING' AND game_code IS NOT NULL;

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM pg_class index_relation
    JOIN pg_index index_metadata ON index_metadata.indexrelid = index_relation.oid
    JOIN pg_namespace namespace ON namespace.oid = index_relation.relnamespace
    WHERE namespace.nspname = 'public'
      AND index_relation.relname = 'ix_review_pending_token_order'
      AND NOT index_metadata.indisvalid
) THEN 'true' ELSE 'false' END AS token_index_invalid \gset

\if :token_index_invalid
DROP INDEX CONCURRENTLY public.ix_review_pending_token_order;
\endif

\echo 'Creating Token queue index'
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_review_pending_token_order
    ON review_task (token_id, file_created_at, image_id)
    WHERE status = 'PENDING' AND token_id IS NOT NULL;

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM pg_class index_relation
    JOIN pg_index index_metadata ON index_metadata.indexrelid = index_relation.oid
    JOIN pg_namespace namespace ON namespace.oid = index_relation.relnamespace
    WHERE namespace.nspname = 'public'
      AND index_relation.relname = 'ix_review_pending_session_order'
      AND NOT index_metadata.indisvalid
) THEN 'true' ELSE 'false' END AS session_index_invalid \gset

\if :session_index_invalid
DROP INDEX CONCURRENTLY public.ix_review_pending_session_order;
\endif

\echo 'Creating Session queue index'
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_review_pending_session_order
    ON review_task (session_id, file_created_at, image_id)
    WHERE status = 'PENDING' AND session_id IS NOT NULL;

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM pg_class index_relation
    JOIN pg_index index_metadata ON index_metadata.indexrelid = index_relation.oid
    JOIN pg_namespace namespace ON namespace.oid = index_relation.relnamespace
    WHERE namespace.nspname = 'public'
      AND index_relation.relname = 'ix_review_pending_notification_true_order'
      AND NOT index_metadata.indisvalid
) THEN 'true' ELSE 'false' END AS notification_index_invalid \gset

\if :notification_index_invalid
DROP INDEX CONCURRENTLY public.ix_review_pending_notification_true_order;
\endif

\echo 'Creating Notification queue index'
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_review_pending_notification_true_order
    ON review_task (file_created_at, image_id)
    WHERE status = 'PENDING' AND is_notification = TRUE;

\echo 'Creating Token/Session planner statistics'
CREATE STATISTICS IF NOT EXISTS st_review_token_session (dependencies, ndistinct)
    ON token_id, session_id
    FROM review_task;

ANALYZE review_task;

\echo 'Review filter performance migration completed'
