\set ON_ERROR_STOP on
-- Run with psql -f, outside a transaction. Targeted covering indexes; no table backfill.
-- A blocked concurrent build fails promptly and can be safely retried.
SELECT current_setting('lock_timeout') AS old_lock_timeout,
       current_setting('statement_timeout') AS old_statement_timeout \gset ai_stats_
SET lock_timeout='3s';
SET statement_timeout='0';
SELECT pg_try_advisory_lock(hashtextextended('validator-ai-pull-migration', 0)) AS locked \gset
\if :locked
\else
  \echo 'Another AI migration is running; retry later'
  \quit 1
\endif

-- IF NOT EXISTS alone would silently accept a different index with the same name.
DO $$
DECLARE expected record;
BEGIN
  FOR expected IN SELECT * FROM (VALUES
    ('ix_ai_issued_rule_status', 'ai_review_task', ARRAY['issued_rule_id','status'], 2,
      $p$((issued_rule_id IS NOT NULL) AND ((status)::text = ANY ((ARRAY['PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))$p$),
    ('ix_ai_rule_overdue', 'ai_review_task', ARRAY['lease_expires_at','issued_rule_id','image_id'], 1,
      $p$((status)::text = 'PROCESSING'::text)$p$),
    ('ix_ai_failed_tasks', 'ai_review_task', ARRAY['file_created_at','image_id','issued_rule_id'], 2,
      $p$((status)::text = 'FAILED'::text)$p$),
    ('ix_ai_pending_rule_stats', 'ai_review_task', ARRAY['image_id','game_code','file_created_at','token_id','session_id','has_user_hand','retry_after','file_available','cloud_available_at'], 1,
      $p$(((status)::text = 'PENDING'::text) AND (file_available OR (cloud_available_at IS NOT NULL)))$p$),
    ('ix_image_ai_availability', 'image_asset', ARRAY['id','file_available','cloud_uploaded_at'], 1,
      $p$(file_available OR (NULLIF(btrim((cloud_object_key)::text), ''::text) IS NOT NULL))$p$)
  ) definitions(name, table_name, col_names, key_count, predicate) LOOP
    IF to_regclass(expected.name) IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
        JOIN pg_am am ON am.oid=c.relam
        WHERE i.indexrelid=to_regclass(expected.name)
          AND i.indrelid=to_regclass(expected.table_name) AND am.amname='btree'
          AND NOT i.indisunique AND i.indnkeyatts=expected.key_count
          AND (expected.name IN ('ix_ai_issued_rule_status','ix_ai_rule_overdue','ix_ai_failed_tasks') OR i.indcollation[0]='"C"'::regcollation)
          AND ARRAY(SELECT pg_get_indexdef(i.indexrelid,n,true) FROM generate_series(1,i.indnatts) n)=expected.col_names
          AND pg_get_expr(i.indpred,i.indrelid)=expected.predicate
    ) THEN
        RAISE EXCEPTION '% has an unexpected definition; inspect it before retrying', expected.name;
    END IF;
  END LOOP;
END $$;

SELECT format('DROP INDEX CONCURRENTLY %I.%I', n.nspname, c.relname)
FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE i.indexrelid IN (to_regclass('ix_ai_issued_rule_status'),to_regclass('ix_ai_rule_overdue'),to_regclass('ix_ai_failed_tasks'),to_regclass('ix_ai_pending_rule_stats'),
                      to_regclass('ix_image_ai_availability')) AND NOT i.indisvalid
\gexec

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_ai_issued_rule_status
    ON ai_review_task (issued_rule_id, status)
    WHERE issued_rule_id IS NOT NULL AND status IN ('PROCESSING','COMPLETED','FAILED');

-- The lease recovery index lacks issued_rule_id: grouping overdue tasks otherwise fetches
-- wide heap pages for every PROCESSING row. Keep this polling index limited to active tasks.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_ai_rule_overdue
    ON ai_review_task (lease_expires_at) INCLUDE (issued_rule_id, image_id)
    WHERE status='PROCESSING';

-- Count failures even without a current issuing rule, and page through them in the Explorer.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_ai_failed_tasks
    ON ai_review_task (file_created_at, image_id) INCLUDE (issued_rule_id)
    WHERE status='FAILED';

-- The existing claim indexes do not cover availability, retries, and all rule conditions.
-- This reads only compact PENDING index entries instead of wide result/message heap pages.
-- SHA-256 hex IDs use bytewise collation for the join, not locale-aware text ordering.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_ai_pending_rule_stats
    ON ai_review_task (image_id COLLATE "C")
    INCLUDE (game_code, file_created_at, token_id, session_id, has_user_hand, retry_after, file_available, cloud_available_at)
    WHERE status='PENDING' AND (file_available OR cloud_available_at IS NOT NULL);

-- Keep the authoritative image availability check, without scanning image payload/metadata heap pages.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_image_ai_availability
    ON image_asset (id COLLATE "C") INCLUDE (file_available, cloud_uploaded_at)
    WHERE file_available OR NULLIF(BTRIM(cloud_object_key),'') IS NOT NULL;

SELECT pg_advisory_unlock(hashtextextended('validator-ai-pull-migration', 0));
SET lock_timeout=:'ai_stats_old_lock_timeout';
SET statement_timeout=:'ai_stats_old_statement_timeout';
