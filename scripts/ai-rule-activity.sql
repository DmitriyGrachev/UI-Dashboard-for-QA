\set ON_ERROR_STOP on
-- Small independent snapshot table; no backfill, rewrite or lock of the existing task tables.
BEGIN;
SET LOCAL lock_timeout='3s';
SET LOCAL statement_timeout='30s';
CREATE TABLE IF NOT EXISTS ai_rule_activity (
    rule_id uuid PRIMARY KEY,
    last_issued_at timestamptz,
    last_issued_count integer,
    last_result_at timestamptz,
    last_error_at timestamptz,
    last_error_image_id varchar(64),
    last_error_code varchar(64),
    last_error_message varchar(1000)
);
COMMIT;
