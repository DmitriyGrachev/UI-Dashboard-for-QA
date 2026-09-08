\set ON_ERROR_STOP on
\echo 'AI pull migration: stop old push scheduler and metadata writers for the final cutover'
SET lock_timeout = '10s';
SET statement_timeout = 0;
SELECT pg_advisory_lock(hashtextextended('validator-ai-pull-migration', 0));

CREATE TABLE IF NOT EXISTS ai_queue_settings (
    id integer PRIMARY KEY, revision bigint NOT NULL, enabled boolean NOT NULL
);
INSERT INTO ai_queue_settings (id, revision, enabled) VALUES (1, 0, false) ON CONFLICT (id) DO NOTHING;
CREATE TABLE IF NOT EXISTS ai_selection_rule (
    id uuid PRIMARY KEY, name varchar(100) NOT NULL, enabled boolean NOT NULL, priority integer NOT NULL,
    created_from timestamptz, created_to timestamptz, token_id bigint, session_id varchar(128),
    notification boolean, has_user_hand boolean
);
CREATE TABLE IF NOT EXISTS ai_review_task (
    image_id varchar(64) PRIMARY KEY REFERENCES image_asset(id) ON DELETE CASCADE,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    file_created_at timestamptz NOT NULL, game_code varchar(100) NOT NULL,
    token_id bigint, session_id varchar(128), is_notification boolean NOT NULL, has_user_hand boolean NOT NULL,
    file_available boolean NOT NULL DEFAULT false, cloud_available_at timestamptz,
    claim_id uuid, lease_expires_at timestamptz, retry_after timestamptz,
    attempt_count integer NOT NULL DEFAULT 0, issued_rule_id uuid, expected varchar(512), game varchar(32),
    valid boolean, verdict varchar(32), certainty integer, confidence integer, message varchar(2000),
    checked_at timestamptz, last_error_code varchar(64), last_error_message varchar(1000), last_error_at timestamptz
);
CREATE TABLE IF NOT EXISTS ai_task_rejection (
    id uuid PRIMARY KEY,
    image_id varchar(64) NOT NULL,
    claim_id uuid NOT NULL,
    message varchar(1000) NOT NULL,
    rejected_at timestamptz NOT NULL,
    CONSTRAINT uk_ai_task_rejection_claim UNIQUE (image_id, claim_id)
);

ALTER TABLE ai_review_task ADD COLUMN IF NOT EXISTS file_available boolean NOT NULL DEFAULT false;
ALTER TABLE ai_review_task ADD COLUMN IF NOT EXISTS cloud_available_at timestamptz;
-- Keep this small DDL identical to src/main/resources/db/ai-availability.sql (also used on fresh databases).
CREATE OR REPLACE FUNCTION sync_ai_image_availability() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE ai_review_task SET file_available=NEW.file_available,
        cloud_available_at=CASE WHEN NULLIF(BTRIM(NEW.cloud_object_key),'') IS NOT NULL THEN NEW.cloud_uploaded_at END
    WHERE image_id=NEW.id AND (file_available,cloud_available_at) IS DISTINCT FROM
        (NEW.file_available,CASE WHEN NULLIF(BTRIM(NEW.cloud_object_key),'') IS NOT NULL THEN NEW.cloud_uploaded_at END);
    RETURN NEW;
END $$;
CREATE OR REPLACE TRIGGER trg_ai_image_availability
AFTER UPDATE OF file_available, cloud_object_key, cloud_uploaded_at ON image_asset
FOR EACH ROW WHEN ((OLD.file_available,OLD.cloud_object_key,OLD.cloud_uploaded_at) IS DISTINCT FROM
                  (NEW.file_available,NEW.cloud_object_key,NEW.cloud_uploaded_at))
EXECUTE FUNCTION sync_ai_image_availability();

-- Bounded transactions and a moving primary-key cursor avoid a growing scan of already copied rows.
-- Existing pull results, rules, settings and old review_task columns are never reset or dropped.
CREATE OR REPLACE PROCEDURE backfill_ai_pull(batch_size integer)
LANGUAGE plpgsql AS $$
DECLARE
    cursor_id text := '';
    end_id text;
    batches integer := 0;
    legacy boolean := EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name='review_task' AND column_name='ai_status');
BEGIN
    LOOP
        -- Batch bounds move through the full key space; a cached generic plan can rescan
        -- all legacy results for every batch. Re-plan this migration's bounded statements.
        SET LOCAL plan_cache_mode = force_custom_plan;
        SELECT max(id) INTO end_id FROM (
            SELECT id FROM image_asset WHERE id > cursor_id AND game_code='bj_single_deck_ags'
            ORDER BY id LIMIT batch_size
        ) batch;
        EXIT WHEN end_id IS NULL;
        INSERT INTO ai_review_task (image_id, status, file_created_at, game_code, token_id, session_id,
            is_notification, has_user_hand, attempt_count, file_available, cloud_available_at)
        SELECT id, 'PENDING', file_created_at, game_code, token_id, session_id, is_notification,
            (NULLIF(BTRIM(active_user_cards),'') IS NOT NULL OR NULLIF(BTRIM(inactive_user_cards),'') IS NOT NULL), 0,
            file_available, CASE WHEN NULLIF(BTRIM(cloud_object_key),'') IS NOT NULL THEN cloud_uploaded_at END
        FROM image_asset WHERE id > cursor_id AND id <= end_id AND game_code='bj_single_deck_ags'
        ON CONFLICT (image_id) DO UPDATE SET file_available=EXCLUDED.file_available,
            cloud_available_at=EXCLUDED.cloud_available_at
        WHERE (ai_review_task.file_available,ai_review_task.cloud_available_at)
            IS DISTINCT FROM (EXCLUDED.file_available,EXCLUDED.cloud_available_at);

        IF legacy THEN
            UPDATE ai_review_task ai SET status='COMPLETED',
                valid=(to_jsonb(rt)->>'ai_valid')::boolean,
                verdict=to_jsonb(rt)->>'ai_verdict',
                certainty=(to_jsonb(rt)->>'ai_certainty')::integer,
                confidence=(to_jsonb(rt)->>'ai_confidence')::integer,
                checked_at=(to_jsonb(rt)->>'ai_checked_at')::timestamptz,
                attempt_count=COALESCE((to_jsonb(rt)->>'ai_attempt_count')::integer,0),
                game='SINGLE_DECK'
            FROM review_task rt WHERE rt.image_id=ai.image_id
                AND ai.image_id > cursor_id AND ai.image_id <= end_id
                AND rt.image_id > cursor_id AND rt.image_id <= end_id
                AND ai.status='PENDING' AND ai.attempt_count=0
                AND rt.ai_status='COMPLETED';
        END IF;
        cursor_id := end_id;
        batches := batches + 1;
        IF batches % 20 = 0 THEN
            RAISE NOTICE 'AI backfill: % batches completed (up to % rows per batch)', batches, batch_size;
        END IF;
        COMMIT;
    END LOOP;
END $$;
CALL backfill_ai_pull(5000);
DROP PROCEDURE backfill_ai_pull(integer);

-- Recover only invalid indexes owned by this migration, e.g. after an interrupted concurrent build.
SELECT format('DROP INDEX CONCURRENTLY %I.%I', n.nspname, c.relname)
FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname=current_schema() AND c.relname IN (
    'ix_ai_pending_game_order','ix_ai_pending_token_order','ix_ai_pending_session_order',
    'ix_ai_pending_notification_order','ix_ai_expired_lease','ix_ai_completed_result_order','ix_ai_completed_order')
AND (NOT i.indisvalid OR (c.relname LIKE 'ix_ai_pending_%'
     AND position('file_available' in pg_get_expr(i.indpred,i.indrelid))=0))
\gexec

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_ai_pending_game_order
    ON ai_review_task (game_code, file_created_at, image_id)
    WHERE status='PENDING' AND (file_available OR cloud_available_at IS NOT NULL);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_ai_pending_token_order
    ON ai_review_task (token_id, file_created_at, image_id)
    WHERE status='PENDING' AND (file_available OR cloud_available_at IS NOT NULL);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_ai_pending_session_order
    ON ai_review_task (session_id, file_created_at, image_id)
    WHERE status='PENDING' AND (file_available OR cloud_available_at IS NOT NULL);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_ai_pending_notification_order
    ON ai_review_task (file_created_at, image_id)
    WHERE status='PENDING' AND is_notification=TRUE AND (file_available OR cloud_available_at IS NOT NULL);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_ai_expired_lease
    ON ai_review_task (lease_expires_at, image_id) WHERE status='PROCESSING';
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_ai_completed_result_order
    ON ai_review_task (valid, file_created_at, image_id) INCLUDE (certainty) WHERE status='COMPLETED';
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_ai_completed_order
    ON ai_review_task (file_created_at, image_id) INCLUDE (valid, certainty) WHERE status='COMPLETED';
CREATE STATISTICS IF NOT EXISTS st_ai_token_session (dependencies, ndistinct, mcv)
    ON token_id, session_id FROM ai_review_task;
ANALYZE ai_review_task;

SELECT COUNT(*) AS missing_single_deck_tasks FROM image_asset ia
WHERE ia.game_code='bj_single_deck_ags' AND NOT EXISTS (SELECT 1 FROM ai_review_task ai WHERE ai.image_id=ia.id);
SELECT COUNT(*) AS incorrect_availability_projections FROM ai_review_task ai JOIN image_asset ia ON ia.id=ai.image_id
WHERE (ai.file_available,ai.cloud_available_at) IS DISTINCT FROM
    (ia.file_available,CASE WHEN NULLIF(BTRIM(ia.cloud_object_key),'') IS NOT NULL THEN ia.cloud_uploaded_at END);
SELECT indexrelid::regclass AS index_name, indisvalid, indisready FROM pg_index
WHERE indrelid='ai_review_task'::regclass ORDER BY index_name;
SELECT pg_advisory_unlock(hashtextextended('validator-ai-pull-migration', 0));
\echo 'AI pull migration completed; review issuance settings in admin before starting workers'
