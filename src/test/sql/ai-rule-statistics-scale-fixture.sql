\set ON_ERROR_STOP on
\timing on
DO $$ BEGIN
 IF current_database()<>'rv_ai_stats_analysis' OR EXISTS(SELECT 1 FROM image_asset) THEN
 RAISE EXCEPTION 'Empty disposable analysis DB only'; END IF;
END $$;
-- Secondary image indexes are irrelevant to this query's PK join; omit them while loading synthetic rows.
SELECT format('DROP INDEX %I',c.relname)
FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
WHERE i.indrelid='image_asset'::regclass AND NOT i.indisprimary AND NOT i.indisunique
\gexec
INSERT INTO image_asset(id,file_name,relative_path,file_created_at,file_modified_at,discovered_at,last_seen_at,
file_available,game_code,token_id,session_id,is_notification,has_stand,has_hit,has_double,has_split,has_surrender,parse_status,
payload_raw,active_user_cards,cloud_object_key,cloud_uploaded_at)
SELECT lpad(n::text,64,'0'),n||'.png',n||'.png','2026-09-01'::timestamptz + n*interval '0.1 second',
now(),now(),now(),n%10<2,CASE WHEN n%3=0 THEN 'bj_single_deck_ags' ELSE 'bj_igt' END,
n%1000,'session-'||(n%1000),false,true,true,false,false,false,'SUCCESS',
repeat('sample_',20),'Seven_King','fixture/'||n||'.png',
CASE WHEN n%10=9 THEN '2026-08-01'::timestamptz ELSE '2026-09-15'::timestamptz END
FROM generate_series(1,5000000) n;
INSERT INTO ai_review_task(image_id,status,file_created_at,game_code,token_id,session_id,is_notification,has_user_hand,
file_available,cloud_available_at,attempt_count,issued_rule_id,lease_expires_at,message)
SELECT id,CASE WHEN right(id,2)::int<10 THEN 'PENDING' WHEN right(id,2)::int=10 THEN 'PROCESSING'
WHEN right(id,2)::int=11 THEN 'FAILED' ELSE 'COMPLETED' END,
file_created_at,game_code,token_id,session_id,false,true,file_available,cloud_uploaded_at,1,
CASE WHEN right(id,2)::int<10 THEN NULL ELSE
('00000000-0000-0000-0000-'||lpad(CASE WHEN token_id%7=0 THEN '99' ELSE (token_id%3+1)::text END,12,'0'))::uuid END,
CASE WHEN right(id,2)::int=10 THEN '2026-09-15'::timestamptz ELSE NULL END,repeat('synthetic result ',15)
FROM image_asset WHERE id<=lpad('4400000',64,'0');
CREATE INDEX ix_ai_pending_game_order ON ai_review_task(game_code,file_created_at,image_id)
 WHERE status='PENDING' AND (file_available OR cloud_available_at IS NOT NULL);
CREATE INDEX ix_ai_pending_token_order ON ai_review_task(token_id,file_created_at,image_id)
 WHERE status='PENDING' AND (file_available OR cloud_available_at IS NOT NULL);
CREATE INDEX ix_ai_pending_session_order ON ai_review_task(session_id,file_created_at,image_id)
 WHERE status='PENDING' AND (file_available OR cloud_available_at IS NOT NULL);
CREATE INDEX ix_ai_expired_lease ON ai_review_task(lease_expires_at,image_id) WHERE status='PROCESSING';
CREATE INDEX ix_ai_completed_result_order ON ai_review_task(valid,file_created_at,image_id) INCLUDE(certainty) WHERE status='COMPLETED';
CREATE INDEX ix_ai_completed_order ON ai_review_task(file_created_at,image_id) INCLUDE(valid,certainty) WHERE status='COMPLETED';
CREATE STATISTICS st_ai_token_session (dependencies,ndistinct,mcv) ON token_id,session_id FROM ai_review_task;
\ir ../../../scripts/ai-rule-statistics-index.sql
VACUUM (ANALYZE) image_asset;
VACUUM (ANALYZE) ai_review_task;
SELECT status,count(*) FROM ai_review_task GROUP BY status;
