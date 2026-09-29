# Operator review history

Operators can open **My history** (`/history`) to inspect their own retained
completed reviews. The page shows the filename, game, decision and review time
in UTC, newest first. It is read-only: opening history or a screenshot does not
claim a task, renew a lease, change a decision or update statistics.

The page reads at most 26 tasks to display 25 rows and an **Older reviews** link.
Pagination uses `(reviewed_at, image_id)` rather than OFFSET or a total COUNT.
Images load only when opened, through the existing authenticated image endpoint.
The preview supports keyboard navigation, Escape, retry and opening the original.
Rows disappear when the existing retention process deletes their source tasks.

## Database preparation

Before deploying history to a large database, run
[`scripts/operator-history-index.sql`](../scripts/operator-history-index.sql)
with `psql -f`, outside a transaction. This creates a partial covering index on
`review_task (assigned_to, reviewed_at DESC, image_id DESC)` concurrently, allowing
normal reads and writes. Index construction still consumes database resources;
schedule it during a quiet period. It is deliberately not a Hibernate startup
index. The script can be repeated and removes an invalid index left by an
interrupted build before retrying. It stops on errors instead of continuing.

The page has a five-second query timeout and shows a retry message if the database
cannot serve history. No index or maintenance operation has been run against the
working/production database as part of this change.

## Verification

Small isolated fixtures cover operator isolation, ordering and pagination,
unchanged assignments/statistics, empty results, invalid cursors and authorization.
Browser checks cover lazy image loading, failure/retry, keyboard focus, both themes,
four window sizes and 200% text. The index script was run twice on the disposable
PostgreSQL test database. Multi-million-row performance testing was intentionally
not run; no production latency claim is made.

Verified on 2026-09-29: `mvn clean verify` with `AiRuleStatisticsScaleTest` and
`AiRuleStatisticsLargeScaleTest` excluded passed 473 tests; one opt-in B2 benchmark
was skipped. All 100 JavaScript tests passed, together with the operator workspace,
history and 15 preload browser scenarios against local fixtures.
