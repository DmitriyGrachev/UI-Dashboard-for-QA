# Explorer query and thumbnail checks — 2026-09-29

Measured only on the disposable local PostgreSQL 17 test database: 100,000 images,
review tasks and AI tasks. No production queries or data changes.

## Exact summary query

AI status/date/rule filters previously forced a join to `image_asset`, even when
no selected filter used image metadata. The summary now joins that table only
for metadata filters (file name, token, session, storage or parse status).
The `review_task.image_id` foreign key guarantees the image exists. Search, CSV
and summary still share the same filter predicates; results remain exact.

Three `EXPLAIN (ANALYZE, BUFFERS)` runs per case, median execution time:

| Filters | Matching rows | Before | After |
| --- | ---: | ---: | ---: |
| All screenshots | 100,000 | 14.8 ms | 14.8 ms |
| AI reviewed day | 5,000 | 94.3 ms | 24.2 ms |
| AI Failed | 80,000 | 155.5 ms | 86.8 ms |

The day query removes 5,000 image index lookups: shared buffer hits fall from
24,063 to 4,063. The Failed query removes an image sequential scan and one hash
join: shared hits fall from 10,362 to 7,731; temporary blocks from 2,247 to 989.
These are local fixture measurements, not production latency guarantees.
No new index or cache was needed for these cases.

The summary has a five-second read-only transaction timeout. A database lock test
checks cancellation, HTTP 503, and successful summary/search after releasing the
lock. Screenshot results render independently of the summary. Client cancellation
discards obsolete detail/summary reads; server timeout bounds an abandoned COUNT.

Reproduce with `mvn -Dtest=AiRuleStatisticsScaleTest,AdminScreenshotExplorerWebTest test`.
Plans are written to `target/explorer-plans/` and timings to test output.

## Thumbnails

A deterministic 1920×1080 PNG fixture, two warm-ups and ten requests through the
authenticated thumbnail endpoint: median 33 ms, max 36 ms. Output remains 320×180
JPEG with `Cache-Control: max-age=300, private`. Explorer now appends result rows
without recreating existing thumbnails.

No server thumbnail cache was added: remote B2 latency and repeated requests from
different administrators were not measured. Measure that workload before adding
another cache and its memory/invalidation costs.

## Related UI behavior

Reloading Explorer restores the selected page from the tab's history cursor,
without fetching every earlier page. The current filters stay unchanged; Search
starts again at the oldest matching result. Existing Copy link targets one image.
Failed pagination preserves results and retries the same cursor.

Operator Focus mode persists in browser preferences and keeps filters, cards,
zoom and decisions available. Toggle it off to restore account/navigation controls.
AI rule Processing/Completed/Failed counts link to Explorer; their update times
are identified separately. Failed task diagnostics can be copied, with selectable
text as fallback when clipboard access is unavailable.

## Verification

`mvn clean verify` passed: 470 tests, 467 passed, 3 opt-in large-scale cases skipped.
All 100 JavaScript tests passed. Eight browser suites passed, covering Explorer
continuity, operator workspace, preload (15 scenarios), AI activity/failures,
admin workspace, navigation and usability. Layouts covered both themes,
1440×900, 1280×720, 768 px and 390 px, plus 200% text scaling.
Browser flows used rendered application templates and controlled API fixtures;
real B2 delivery latency and production-sized data were not tested in this run.

Statistics startup now has a transactional one-time checkpoint; the first start
after upgrading still performs the existing bounded backfill. See
`DAILY-STATISTICS.md` for the initial-upgrade and backup considerations.
