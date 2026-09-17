# AI rule activity (2026-09-17)

## Behavior

The AI queue page shows the last assignment batch, last accepted result, overdue assignment count,
and last recorded error for each saved rule. Diagnostic links open a screenshot in the existing explorer.
Activity refreshes every 15 seconds while the page is visible; it does not rerun the exact Remaining
calculation. Both kinds of statistics have their own refresh button and timestamp.

`GET /admin/api/ai-queue/operations/activity` requires an admin session and returns `Cache-Control: no-store`.
It returns the settings revision, generation time, `lastIssuedRuleIds`, and per-rule activity.
Unsaved settings, a revision mismatch, and request failures hide the marker rather than showing misleading activity.

The marker identifies the most recent recorded assignment batch among currently saved rules.
One batch can use several rules, so more than one marker is valid. Assignment time comes from the database,
not from a browser clock or an estimate based on a lease. It records committed server assignments;
image preparation may subsequently fail before the AI client receives the task.

## Storage and concurrency

`ai_rule_activity` retains one snapshot per rule ID ever used, independently of image retention and the
delete/reinsert performed by settings saves. This is not a per-task event log. Deleted rules are omitted
from the activity endpoint; their stored snapshot remains if that same ID is restored.

Activity writes participate in the existing claim/result transaction. Failed transactions leave no activity.
Idempotent result/rejection retries do not advance timestamps. Older concurrent batches cannot move timestamps
backwards. Multi-rule activity writes use a fixed UUID lock order even if settings priorities differ.

Old activity cannot be reconstructed reliably: fields start null until a new tracked event occurs.
Existing overdue tasks are still visible because those counts use the current PROCESSING rows and lease deadlines.
All overdue assignments are counted, including more than the 100 tasks eligible for recovery in a single claim.
No assignment or lease changes occur while reading activity. The last error is historical and may refer to
a task that has since recovered or whose image has been removed by retention. Recorded errors come from
AI rejections and image-preparation failures; overdue leases are shown separately, not appended to an event log.

## Deployment prerequisite

Only a disposable test database was changed during development. For an existing installation, apply
`scripts/ai-rule-activity.sql` as an authorized database migration before running the updated application.
The script creates one new table without a backfill and includes the existing concurrent-index migration.
It adds `ix_ai_rule_overdue` on the lease deadline, including rule and image IDs, for PROCESSING rows only.
The existing lease-recovery index cannot cover this query: fetching rule IDs from 44,000 wide heap pages
took 6.5 seconds on the large fixture (107 ms with those pages already in cache).
The new index avoids that dependence on cached task pages. It does not replace the ordered recovery index.

Run with `psql -f`, outside an enclosing transaction. Concurrent index creation scans the table and consumes I/O,
but allows normal writes; a three-second lock wait limit prevents waiting indefinitely for conflicting transactions.
The migration is rerunnable, rejects unexpected index definitions and rebuilds invalid indexes left by an interrupted build.
New installations include it through `scripts/ai-pull-integration.sql`; Hibernate creates the activity table in small tests.

## Scope of the refactor

Operational response records now live in `ai.dto` and JDBC conversion in `ai.mapper.AiJdbcMapping`.
The old `AiCardPayloadMapper`, which had no application callers, and its obsolete test were removed.
Existing API fields, selection predicates, assignment limits, and lease durations are unchanged.

## Reproduce verification

- `node --test src/test/js/*.test.cjs`
- `mvn clean verify -Dai.rules.scale.url=jdbc:postgresql://127.0.0.1:55440/rv_ai_stats_analysis`
- Initialize the disposable large database using `src/test/sql/ai-rule-statistics-scale-fixture.sql`.
  The new activity scale check uses its three persisted rules and the same 5 million images / 4.4 million tasks.
- Browser: start `node src/test/browser/review-preload-server.cjs`, then run
  `src/test/browser/ai-activity.js` through the Playwright tool's filename argument.
  It serves the actual template/CSS/JS with controlled API responses; it does not access application data.
  Covers 1440x900 dark, 1280x720 light and 768x900 dark; diagnostic links, text escaping, keyboard refresh,
  draft preservation, error/retry, back-navigation restoration, and visible-page-only polling without heavy rule-statistics requests.

The real production environment and live B2/AI services are outside this local verification.

## Measured results

Final verification: `mvn clean verify` with the large fixture enabled succeeded in 2:43;
436 tests passed, zero failures/errors/skips. All 89 JavaScript tests passed. Browser checks passed
at all three documented sizes, including keyboard focus preservation during polling and back navigation.

PostgreSQL 17.11, 2 CPUs / 4 GiB, 5 million images and 4.4 million AI tasks, including 44,000 overdue tasks.
The statement timeout remains five seconds.

| Query | Result |
| --- | --- |
| Activity before the covering index | First measured scan: 6,526 ms; cached repeat: 107 ms. 44,559 buffer accesses. |
| Activity with the covering index | Repository calls: 67 / 37 / 37 ms. Plan: 37 ms, 667 index buffer accesses, zero heap fetches. |
| Existing optimized rule statistics | Six repository calls: 696–3,751 ms, with and without parallel workers; counts still equal the frozen legacy query. |
| Legacy vs optimized rules, diagnostic EXPLAIN | 10,904 ms vs 1,452 ms. The legacy query still exceeds the application timeout. |
| 100k-row, 20-rule fixture | One statistics query: 213 ms; one streaming export query: 1,955 ms. |

The new partial index occupies about 5.2 MiB on this fixture. Index-only scans depend on PostgreSQL's
visibility map; recently updated pages may still need heap checks. Measurements describe this local fixture,
not production throughput. Plans are generated in `target/ai-rule-statistics-scale/`.

The migration was replayed successfully and recovered its own invalid index after a concurrent build
hit the three-second lock wait limit during a benchmark. No production database was queried or migrated.

## Changed files

Paths relative to the repository root (including the removed mapper and its test):

- `scripts/ai-pull-integration.sql`
- `scripts/ai-rule-activity.sql`
- `scripts/ai-rule-statistics-index.sql`
- `src/main/java/com/introlabsystems/recognitionvalidator/ai/controller/AiOperationsController.java`
- `src/main/java/com/introlabsystems/recognitionvalidator/ai/dto/AiOperationsSnapshot.java`
- `src/main/java/com/introlabsystems/recognitionvalidator/ai/dto/AiRuleActivity.java`
- `src/main/java/com/introlabsystems/recognitionvalidator/ai/dto/AiRuleActivitySnapshot.java`
- `src/main/java/com/introlabsystems/recognitionvalidator/ai/dto/AiRuleStatisticsSnapshot.java`
- `src/main/java/com/introlabsystems/recognitionvalidator/ai/mapper/AiCardPayloadMapper.java`
- `src/main/java/com/introlabsystems/recognitionvalidator/ai/mapper/AiJdbcMapping.java`
- `src/main/java/com/introlabsystems/recognitionvalidator/ai/model/AiRuleActivityEntity.java`
- `src/main/java/com/introlabsystems/recognitionvalidator/ai/repository/AiOperationsRepository.java`
- `src/main/java/com/introlabsystems/recognitionvalidator/ai/repository/AiRuleActivityRepository.java`
- `src/main/java/com/introlabsystems/recognitionvalidator/ai/repository/AiSettingsRepository.java`
- `src/main/java/com/introlabsystems/recognitionvalidator/ai/repository/AiTaskRepository.java`
- `src/main/resources/static/css/admin.css`
- `src/main/resources/static/js/ai-queue.js`
- `src/main/resources/templates/admin-ai-queue.html`
- `src/test/browser/ai-activity.js`
- `src/test/browser/review-preload-server.cjs`
- `src/test/java/com/introlabsystems/recognitionvalidator/ai/AiCardPayloadMapperTest.java`
- `src/test/java/com/introlabsystems/recognitionvalidator/ai/AiRuleActivityTest.java`
- `src/test/java/com/introlabsystems/recognitionvalidator/ai/AiRuleStatisticsLargeScaleTest.java`
- `src/test/java/com/introlabsystems/recognitionvalidator/ai/AiRuleStatisticsScaleTest.java`
- `src/test/java/com/introlabsystems/recognitionvalidator/ai/AiRuleStatisticsTest.java`
- `src/test/java/com/introlabsystems/recognitionvalidator/ai/AiTaskRepositoryTest.java`
- `src/test/java/com/introlabsystems/recognitionvalidator/ai/AiTestSupport.java`
- `src/test/java/com/introlabsystems/recognitionvalidator/ai/controller/AiOperationsControllerTest.java`
- `src/test/java/com/introlabsystems/recognitionvalidator/controller/AdminScreenshotCsvWebTest.java`
- `src/test/js/ai-queue.test.cjs`
- `src/test/sql/ai-rule-statistics-scale-fixture.sql`
- `docs/ai-rule-activity.md`
