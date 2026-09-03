\set ON_ERROR_STOP on
DO $$ BEGIN
    IF current_database() <> 'ai_pull_migration_test' THEN RAISE EXCEPTION 'Disposable database only'; END IF;
END $$;
SET statement_timeout = '20s';
SET max_parallel_workers_per_gather = 0;
SET plan_cache_mode = force_generic_plan;
\echo 'Common claim'
EXPLAIN (ANALYZE, BUFFERS) SELECT ai.image_id FROM ai_review_task ai JOIN image_asset ia ON ia.id=ai.image_id
WHERE ai.status='PENDING' AND ai.game_code='bj_single_deck_ags' AND ia.file_available
AND ai.file_available AND (ai.file_available OR ai.cloud_available_at IS NOT NULL)
AND (ai.retry_after IS NULL OR ai.retry_after<=now()) ORDER BY ai.file_created_at,ai.image_id LIMIT 20;
PREPARE token_claim(bigint,text) AS SELECT ai.image_id FROM ai_review_task ai JOIN image_asset ia ON ia.id=ai.image_id
WHERE ai.status='PENDING' AND ai.game_code='bj_single_deck_ags' AND ia.file_available
AND ai.file_available AND (ai.file_available OR ai.cloud_available_at IS NOT NULL)
AND (ai.retry_after IS NULL OR ai.retry_after<=now()) AND ai.token_id=$1 AND ai.session_id=$2
ORDER BY ai.file_created_at,ai.image_id LIMIT 20;
\echo 'Rare token + session, generic plan'
EXPLAIN (ANALYZE, BUFFERS) EXECUTE token_claim(53,'session-53');
\echo 'Empty token + session'
EXPLAIN (ANALYZE, BUFFERS) EXECUTE token_claim(999999,'absent');
\echo 'Notification true'
EXPLAIN (ANALYZE, BUFFERS) SELECT ai.image_id FROM ai_review_task ai JOIN image_asset ia ON ia.id=ai.image_id
WHERE ai.status='PENDING' AND ai.game_code='bj_single_deck_ags' AND ia.file_available AND ai.is_notification=TRUE
AND ai.file_available AND (ai.file_available OR ai.cloud_available_at IS NOT NULL)
ORDER BY ai.file_created_at,ai.image_id LIMIT 20;
\echo 'AI + operator filters, rare completed result'
EXPLAIN (ANALYZE, BUFFERS) SELECT rt.image_id FROM ai_review_task ai
JOIN review_task rt ON rt.image_id=ai.image_id JOIN image_asset ia ON ia.id=rt.image_id
WHERE rt.status='PENDING' AND ia.file_available AND ai.status='COMPLETED' AND ai.valid=FALSE AND ai.certainty>=90
ORDER BY ai.file_created_at,ai.image_id LIMIT 1;
\echo 'Operator oldest with many AI matches clustered at the end'
EXPLAIN (ANALYZE, BUFFERS) SELECT rt.image_id FROM ai_review_task ai
JOIN review_task rt ON rt.image_id=ai.image_id JOIN image_asset ia ON ia.id=rt.image_id
WHERE rt.status='PENDING' AND ia.file_available AND ai.status='COMPLETED' AND ai.valid=TRUE AND ai.certainty>=90
ORDER BY ai.file_created_at,ai.image_id LIMIT 1;
\echo 'Admin newest page with AI result'
EXPLAIN (ANALYZE, BUFFERS) SELECT rt.image_id FROM ai_review_task ai JOIN review_task rt ON rt.image_id=ai.image_id
WHERE ai.status='COMPLETED' AND ai.valid=TRUE
ORDER BY ai.file_created_at DESC,ai.image_id DESC LIMIT 51;
\echo 'Admin certainty-only cursor page'
EXPLAIN (ANALYZE, BUFFERS) SELECT rt.image_id FROM ai_review_task ai JOIN review_task rt ON rt.image_id=ai.image_id
WHERE ai.status='COMPLETED' AND ai.certainty>=90 AND (ai.file_created_at,ai.image_id)<('2026-08-18'::timestamptz,repeat('f',64))
ORDER BY ai.file_created_at DESC,ai.image_id DESC LIMIT 51;
\echo 'Admin cursor with AI unchecked'
EXPLAIN (ANALYZE, BUFFERS) SELECT rt.image_id FROM review_task rt
WHERE (rt.file_created_at,rt.image_id)<('2026-08-10'::timestamptz,repeat('f',64)) AND NOT EXISTS (
SELECT 1 FROM ai_review_task ai WHERE ai.image_id=rt.image_id AND ai.status='COMPLETED')
ORDER BY rt.file_created_at DESC,rt.image_id DESC LIMIT 51;
PREPARE operator_ai(bigint,text) AS SELECT rt.image_id FROM ai_review_task ai
JOIN review_task rt ON rt.image_id=ai.image_id JOIN image_asset ia ON ia.id=rt.image_id
WHERE rt.status='PENDING' AND ia.file_available AND ai.status='COMPLETED' AND ai.valid=TRUE AND ai.certainty>=90
AND rt.token_id=$1 AND rt.session_id=$2 ORDER BY ai.file_created_at,ai.image_id LIMIT 1 FOR UPDATE OF rt SKIP LOCKED;
\echo 'Operator AI + token + session, generic plan'
EXPLAIN (ANALYZE,BUFFERS) EXECUTE operator_ai(53,'session-53');
\echo 'Operator AI + empty token + session, generic plan'
EXPLAIN (ANALYZE,BUFFERS) EXECUTE operator_ai(999999,'absent');
DEALLOCATE ALL;
