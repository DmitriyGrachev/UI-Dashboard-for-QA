# AI rule statistics: selective rules and vacuum

The statistics query now exposes active rule conditions in `WHERE`, using the same predicate builder as claim. Previously those conditions appeared only in the ownership `CASE`. PostgreSQL could filter rows, but did not estimate their selectivity correctly or use the game/token/session claim indexes to narrow the pending scan.

The ordered `CASE` still assigns each eligible screenshot to its first active matching rule. Availability, bounded expired-lease recovery, and historical counts by `issued_rule_id` remain unchanged. No new index, migration, timeout increase, or approximate count is introduced.

## Local evidence (PostgreSQL 17, 2026-09-30)

`AiRuleStatisticsPlanTest` uses 12,000 synthetic pending rows, 100 matching two overlapping rules, and the indexes already shipped in `scripts/ai-pull-integration.sql` and `scripts/ai-rule-statistics-index.sql`. It runs ANALYZE, without VACUUM. Both versions return 50 + 50 tasks in one query.

| | Before | After |
|---|---:|---:|
| Median execution, three warm runs | 5.908 ms | 2.791 ms |
| Shared buffer hits (saved plan) | 584 | 334 |
| Pending access | Sequential scan | Existing `ix_ai_pending_game_order` |
| Pending row estimate / actual | 11,940 / 100 | 100 / 100 |

Plans are regenerated in `target/ai-rule-plans/`. These numbers demonstrate a selective-filter improvement, not a production benchmark or proof of the production timeout cause. Broad rules still require an exact scan of eligible tasks. Millions of rows were not loaded or tested.

The UI retains the previous snapshot on refresh failure only while its settings revision still matches. It explicitly marks the counts as potentially out of date. After a settings change, old counts are not presented as current.

## Before changing production maintenance settings

The reported value “20” does not identify the setting. `autovacuum_vacuum_threshold=20` means a base count, whereas a 20% scale factor is `0.2`. The vacuum trigger is base threshold plus scale factor times estimated table rows. At five million rows, `0.2` contributes about one million changed/deleted tuples. Increasing the threshold delays vacuum. ANALYZE has separate triggers. See [PostgreSQL 17 autovacuum settings](https://www.postgresql.org/docs/17/runtime-config-autovacuum.html) and [routine vacuuming](https://www.postgresql.org/docs/17/routine-vacuuming.html).

Vacuum restores visibility-map coverage needed for efficient index-only scans; ANALYZE refreshes planner statistics. Adding a selection rule does not itself update millions of image rows. A changed query plan, concurrent writes, missing/invalid indexes, or delayed maintenance need to be distinguished before tuning.

The following metadata-only queries can be run by a database administrator. They do not scan screenshot contents or alter settings. No production queries were run during this change.

```sql
SELECT c.relname, c.reltuples::bigint AS estimated_rows, c.reloptions,
       s.n_dead_tup, s.n_mod_since_analyze, s.last_autovacuum, s.last_autoanalyze
FROM pg_class c JOIN pg_stat_user_tables s ON s.relid=c.oid
WHERE s.schemaname=current_schema() AND c.relname IN ('image_asset','ai_review_task');

SELECT name, setting, unit FROM pg_settings
WHERE name LIKE 'autovacuum%threshold' OR name LIKE 'autovacuum%scale_factor';

SELECT t.relname AS table_name, x.relname AS index_name, i.indisvalid,
       pg_get_indexdef(i.indexrelid) AS definition
FROM pg_index i JOIN pg_class t ON t.oid=i.indrelid
JOIN pg_class x ON x.oid=i.indexrelid JOIN pg_namespace n ON n.oid=t.relnamespace
WHERE n.nspname=current_schema() AND t.relname IN ('image_asset','ai_review_task');
```

Next production diagnosis requires the actual saved rules and a query plan during the slowdown, with explicit permission and an agreed load window. Do not run VACUUM FULL or raise application timeouts as a workaround.
