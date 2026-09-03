\set ON_ERROR_STOP on
-- Disposable database ONLY: cloned schema, no user data. Never run against production.
DO $$ BEGIN
    IF current_database() <> 'ai_pull_migration_test' OR EXISTS (SELECT 1 FROM image_asset) THEN
        RAISE EXCEPTION 'Requires the empty disposable ai_pull_migration_test database';
    END IF;
END $$;
\timing on
INSERT INTO image_asset (id, relative_path, file_name, game_code, token_id, session_id, file_available,
    file_created_at, file_modified_at, discovered_at, last_seen_at, is_notification,
    has_stand, has_hit, has_double, has_split, has_surrender, parse_status, payload_raw, active_user_cards)
SELECT lpad(i::text,64,'0'), i||'.png', i||'.png', 'bj_single_deck_ags', i%10000, 'session-'||(i%10000), true,
    timestamptz '2026-08-01' + i*interval '1 second', now(), now(), now(), i%100=0,
    true,true,false,false,false,'SUCCESS','d_Six_u_Seven_King_bSbH','Seven_King'
FROM generate_series(1,1500000) i;
INSERT INTO review_task(image_id,status,file_created_at,game_code,token_id,session_id,is_notification,has_user_hand)
SELECT id,'PENDING',file_created_at,game_code,token_id,session_id,is_notification,true FROM image_asset;
-- Simulate the old push schema (including one historical completed result).
ALTER TABLE review_task ADD COLUMN ai_status varchar(16), ADD COLUMN ai_valid boolean,
    ADD COLUMN ai_verdict varchar(32), ADD COLUMN ai_checked_at timestamptz,
    ADD COLUMN ai_certainty integer, ADD COLUMN ai_confidence integer, ADD COLUMN ai_attempt_count integer;
UPDATE review_task SET ai_status='COMPLETED',ai_valid=false,ai_verdict='MISMATCH',
    ai_checked_at='2026-09-01T10:00:00Z',ai_certainty=93,ai_confidence=0,ai_attempt_count=1
WHERE image_id=lpad('1',64,'0');
ANALYZE image_asset;
ANALYZE review_task;
-- Baseline production queue indexes are SQL migrations, not Hibernate @Index declarations.
CREATE INDEX IF NOT EXISTS ix_review_pending_order ON review_task(file_created_at,image_id) WHERE status='PENDING';
CREATE INDEX IF NOT EXISTS ix_review_pending_game_order ON review_task(game_code,file_created_at,image_id) WHERE status='PENDING' AND game_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_review_pending_token_order ON review_task(token_id,file_created_at,image_id) WHERE status='PENDING' AND token_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_review_pending_session_order ON review_task(session_id,file_created_at,image_id) WHERE status='PENDING' AND session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_review_pending_notification_true_order ON review_task(file_created_at,image_id) WHERE status='PENDING' AND is_notification=TRUE;
CREATE STATISTICS IF NOT EXISTS st_review_token_session (dependencies,ndistinct) ON token_id,session_id FROM review_task;
ANALYZE review_task;
