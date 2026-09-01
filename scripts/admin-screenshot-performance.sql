\set ON_ERROR_STOP on
\echo 'Creating screenshot explorer indexes'

SET lock_timeout = '10s';
SET statement_timeout = 0;

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM pg_class index_relation
    JOIN pg_namespace index_schema ON index_schema.oid = index_relation.relnamespace
    JOIN pg_index index_metadata ON index_metadata.indexrelid = index_relation.oid
    WHERE index_schema.nspname = 'public'
      AND index_relation.relname = 'ix_admin_review_order'
      AND NOT index_metadata.indisvalid
) THEN 'true' ELSE 'false' END AS admin_order_index_invalid \gset

\if :admin_order_index_invalid
DROP INDEX CONCURRENTLY public.ix_admin_review_order;
\endif

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_admin_review_order
    ON public.review_task (file_created_at, image_id);

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM pg_class index_relation
    JOIN pg_namespace index_schema ON index_schema.oid = index_relation.relnamespace
    JOIN pg_index index_metadata ON index_metadata.indexrelid = index_relation.oid
    WHERE index_schema.nspname = 'public'
      AND index_relation.relname = 'ix_admin_review_state_order'
      AND NOT index_metadata.indisvalid
) THEN 'true' ELSE 'false' END AS admin_state_index_invalid \gset

\if :admin_state_index_invalid
DROP INDEX CONCURRENTLY public.ix_admin_review_state_order;
\endif

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_admin_review_state_order
    ON public.review_task (status, file_created_at, image_id);

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM pg_class index_relation
    JOIN pg_namespace index_schema ON index_schema.oid = index_relation.relnamespace
    JOIN pg_index index_metadata ON index_metadata.indexrelid = index_relation.oid
    WHERE index_schema.nspname = 'public'
      AND index_relation.relname = 'ix_admin_review_game_order'
      AND NOT index_metadata.indisvalid
) THEN 'true' ELSE 'false' END AS admin_game_index_invalid \gset

\if :admin_game_index_invalid
DROP INDEX CONCURRENTLY public.ix_admin_review_game_order;
\endif

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_admin_review_game_order
    ON public.review_task (game_code, file_created_at, image_id);

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM pg_class index_relation
    JOIN pg_namespace index_schema ON index_schema.oid = index_relation.relnamespace
    JOIN pg_index index_metadata ON index_metadata.indexrelid = index_relation.oid
    WHERE index_schema.nspname = 'public'
      AND index_relation.relname = 'ix_admin_image_file_name'
      AND NOT index_metadata.indisvalid
) THEN 'true' ELSE 'false' END AS admin_file_name_index_invalid \gset

\if :admin_file_name_index_invalid
DROP INDEX CONCURRENTLY public.ix_admin_image_file_name;
\endif

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_admin_image_file_name
    ON public.image_asset (file_name);

ANALYZE public.review_task;
ANALYZE public.image_asset;

\echo 'Screenshot explorer index migration completed'
