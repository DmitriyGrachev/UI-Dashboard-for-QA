# AI pull integration (V1)

Client-facing handoff for Igor: [API client guide](IGOR-AI-CLIENT-GUIDE.md).

Igor owns the scheduler and parallel workers. Validator does **not** call Igor's API.
Each worker claims work, downloads the returned URL, validates locally, and posts the result.
Rules can issue any game configured in `validator.games`; the outgoing `game` is its exact game code.

## API contract

All claim/result/reject calls require `X-API-Key: <INTEGRATION_IMAGE_API_KEY>` over HTTPS.
They do not use an operator login, cookie or CSRF token. Do not put the API key in URLs.
Interactive OpenAPI documentation is available at `/swagger-ui.html`; raw JSON is at `/v3/api-docs`.

```bash
# size is optional (default 1), range 1..20. Use only the number of available workers.
curl --fail-with-body -X POST "$VALIDATOR_URL/api/integration/ai/tasks/claim?size=2" \
  -H "X-API-Key: $INTEGRATION_IMAGE_API_KEY"
```

```json
{
  "items": [
    {
      "imageId": "<64-character image ID>",
      "claimId": "<UUID of this attempt>",
      "url": "https://...temporary-download-url...",
      "imageName": "original-screenshot.png",
      "game": "bj_single_deck_ags",
      "leaseExpiresAt": "2026-09-08T10:10:00Z"
    }
  ]
}
```

Always an `items` array: requesting 5 can return 1, 3, 5, or `[]`. Empty queue or
paused issuance returns HTTP 200 with `{"items":[]}`. It does not wait for work.
Priority rules are evaluated from position 1 onward; within a rule the
oldest `(file_created_at, image_id)` is first. Rules fill the remaining batch capacity.
Overlapping rules do not duplicate a task. No implicit fallback beyond configured rules.

The URL returns PNG bytes without an extra download header: either a B2 presigned
URL or a locally served HMAC-signed URL. Treat it as a temporary bearer credential;
do not log or publish it. Local URLs expire 30 seconds after the lease. B2 URLs use
at least that lifetime. Metadata availability is not a guarantee that an external
filesystem cleanup or B2 lifecycle rule has not removed the file.

`imageName` contains the complete original filename including its extension, without a
directory path. `expected` is no longer returned or parsed as a prerequisite for issuing a task.

```bash
curl --fail-with-body -X POST "$VALIDATOR_URL/api/integration/ai/tasks/$IMAGE_ID/result" \
  -H "X-API-Key: $INTEGRATION_IMAGE_API_KEY" -H 'Content-Type: application/json' \
  --data '{"claimId":"<claim UUID>","valid":false,"verdict":"MISMATCH","certainty":93,"confidence":90,"message":"Observed cards differ"}'
```

Success: `{"imageId":"...","status":"COMPLETED"}`.

- Required: `claimId` (UUID), `valid` (boolean), `verdict` (string).
- `MATCH` requires `valid=true`. `LOW_CONFIDENCE`, `MISMATCH`, `HAND_COUNT_MISMATCH`,
  `NO_HANDS_FOUND` require `valid=false`.
- `certainty` and `confidence` are optional integers 0..100; absent/null means unknown, not zero.
- `message` is optional, at most 2000 characters. Entire JSON body: at most 16 KiB.
- Unknown fields are ignored; wrong types, quoted numbers/booleans, fractional percentages,
  unsupported verdicts and inconsistent results return 400. Oversized body returns 413.
- Wrong/missing API key: 401. Integration key not configured: 503. Unknown task: 404.
- Expired/reassigned claim or a conflicting repeat result: 409. Never retry it under a new claim ID.
- Repeating the identical accepted result with its original claim ID returns 200 without changing `checked_at`.
  This also works after its former lease expiry, until retention removes the task.
- Temporary database/storage/configuration failures return 503, not a fake AI mismatch.

Workers must preserve the exact received claim ID. On a network error posting a result,
retry the **same result** with backoff while the lease is live. On 409 discard it.
If downloading or the model fails, do not invent `valid=false`: submit
`POST /api/integration/ai/tasks/reject` with `imageId`, `claimId` and a nonblank `message`.
The failure is stored for human review; the AI task is no longer issued automatically.
There is no renew/release endpoint. Unreported expired leases are recovered
in bounded groups during subsequent claims; some duplicate model computation is therefore possible.
Ownership and result acceptance are fenced by claim ID, so an old worker cannot overwrite a new attempt.
On `[]` poll with a delay (e.g. 1–2 seconds); on 503 use increasing backoff.
Do not request 20 items for a single sequential worker if it cannot finish them within the lease.

The existing test downloader remains: `GET /api/integration/images/{imageId}/content`
with `X-API-Key`. A signed image URL cannot authorize claim/result or admin requests.

## Configuration

```dotenv
INTEGRATION_IMAGE_API_KEY=<random integration key shared securely with Igor>
AI_TASK_LEASE_DURATION=10m

# Required for local image delivery; not needed when every selected image is available in B2.
VALIDATOR_PUBLIC_BASE_URL=https://validator.example.com
AI_IMAGE_LINK_SIGNING_KEY=<separate random secret, at least 32 bytes, stays on Validator>
```

The lease accepts 10 seconds through 1 hour and is an environment setting (restart required).
Existing B2 settings are reused. Local public origin must be HTTPS without credentials,
query, fragment or path prefix. Configure a reachable HTTPS reverse proxy and prohibit
public plaintext access to the application port. `localhost` on Igor's machine is not Validator.
No Igor-service URL, outbound timeout, circuit breaker, or outgoing 500 ms scheduler is needed.
Do not share the local signing secret or B2 secret with Igor.

In `/admin` → **AI queue**:

1. Add up to 20 rules: name, enabled, game, UTC date bounds, Token, Session and Has user hand.
   New rules default to **Single Deck**. Empty optional fields mean any value. Conditions within a rule use AND.
2. Move rules up/down to set their unique sequential priorities (1, 2, 3…). Date lower bound is inclusive;
   upper bound is exclusive.
3. Check **Allow new AI claims** and save. A new installation starts disabled with no rules.
4. **Stop issuing** immediately saves disabled state using the last saved rules. Active tasks may finish.
5. **Reload saved settings** discards unsaved edits. A concurrent admin save returns a conflict;
   reload, review and save again. Configurations are read as a coherent snapshot per claim.

Move up/down edits the draft only. **Save AI settings** commits the whole ordered list;
claims that read settings after that commit use the new order. A claim already in flight
can finish using its previous snapshot, and existing task leases remain unchanged.
Disabled rules are skipped; an enabled rule with no available candidates contributes zero.
An earlier rule can fill the entire requested batch, so a continuously replenished broad
rule above a narrow rule can keep the latter from issuing work. Put specific rules first
when that is the intended priority; this is not round-robin scheduling.

### Rule diagnostics

**Refresh rule statistics** in Issuance settings reads
`GET /admin/api/ai-queue/operations/rules` (admin session, no writes).
The response contains `revision`, `enabled`, `generatedAt` (UTC), and
`rules: [{ruleId, remaining, processing, completed, failed}]` in saved priority order.

- `remaining` assigns each eligible screenshot to its first matching enabled rule.
  It reuses claim's game/date/token/session/hand, retry and local/B2 availability predicates.
  `default` is an ordinary rule: its name adds no special fallback or cross-game behavior.
- The preview includes the same oldest 100 expired assignments that the next claim can
  recover, without locking or changing them. Concurrent claims can change the snapshot;
  these counts reserve nothing and do not predict rows skipped because another claim holds a lock.
- Disabled rules and paused queues have zero remaining. Status counts still apply.
- `processing`, `completed`, and `failed` count retained tasks by `issued_rule_id`,
  regardless of today's rule conditions. These are task counts, not attempt or lifetime totals.
  Expired tasks remain Processing until actually recovered, so columns are not additive.
- All rules are counted by one aggregate statement, alongside one settings read in a
  read-only repeatable-read transaction (5-second timeout). No migration or background polling.
  Draft changes hide counters until saved/reloaded; a revision mismatch requires reload.

The scale regression uses 100,000 tasks and 20 overlapping rules, checks one aggregate
statement, and applies the same 5-second budget. Its local timings are not production guarantees.

- INFO `AI queue settings saved` is emitted after the save commits: revision, queue state,
  counts, authenticated `actor`, and `order=[priority:ruleId:enabled|disabled, ...]`.
  Rule names, sessions, filter contents, API keys and signed URLs are not included.
- DEBUG `AI rule candidates selected` includes the settings revision, rule ID, priority,
  candidate count and remaining batch capacity **before** that rule. These are selections
  inside a transaction, not a claim-success record; a later failure can roll them back.
- DEBUG `AI claim completed` includes the same revision and the number of deliverable
  tasks after committing assignments and preparing links. `ai_review_task.issued_rule_id`
  stores which rule assigned a task.

To inspect a problem temporarily, start with these Spring arguments (DEBUG is off by default):

```text
--logging.level.com.introlabsystems.recognitionvalidator.ai.repository.AiTaskRepository=DEBUG
--logging.level.com.introlabsystems.recognitionvalidator.ai.service.AiQueueService=DEBUG
```

Verified on 2026-09-14 against disposable PostgreSQL: real browser Up/Down (including
keyboard focus at list boundaries), draft versus saved order, reload, and subsequent AI
claims. HTTP tests cover disabled/empty/overlapping rules, stale-save conflicts and
preservation of active claims. No production rule ordering was changed.

Operator `/review` and admin `/admin/screenshots` default to AI **All** (no AI restriction).
**Checked by AI** (`aiResult=CHECKED`) includes every completed AI result, whether matched
or unmatched and whether percentages are known. **Unchecked by AI** (`UNCHECKED`) includes
absent, pending, processing and technically failed AI tasks. **Matched** and **Unmatched**
remain available separately. Operator checked/unchecked and Match/Not match are independent.
Both screens also support `aiVerdict` (`MATCH`, `LOW_CONFIDENCE`, `MISMATCH`,
`HAND_COUNT_MISMATCH`, `NO_HANDS_FOUND`) and inclusive `confidenceFrom` / `confidenceTo`
bounds from 0 to 100. Unknown confidence values are excluded when a bound is set.
Verdict/percentage restrictions apply to completed results; combining them with Unchecked
returns no matches. Old `certaintyFrom` / `certaintyTo` fields remain ignored.
The result API stores `certainty` and `confidence` unchanged.
Details display verdict, percentages, time, message and technical failure code as plain text.
The screenshot explorer keeps cursor pagination (50 per page, API maximum 100), asynchronous
counts and individual download via button / D shortcut.

## Deployment (one-time additive migration)

Multi-game upgrade: existing installations must rerun `scripts/ai-pull-integration.sql`
before starting the multi-game application. It adds `ai_selection_rule.game_code`,
sets legacy rules to `bj_single_deck_ags` (the previous queue only issued Single Deck),
normalizes their priority to `1..N` in the previous `(priority, id)` order, and enforces
required game codes and unique priorities. It also creates missing AI tasks for other
games without resetting existing pull results. The obsolete `notification` column is
retained, but is no longer an active AI rule filter; review rules before resuming workers.

An AI Queue page showing **Unavailable** with a server log mentioning a missing
`game_code` needs this migration, not a browser refresh. `DATABASE_SCHEMA_ERROR`
instructs an administrator to check migrations and logs; it does not expose SQL.
For a temporary `DATABASE_UNAVAILABLE` error, retry later. Result submissions must
reuse the same `claimId` to preserve idempotency.

September 8 update: the migration now also creates `ai_task_rejection` for technical
failure history. Existing installations must create this table before using `/tasks/reject`
when automatic schema updates are disabled; rerunning the script is supported. Rejection
history is retained independently of screenshot cleanup. Set `AI_TASK_LEASE_DURATION=10m`
in the deployed environment if it already explicitly overrides the old 2-minute default.
Update AI clients to read `imageName` before switching to this claim contract.

1. Back up PostgreSQL as usual. Disable the **old push scheduler** and let its current
   request finish. For the final cutover, stop the old application/metadata writers while
   backfilling so newly indexed rows cannot appear behind the migration cursor.
   Alternatively, keep AI issuance off and re-run the migration once after switching
   all writers to the new application. Do not run old push and new pull consumers together.
2. Build the new application image. Before starting it, run the migration against the intended DB:

   Keep the previously deployed `review-queue-performance.sql`, `review-filter-performance.sql`
   and admin explorer indexes. In particular, operator Token/Session + AI filters still use
   `ix_review_pending_token_order` / `ix_review_pending_session_order`; the new AI indexes
   complement them, not replace them. A fresh large database needs those baseline migrations too.

   ```bash
   cd /opt/dataox/validator-api-build/app
   docker compose exec -T validator-api-db \
     sh -lc 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
     < scripts/ai-pull-integration.sql
   ```

3. Require `missing_ai_tasks = 0`, `incorrect_availability_projections = 0`
   and all listed indexes valid/ready.
   The script commits backfill in batches of 5000, builds partial indexes concurrently,
   and copies old completed AI results when the old columns exist.
   It never resets new pull results/rules or drops old columns. Hibernate update alone
   does not perform this backfill or create the partial indexes. Re-running the script
   is safe, but is not required for every ordinary redeploy.
   Progress is printed every 20 batches. The script needs table/index/function/trigger
   ownership permissions and should be run without an outer transaction (`psql -1` is unsuitable).
4. Deploy the new image with the environment above. Old pushed-service settings are ignored.
5. Configure rules with issuance still disabled. Verify API-key rejection, HTTPS image reachability,
   then enable issuance and let one Igor worker complete one real claim before increasing concurrency.

## Verification and rollback boundaries

Local tests use a disposable PostgreSQL on 5433, never the developer DB on 5436.
`mvn test` covers indexing, concurrent claims, independent operator locks, leases,
idempotency, strict HTTP results, filters and retention. `node --test src/test/js/*.test.cjs`
covers UI helpers. Scale fixtures under `src/test/sql/` refuse to run outside the explicitly
named empty `ai_pull_migration_test` database; they are not production diagnostics.

AI availability is projected into `ai_review_task` so an unavailable million-row prefix
does not cause a million image lookups. A small `AFTER UPDATE` trigger synchronizes local/B2
availability in the same transaction, including JDBC and JPA writes. The batch indexer also
initializes these fields. The migration installs the trigger; on a fresh Hibernate-created
database the application installs it once. Normal startup only checks its presence. Existing
databases still require the migration/backfill/indexes; do not disable the trigger.

Completed-result filters use a one-to-one AI join and the AI timestamp/index for ordered
selection; this avoids sorting every matching result before returning the next screenshot.
All/Unchecked keep their existing queue order and the explorer retains its keyset cursor.
Partial indexes cover pending game/token/session, expired leases, and ordered
completed results (with or without a Match/Unmatched constraint).

Local verification on 2026-09-03: `mvn clean verify` passed 268 tests (no skips/failures),
and the JavaScript suite passed 45 tests. Browser checks covered settings save/stop/persistence,
AI filters, plain-text messages, image loading and form overflow at wide/narrow viewports.
The application also started with `ddl-auto=validate` against the migration-created AI schema.

After removing certainty filtering and adding `CHECKED`, `mvn clean verify` passed
275 tests and the JavaScript suite passed 45 tests. State-filter regression tests cover
All (including omitted filter), Checked, Matched, Unmatched, Unchecked, legacy certainty
parameters, summaries, operator claims and cursor pagination.

For repeatable scale checks, use a disk-backed disposable PostgreSQL with sufficient space
(not the small RAM-only unit-test DB): schema → `ai-pull-scale-fixture.sql` → migration →
`ai-pull-scale-plans.sql` → `ai-pull-scale-stress.sql` → plans again. The fixture contains
1.5 million images/review tasks; stress makes the oldest million images unavailable and
clusters 100,001 positive AI results near the newest end. These measurements are SQL execution
times on synthetic local data, not production HTTP latency guarantees.

Stress-query execution times (first measured / subsequent warm run where available):

| Query | SQL execution |
| --- | --- |
| Claim 20 after 1,000,000 unavailable images | 2.54 / 0.57 ms (before projection: timeout at 20 s) |
| AI Token + Session, generic prepared plan | 30.21 / 7.04 ms |
| Empty AI Token + Session | 0.06 ms |
| Operator oldest AI Match, 100,001 results near the tail | 2.25 / 0.10 ms (before ordered AI join: 3.69 s) |
| Admin confidence 90–100, newest page | 6.23 / 0.83 ms |
| Operator exact confidence 50, oldest task | 4.51 / 0.18 ms |
| Confidence 90–100 result summary | 326.18 / 197.24 ms |
| Operator AI + Token + Session, baseline indexes present | 125.75 ms with disk reads; empty combination 0.12 ms |

The ordered completed-result index keeps interactive confidence-filtered pages fast. The aggregate
summary scans the completed subset, but the measured cost does not justify another production index yet.

Locking variants were also checked: 20 AI rows with `FOR UPDATE OF ai SKIP LOCKED`
took 0.47 ms; one operator row with `FOR UPDATE OF rt SKIP LOCKED` took 2.42 ms.
These are bounded local samples, not a sustained concurrent production load test.

Live AI leases protect metadata from our retention cleanup. They cannot prevent an external
filesystem/B2 cleanup. Database transactions end before preparing/downloading images or running AI.
`FOR UPDATE OF ai SKIP LOCKED` is used only for queue ownership; see
[PostgreSQL locking documentation](https://www.postgresql.org/docs/17/sql-select.html#SQL-FOR-UPDATE-SHARE).
Partial index predicates use explicit constants so PostgreSQL can recognize them even for
prepared statements; see [partial indexes](https://www.postgresql.org/docs/17/indexes-partial.html).

Old push source is preserved locally in `archive/ai-push-integration`; old main has its own
`archive/main-before-ai-pull` reference. Restoring old code is not a reverse data migration:
new pull results live in `ai_review_task`, not in old `review_task.ai_*` columns.
Pause consumers before any rollback and preserve the database; never reset/drop result tables to roll back code.

Final real-service acceptance still requires Igor's updated pull worker and a shared reachable
HTTPS origin/B2 URL. Local MockMvc/browser tests do not claim to validate his deployed scheduler.
