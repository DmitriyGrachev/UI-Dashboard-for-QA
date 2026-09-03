\set ON_ERROR_STOP on
-- After scale-fixture.sql and the migration. Writes synthetic data; NEVER a production diagnostic.
DO $$ BEGIN
    IF current_database() <> 'ai_pull_migration_test' THEN RAISE EXCEPTION 'Disposable database only'; END IF;
END $$;
SET statement_timeout = '10min';
-- Missing images at the head previously caused a million image-asset lookups.
UPDATE image_asset SET file_available=false WHERE id<=lpad('1000000',64,'0');
-- Positive AI results far from the operator queue head must not require scanning the entire queue.
UPDATE ai_review_task SET status='COMPLETED',valid=true,verdict='MATCH',certainty=97,
    checked_at='2026-09-03T10:00:00Z' WHERE image_id>=lpad('1400000',64,'0');
ANALYZE image_asset;
ANALYZE review_task;
ANALYZE ai_review_task;
