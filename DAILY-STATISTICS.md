# Daily statistics API

Set a separate random `STATISTICS_API_KEY` in `.env` and restart the application.
Compose passes it to Spring; an empty value disables this integration. Do not put
the key in a URL, browser JavaScript, source control or logs. Use HTTPS outside localhost.

```http
GET /api/integration/statistics/daily?date=2026-09-14
X-API-Key: <STATISTICS_API_KEY>
```

Omitting `date` selects today in UTC. Invalid dates return 400; a missing/wrong key
returns 401; unconfigured access or an unavailable database returns 503. Only GET
on this endpoint is authorized by the statistics key. Image/AI integration keys
and logged-in admin sessions do not grant access to it.

The response has `date`, `timezone: "UTC"`, `generatedAt`, and:

- `operators`: `total`, `accepted`, `rejected`, `byOperator` with ID, username and
  the same three counts. Includes inactive operators with statistics for the day;
  operators without a daily row are omitted. An empty day has zero counts and an empty list.
- `ai`: `total`, `matched`, `mismatched`, `confidence`. Matched/mismatched use
  `valid=true/false`; mismatched includes every non-MATCH verdict.
- `ai.confidence`: `below50` (0–49), `from50To79`, `from80To94`, `from95To100`,
  `unknown` (NULL confidence), `retainedResults`, `completeCoverage`.

Operator/AI totals use existing persistent daily aggregates. Confidence describes
only completed AI task rows still retained for that day, based on `checked_at` in
the half-open UTC interval `[day start, next day start)`. `completeCoverage=false`
means retained rows do not match the aggregated AI total; deleted results are not
misrepresented as unknown confidence or zero-confidence results. The values are
reported AI confidence, not a measured probability of correctness.

The queries share one read-only repeatable-read transaction, limited to 15 seconds.
Responses are `Cache-Control: no-store`. Swagger exposes a separate `StatisticsApiKey`
security scheme. No database migration is required for this endpoint.

# Shared operator queue reads

The review page's **left to review** count includes matching PENDING and ASSIGNED
tasks across all operators. `/api/review-tasks/summary` now returns `asOf`,
`refreshing`, and `failed` in addition to the count/date range. A cold cache returns
202 with null counts while loading; an initial failure returns 503. A previous
snapshot remains available with HTTP 200 during refresh or failure. All calls still
require operator session authorization and CSRF.

Visible tabs poll every five seconds. The server keeps up to 128 normalized filter
keys, refreshes each at most once concurrently, and uses two count workers. Snapshots
live for five seconds after the query completes. Typical display lag is up to ten
seconds plus query time; under load or errors it can be longer. The output tooltip
shows `asOf`, and the page marks loading/failure. Decisions do not decrement a private
browser counter. Claim/decision requests do not wait for these background counts.

Candidate buffering uses at most 128 filters × `REVIEW_CANDIDATE_BATCH_SIZE` IDs
(default 30, range 1–100; 1 disables buffering). It holds no image bytes and reserves
no work. IDs expire after five seconds; assignment rechecks all predicates and locks
the selected review row in PostgreSQL. Stale/exhausted candidates fall back to a live
search. Concurrent claims remain distinct even with overlapping filters or multiple
application instances. Newly arrived older work may wait for the current buffer to
expire or drain; the buffer is not a strict global FIFO snapshot.

Both caches are per application process. Multiple instances can briefly serve
different timestamps and each performs its own bounded refreshes; database locking,
not the cache, enforces assignment ownership. Memory is bounded and unused keys are
evicted as new filters arrive. Compare batch size 1 and 30 under the same workload
before attributing a measured latency improvement to buffering.

## Local verification (2026-09-14)

- Full isolated-database suite: 393 Java tests passed; JavaScript suite: 74 passed.
- Thirty sequential database assignments used one full candidate search and thirty
  ID-constrained validation queries. Eight concurrent operators received distinct
  images; unassigned batch candidates remained PENDING.
- Two browser sessions on a disposable database showed the same remaining count:
  completing one review updated both sessions from 12 to 11. A late response for
  a previous filter did not overwrite the current count.
- Review layouts checked at 1440, 1280, and 768 px widths, including light and dark
  themes, with no horizontal page overflow.
- The app on port 8080, connected to the existing local database, returned 200 for
  the daily statistics request with its dedicated key and 401 without the header.
  The database was only queried for this check; browser decisions used test data.

These checks demonstrate query reuse and correctness, not a production latency
benchmark. Shared counts remain eventually consistent as described above.

## Different and overlapping filters

Each distinct normalized filter has its own candidate buffer and count snapshot.
Two operators with the same filter share them; different filters do not share their
results. A slow candidate refill does not hold the buffer lock for another filter
(database connections and database resources are still shared).

Overlapping filters can contain the same candidate ID. It is only a hint: the claim
query repeats the current filter and PENDING/availability checks and locks the task.
An assignment through one filter therefore cannot also be assigned through another.
After completion, each matching filter's count changes on its next successful
refresh. Non-matching counts stay unchanged; overlapping counts must not be summed.

Additional isolated-database checks on 2026-09-14:

- 60 sequential assignments alternating between two disjoint filters used two full
  candidate searches and 60 ID-constrained validation queries within the cache TTL.
- Eight simultaneous claims against warmed, overlapping buffers returned distinct
  matching images. Along with three warm-up claims, 11 tasks were assigned and the
  other 21 remained PENDING, not reserved by a batch.
- Completing one image changed broad/session-A/session-B counts from 32/16/16 to
  31/15/16. Cache tests also check normalization and independent count snapshots.

This does **not** mean 30 times faster requests or 30 times fewer total SQL queries.
The cold path adds a buffer-fill query; assignments still require database ownership
checks and updates. With infrequent claims, frequent filter changes, five-second
expiry, or many stale overlapping hints, buffering may provide little benefit or
extra overhead. The exact COUNT still costs a database query on refresh; caching
shares that work and takes it off the browser's claim/decision path. Millisecond
latency, throughput and CPU/RAM/SQL profiling remain deferred; no speedup percentage
is claimed for these changes.

After the rule-order and mixed-filter checks, the full isolated-database suite passed
398 Java tests and 75 JavaScript tests (2026-09-14, no failures or skips).
