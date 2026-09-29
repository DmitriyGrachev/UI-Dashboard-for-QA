-- Run with psql -f outside a transaction, before enabling history on a large database.
-- No table rewrite; concurrent index creation allows normal reads and writes.
\set ON_ERROR_STOP on
SET lock_timeout = '5s';
SET statement_timeout = '30min';

SELECT EXISTS (
    SELECT 1 FROM pg_index i
    WHERE i.indexrelid = to_regclass('public.ix_operator_review_history') AND NOT i.indisvalid
) AS history_index_invalid \gset
\if :history_index_invalid
DROP INDEX CONCURRENTLY public.ix_operator_review_history;
\endif

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_operator_review_history
    ON public.review_task (assigned_to, reviewed_at DESC, image_id DESC)
    INCLUDE (game_code, decision)
    WHERE status = 'COMPLETED' AND reviewed_at IS NOT NULL;

RESET lock_timeout;
RESET statement_timeout;
