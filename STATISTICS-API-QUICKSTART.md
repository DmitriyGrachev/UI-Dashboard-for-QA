# Daily statistics: integration quickstart

Read-only endpoint for an external service. Obtain the deployment base URL and
the dedicated statistics key from the application administrator.

## Request

```http
GET /api/integration/statistics/daily?date=2026-09-14
X-API-Key: <STATISTICS_API_KEY>
Accept: application/json
```

`date` is optional (`YYYY-MM-DD`); the default is today in **UTC**, not the
caller's local date. A day runs from 00:00 UTC inclusive to the next midnight exclusive.
No login, cookies or CSRF token are required. The key grants access only to this endpoint.

Example with environment variables on the calling service:

```bash
curl "$BASE_URL/api/integration/statistics/daily?date=2026-09-14" \
  -H "X-API-Key: $STATISTICS_API_KEY" \
  -H "Accept: application/json"
```

The application reads `STATISTICS_API_KEY` from its environment (`.env` for Compose);
restart/recreate it after changing the key. Keep the key server-side and use HTTPS
outside localhost. Send it in the header, never in the URL.

## Response: HTTP 200, application/json

Example values:

```json
{
  "date": "2026-09-14",
  "timezone": "UTC",
  "generatedAt": "2026-09-15T09:00:00Z",
  "operators": {
    "total": 100,
    "accepted": 90,
    "rejected": 10,
    "byOperator": [
      {
        "id": "00000000-0000-0000-0000-000000000001",
        "username": "operator-1",
        "total": 100,
        "accepted": 90,
        "rejected": 10
      }
    ]
  },
  "ai": {
    "total": 80,
    "matched": 70,
    "mismatched": 10,
    "sentToOperators": null,
    "confidence": {
      "below50": 5,
      "from50To79": 10,
      "from80To94": 20,
      "from95To100": 40,
      "unknown": 5,
      "retainedResults": 80,
      "completeCoverage": true
    }
  }
}
```

- All numeric metrics are **counts**, not percentages. Operator and AI totals
  describe independent reviews; do not add them as a count of unique screenshots.
- `byOperator` includes disabled users who have daily statistics; users with no
  daily statistics are omitted. An empty day returns zero calculated counts and `byOperator: []`.
- `ai.matched` / `mismatched` count completed results with `valid=true` / `false`.
  A technical failure without an accepted result is not a mismatch.
- `ai.sentToOperators` is reserved and always **null**, including on empty days.
  AI and operator queues are independent; handoffs are not tracked yet. Treat null
  as unavailable data, not zero. The future counting rule still needs agreement.
- Confidence ranges are **0–49, 50–79, 80–94, 95–100**, inclusive; `unknown` means
  confidence was not provided. These describe the AI's reported confidence.
- Daily totals survive image cleanup. Confidence covers only retained completed
  results: its buckets sum to `retainedResults`. If `completeCoverage=false`,
  this count differs from `ai.total`; do not present the distribution as complete.
- Each call reads current data (`Cache-Control: no-store`). Today's counts can grow.

## Errors

| HTTP | Meaning | Caller action |
| --- | --- | --- |
| 400 | Invalid date | Correct the date. |
| 401 | Missing or incorrect key | Check the `X-API-Key` header and configured key. |
| 503 | Key not configured on the server, or database temporarily unavailable | Check server configuration; retry transient failures with backoff. |

Error bodies use `application/problem+json`. Check the HTTP status before parsing
the statistics payload; never turn a failed request into zero statistics.

Implementation notes: [DAILY-STATISTICS.md](DAILY-STATISTICS.md).
