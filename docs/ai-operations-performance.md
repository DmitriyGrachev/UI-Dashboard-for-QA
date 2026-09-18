# Global AI Operations counts

`AiOperationsRepository.snapshot()` is shared by the admin dashboard and Slack operations monitoring. It now counts PROCESSING and FAILED separately in one SQL statement, using partial indexes rather than aggregating every historical task. Expired means PROCESSING with `lease_expires_at <= now`. Counts include NULL and deleted issuing rules. Settings, availability and last-result behavior are unchanged; the transaction/statement budget remains five seconds.

## Migration

Before rolling out, run `psql -f scripts/ai-rule-statistics-index.sql` outside a transaction on the intended database. It adds only `ix_ai_failed_tasks (file_created_at, image_id) INCLUDE (issued_rule_id) WHERE status='FAILED'`. The existing PROCESSING indexes are reused. The script checks definitions, retries invalid concurrent indexes, uses a three-second lock timeout and restores session settings. Re-running succeeded on the disposable fixture. No production migration was executed.

## Local measurements, 2026-09-18

PostgreSQL 17.11, 2 CPUs / 4 GiB, 5m images and 4.4m AI tasks. The static fixture has 44,000 PROCESSING and 44,000 FAILED tasks. This is a vacuumed synthetic fixture, not a production latency promise.

| Query | Plan | Shared pages | Measured time |
| --- | --- | ---: | ---: |
| Previous, first run after DB start | Parallel Seq Scan of 4.4m tasks | 255,200 | 4,440 ms |
| Previous, repeated repository calls | Same full scan | ~255,200 | 515–753 ms |
| New, EXPLAIN ANALYZE | Two Index Only Scans of 88k tasks | 1,234 | 12.7 ms |
| New, repository calls | Same indexed counts | | 19–68 ms |

The final full verification repeated the plans at 2,833.4 ms (previous) and 13.3 ms (new); repository calls took 16–73 ms. Timing varies with cache state and concurrent local work; index reads remained bounded to operational statuses.

The repository benchmark stubs settings and availability, so it measures the count/last-result reads, not full HTTP latency. The baseline did not exceed the five-second statement timeout locally. The regression checks exact values and rejects a full task-table scan; it does not increase timeouts or substitute estimates. Run `mvn clean verify -Dai.rules.scale.url=jdbc:postgresql://127.0.0.1:55440/rv_ai_stats_analysis` only against the dedicated fixture. Plans are saved under `target/ai-rule-statistics-scale/operations-{old-plan,plan}.txt`.
