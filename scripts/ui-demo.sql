\set ON_ERROR_STOP on
-- Synthetic UI fixtures. Run only against the dedicated local demo database.
BEGIN;
DO $$ BEGIN
    IF current_database() <> 'recognition_validator_ui_demo' THEN
        RAISE EXCEPTION 'This fixture requires recognition_validator_ui_demo';
    END IF;
END $$;
SET LOCAL TIME ZONE 'UTC';

INSERT INTO app_user (id, username, password_hash, role, enabled, created_at)
VALUES ('dddddddd-0000-0000-0000-000000000001', 'demo.operator', '!', 'OPERATOR', false, now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO operator_daily_statistics (operator_id, statistics_date, total_checked, matched_count, not_matched_count)
SELECT 'dddddddd-0000-0000-0000-000000000001', current_date - n, total, total - rejected, rejected
FROM generate_series(0,29) n
CROSS JOIN LATERAL (SELECT 500 + (29-n)*14 + (n%7)*40 AS total, 50 + (n%4)*7 AS rejected) v
ON CONFLICT (operator_id, statistics_date) DO UPDATE SET total_checked=EXCLUDED.total_checked,
    matched_count=EXCLUDED.matched_count, not_matched_count=EXCLUDED.not_matched_count;

INSERT INTO ai_daily_statistics (statistics_date, total_checked, matched_count, not_matched_count, last_checked_at)
SELECT current_date - n, total, total - mismatched, mismatched, now() - n*interval '1 day' - interval '1 minute'
FROM generate_series(0,29) n
CROSS JOIN LATERAL (SELECT 900 + (29-n)*22 + (n%6)*70 AS total, 80 + (n%5)*9 AS mismatched) v
ON CONFLICT (statistics_date) DO UPDATE SET total_checked=EXCLUDED.total_checked,
    matched_count=EXCLUDED.matched_count, not_matched_count=EXCLUDED.not_matched_count, last_checked_at=EXCLUDED.last_checked_at;

INSERT INTO ai_queue_settings (id, revision, enabled) VALUES (1,1,true) ON CONFLICT (id) DO NOTHING;
INSERT INTO ai_selection_rule (id, name, enabled, priority, game_code, token_id) VALUES
    ('dddddddd-1111-0000-0000-000000000001','Priority sessions · demo',true,1,'bj_igt',53),
    ('dddddddd-1111-0000-0000-000000000002','IGT fallback · demo',true,2,'bj_igt',NULL),
    ('dddddddd-1111-0000-0000-000000000003','NetEnt · demo',true,3,'bj_netent',NULL),
    ('dddddddd-1111-0000-0000-000000000004','Single Deck · demo',false,4,'bj_single_deck_ags',NULL)
ON CONFLICT (id) DO NOTHING;

CREATE TEMP TABLE demo_tasks ON COMMIT DROP AS
SELECT r.id AS rule_id, r.game_code, r.priority,
       md5('ui-demo-' || r.priority || '-' || n) || md5('ui-demo-image-' || r.priority || '-' || n) AS image_id,
       'demo-' || r.priority || '-' || n || '.png' AS filename,
       CASE WHEN r.priority=1 THEN 53 ELSE 54 END AS token_id,
       now() - (n%30)*interval '1 day' - n*interval '1 minute' AS created_at, n,
       CASE WHEN n<=64 THEN 'COMPLETED' WHEN n<=69 THEN 'FAILED' WHEN n<=79 THEN 'PROCESSING' ELSE 'PENDING' END AS status
FROM ai_selection_rule r CROSS JOIN generate_series(1,100) n
WHERE r.id::text LIKE 'dddddddd-1111-%';

INSERT INTO image_asset (id, file_name, relative_path, file_created_at, file_modified_at, discovered_at,
    last_seen_at, file_available, game_code, token_id, session_id, dealer_cards, active_user_cards,
    is_notification, has_hit, has_stand, has_double, has_split, parse_status)
SELECT image_id, filename, filename, created_at, created_at, now(), now(), true, game_code, token_id,
    'demo-session-' || priority, 'K', 'A,8', false, true, true, false, false, 'SUCCESS'
FROM demo_tasks ON CONFLICT (id) DO NOTHING;

INSERT INTO ai_review_task (image_id, status, file_created_at, game_code, token_id, session_id,
    is_notification, has_user_hand, file_available, attempt_count, issued_rule_id, claim_id, lease_expires_at,
    valid, verdict, confidence, certainty, message, checked_at, last_error_code, last_error_message, last_error_at)
SELECT image_id, status, created_at, game_code, token_id, 'demo-session-' || priority,
    false, true, true, CASE WHEN status='PENDING' THEN 0 WHEN status='FAILED' THEN 3 ELSE 1 END,
    CASE WHEN status<>'PENDING' THEN rule_id END,
    CASE WHEN status='PROCESSING' THEN gen_random_uuid() END,
    CASE WHEN status='PROCESSING' THEN now() + CASE WHEN n<=76 THEN interval '10 minutes' ELSE interval '-2 minutes' END END,
    CASE WHEN status='COMPLETED' THEN n%9<>0 END,
    CASE WHEN status='COMPLETED' THEN CASE WHEN n%9<>0 THEN 'MATCH' ELSE 'MISMATCH' END END,
    CASE WHEN status='COMPLETED' THEN 75+n%25 END, CASE WHEN status='COMPLETED' THEN 75+n%25 END,
    CASE WHEN status='COMPLETED' THEN 'Synthetic result for the local UI demonstration.' END,
    CASE WHEN status='COMPLETED' THEN created_at + interval '30 seconds' END,
    CASE WHEN status='FAILED' THEN CASE WHEN n%2=0 THEN 'AI_REJECTED' ELSE 'DELIVERY_UNAVAILABLE' END END,
    CASE WHEN status='FAILED' THEN 'Demo: image could not be read. No AI service was called.' END,
    CASE WHEN status='FAILED' THEN now() - interval '4 minutes' END
FROM demo_tasks ON CONFLICT (image_id) DO UPDATE SET lease_expires_at=EXCLUDED.lease_expires_at;

INSERT INTO ai_rule_activity (rule_id, last_issued_at, last_issued_count, last_result_at,
    last_error_at, last_error_code, last_error_message, last_error_image_id)
SELECT rule_id, now() - (priority*2)*interval '1 minute', 10, now() - interval '1 minute',
    now() - interval '4 minutes', 'AI_REJECTED', 'Demo: image could not be read.', image_id
FROM demo_tasks WHERE n=68
ON CONFLICT (rule_id) DO UPDATE SET last_issued_at=EXCLUDED.last_issued_at, last_result_at=EXCLUDED.last_result_at,
    last_error_at=EXCLUDED.last_error_at;
COMMIT;
