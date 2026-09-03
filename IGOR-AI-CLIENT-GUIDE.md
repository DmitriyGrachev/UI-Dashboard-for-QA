# Recognition Validator — Integration Guide for Igor's AI Service

V1 contract · Verified against the current implementation on September 3, 2026.

## Overview: who calls whom

**Igor's AI service requests tasks from Recognition Validator.** The scheduler and worker threads run on Igor's side. Validator does not call Igor's API or require a separate callback URL.

One processing cycle:

```text
AI worker                  Recognition Validator                 Storage
    | POST /tasks/claim              |                               |
    |------------------------------->|                               |
    | imageId, claimId, url,          |                               |
    | expected, game, leaseExpiresAt  |                               |
    |<-------------------------------|                               |
    | GET returned url — without X-API-Key -------------------------->|
    |<--------------------------- PNG -------------------------------|
    |                                                                |
    | Validate the image using the AI model                          |
    |                                                                |
    | POST /tasks/{imageId}/result    |                               |
    |------------------------------->|                               |
    | 200, status=COMPLETED           |                               |
    |<-------------------------------|                               |
```

In this diagram, `/tasks` is shorthand. The full paths are provided below.

## 1. What you need before connecting

Get **two values** from Dmytro:

| Value | Purpose |
| --- | --- |
| Validator's HTTPS base URL | For example, `https://validator.example.com`, without a trailing `/api`. This is Validator's address, not Igor's AI service address. |
| Integration API key | Send it in the `X-API-Key` header. On the Validator server, this is the value of `INTEGRATION_IMAGE_API_KEY`. |

The examples use these client-side variables:

```dotenv
VALIDATOR_BASE_URL=https://validator.example.com
VALIDATOR_API_KEY=<obtain separately from Dmytro>
```

`validator.example.com` is a placeholder, not a live service address. Confirm the actual production URL and availability before starting. The key is shared separately, not in this document.

You do not need an operator login, cookies, a CSRF token, B2 access or secret keys, or the local URL signing key. The integration API key does not grant access to the admin interface.

External requests must use HTTPS with certificate verification. Do not send the key over plain HTTP or disable TLS verification. Do not put keys or complete temporary URLs in shared logs or Git.

## 2. Claim one task or a batch

```http
POST /api/integration/ai/tasks/claim?size=1
X-API-Key: <integration key>
```

No request body is required. Send `size` **in the query string**, not in JSON.

| Parameter | Rule |
| --- | --- |
| `size` | Optional integer from `1` to `20`. Defaults to `1`. |

Successful response — HTTP `200`:

```json
{
  "items": [
    {
      "imageId": "0000000000000000000000000000000000000000000000000000000000000001",
      "claimId": "314fd2b3-0e1a-46e8-a974-a5a99d40a01b",
      "url": "https://validator.example.com/api/integration/images/0000000000000000000000000000000000000000000000000000000000000001/content?expires=EXAMPLE&signature=EXAMPLE",
      "expected": "7K",
      "game": "SINGLE_DECK",
      "leaseExpiresAt": "2026-09-03T12:02:00Z"
    }
  ]
}
```

All IDs, signatures, and timestamps in this example are illustrative. In real requests, use only values returned by the server.

| Task field | Meaning |
| --- | --- |
| `imageId` | Image identifier: 64 lowercase hexadecimal characters. Use it in the result submission path. |
| `claimId` | UUID for this particular assignment. Return **the same UUID**; do not generate a new one. If the image is assigned again, its `claimId` changes. |
| `url` | Ready-to-use temporary image download URL. Use the complete URL without modifying it. |
| `expected` | Cards recognized by the source system that the AI should verify against the image. This is the recognition output being checked, not guaranteed ground truth. |
| `game` | Always `SINGLE_DECK` in V1. |
| `leaseExpiresAt` | Task deadline in UTC / ISO 8601. The server must accept a new result before this time. |

The response **always contains an `items` array**, even when `size=1`.

- The requested `size` is an upper limit, not a guaranteed count.
- With `size=5`, the response may contain 5, 3, 1, or 0 tasks. Process every item actually returned.
- If no matching tasks are available, issuing is stopped, or no active rule matches:

```json
{"items": []}
```

This is HTTP `200`, not an error or a signal to stop the scheduler permanently. An empty response may be temporary: for example, other workers may already hold the tasks. Pause before requesting again.

### How images are selected

V1 issues only `bj_single_deck_ags` images, represented as `SINGLE_DECK` in the external JSON.

Filters are configured by the Validator administrator. The client sends only `size` in the claim request, not Token, Session, Game, or dates.

- Rules with a lower numeric `priority` are evaluated first.
- Rules with the same priority are ordered by rule ID.
- Within each rule, images are ordered by `file_created_at`, oldest first, then by `imageId`.
- Conditions within a rule are combined with AND. Overlapping rules do not duplicate a task within a batch.
- Concurrent workers skip tasks already locked by another worker. Strict global completion order is not guaranteed.

### The `expected` format

The value uses the **first active hand marked by `_u_`**, not the dealer or other hands.

- Ranks: `A`, `2`…`9`, `10`, `J`, `Q`, `K`. Ten is `10`, not `T`.
- Cards are concatenated without spaces or suits.
- Exactly two cards have no trailing comma: `7K`, `A10`.
- Any other card count has a trailing comma: `22104,` = `2, 2, 10, 4`; `22769K,` = `2, 2, 7, 6, 9, K`.
- Do not automatically strip the trailing comma: it is part of the current format.

## 3. Download the image

Send a normal **GET request to the task's `url`**.

- The response contains binary PNG data, not JSON or base64.
- The URL does not have to end in `.png`.
- The link may point to Validator or directly to B2.
- No additional `X-API-Key` is needed for the download: the URL signature already authorizes access.
- Do not attach the API key to the HTTP client used to download arbitrary task URLs. Otherwise, you could send the key to B2 or another host.
- The HTTP client may follow redirects. Preserve the entire query string; do not rebuild it manually.
- JSON decoding converts `\u0026` into a normal `&`. Use the decoded string.

A local URL expires at approximately `leaseExpiresAt + 30 seconds`. A B2 URL is valid for at least that interval and may remain valid longer. **This does not extend the result submission deadline.** The URL may still work after results for that claim would be rejected.

If downloading fails, do not submit `valid=false`: that is a technical failure, not a recognition mismatch. Retry temporary download failures within a bounded time budget. If processing cannot finish, leave the task to expire.

## 4. Submit the validation result

For **each image separately**:

```http
POST /api/integration/ai/tasks/{imageId}/result
X-API-Key: <integration key>
Content-Type: application/json
```

The body must be a flat JSON object: not multipart, not an array, and not a `{"result": ...}` wrapper.

Match example:

```json
{
  "claimId": "314fd2b3-0e1a-46e8-a974-a5a99d40a01b",
  "valid": true,
  "verdict": "MATCH",
  "certainty": 97,
  "confidence": 95,
  "message": "Observed active hand matches expected 7K"
}
```

Mismatch example:

```json
{
  "claimId": "314fd2b3-0e1a-46e8-a974-a5a99d40a01b",
  "valid": false,
  "verdict": "MISMATCH",
  "certainty": 93,
  "confidence": 90,
  "message": "Expected 7K, observed 7Q"
}
```

These are **alternative examples**, not two results to submit for the same task. In production, the model determines the values.

| Field | Required | Constraints |
| --- | --- | --- |
| `claimId` | Yes | UUID from the claim response for this `imageId`. |
| `valid` | Yes | JSON boolean: `true` or `false`, not the strings `"true"` / `"false"`. |
| `verdict` | Yes | One of the case-sensitive values in the table below. |
| `certainty` | No | Integer `0…100` or `null`. Not a fraction in `0…1`, not `97.5`, and not a string such as `"97"`. |
| `confidence` | No | Integer `0…100` or `null`, with the same rules. |
| `message` | No | String of at most 2000 UTF-16 code units, as measured by Java `String.length()`, or `null`. May briefly describe the model's observation. |

An omitted percentage or `null` means “unknown.” `0` is an actual zero value, not a substitute for unknown. Validator stores the supplied `certainty` and `confidence`; it does not calculate them for the model.

The total request body must not exceed **16 KiB**. Unknown JSON fields are ignored. Do not include the image or other binary data in the result.

| `verdict` | Required `valid` |
| --- | --- |
| `MATCH` | `true` |
| `MISMATCH` | `false` |
| `LOW_CONFIDENCE` | `false` |
| `HAND_COUNT_MISMATCH` | `false` |
| `NO_HANDS_FOUND` | `false` |

Other combinations, such as `valid=true` with `MISMATCH`, are rejected. `LOW_CONFIDENCE` is a model verdict, not a way to report an HTTP timeout or service failure.

Once the result is accepted, the server returns HTTP `200`:

```json
{
  "imageId": "0000000000000000000000000000000000000000000000000000000000000001",
  "status": "COMPLETED"
}
```

Validator stores the AI result separately from the operator's result. Completing an AI task does not mark the screenshot as reviewed by a human. `certainty` remains visible in result details; there is no certainty filter in the UI.

## 5. Workers, deadlines, and safe retries

### Recommended simple approach

Run a bounded number of workers. **Each available worker calls `claim?size=1`, processes one image, submits its result, and then claims another task.** Start acceptance testing with one worker.

Alternatively, use one dispatcher: set `size` to the number of available worker slots, up to 20 per request. Each batch item has its own `claimId`, and each result is submitted in a separate request.

- 20 is the maximum response batch size, not a stated server throughput.
- Do not claim 20 tasks for slow sequential processing in one thread: the lease is already running for the entire batch.
- Do not share mutable `currentImageId` or `currentClaimId` variables, or a single `image.png` file, across workers. Pass the complete task object and use unique filenames.
- During an active lease, the server does not assign the same AI task to another worker. A human operator may independently review the same screenshot; this is allowed.
- If a worker crashes or misses the deadline, the task may be assigned again with a **new `claimId`**. The model may process an image more than once; exactly-once processing is not guaranteed.

### Time budget

Use `leaseExpiresAt` from each response. **Do not hard-code the lease duration in the client.** It is configurable on the server. The default is 2 minutes, but a particular environment may use a different value.

This budget must cover receiving the task response, downloading the image, running the model, and having the server accept the result. Set finite HTTP timeouts and leave time for result submission. Keep the client machine's clock synchronized.

V1 has **no** lease extension, heartbeat, release, or worker technical-error endpoint. If the AI cannot validate an image, do not invent a `MATCH` or `MISMATCH`: submit no result. After the lease expires, the server recovers such tasks during subsequent claim requests.

### Retrying result submission

If `POST .../result` fails with a network error, timeout, or temporary server error, the result may already have been saved.

1. Keep the original `imageId`, `claimId`, and complete result object.
2. Retry **the same** result with the same `claimId`, using delays and a limited number of attempts.
3. An identical, already accepted result returns `200` without another write or a change to the validation timestamp. This also works after the original lease deadline, while the record still exists.
4. If the previous result was not accepted and the lease expired or the task was reassigned, the server returns `409 STALE_CLAIM`. Stop submitting that attempt.
5. If a result was already accepted but the retry differs in any field, the server returns `409 RESULT_CONFLICT`. Do not try to overwrite it.

Do not add a new timestamp or retry counter to `message` on each retry: that changes the result. JSON field order does not matter, but values must match. Do not substitute a new `claimId` into an old result.

### Lost claim response

`POST .../claim` is **not idempotent**: the server may reserve tasks without the client receiving the response. Retrying the claim may return different tasks, not recover the previous response.

Do not enable uncontrolled automatic retries for claims. Pause after losing a response; tasks from the lost response become available again after their leases expire. V1 has no separate endpoint for recovering a lost batch.

### Pausing and stopping

- For `{"items":[]}`, a 1–2 second pause with a small random variation between workers is recommended.
- For temporary `503` responses and network failures, use increasing delays, for example 1 → 2 → 4 → 8 seconds, with an upper limit. These are client recommendations, not mandatory API intervals.
- When shutting down the client, stop claiming new tasks and, where possible, finish in-flight tasks before their leases expire.
- If an administrator selects Stop issuing, new claims return an empty batch. Results for previously issued tasks can still be submitted within their leases.

## 6. API errors

Queue controller errors normally contain `code` and `message`, for example:

```json
{
  "code": "STALE_CLAIM",
  "message": "Claim expired or was replaced"
}
```

Authentication uses a different format (`application/problem+json`), for example:

```json
{
  "status": 401,
  "code": "UNAUTHORIZED",
  "title": "Unauthorized",
  "detail": "A valid X-API-Key header or image signature is required."
}
```

Handle the HTTP status first, then `code` if the response can be decoded as JSON. A proxy or storage service may return a non-JSON response. Do not rely on the exact `message` text.

| HTTP / `code` | Meaning and action |
| --- | --- |
| `200`, `items: []` | No tasks are being issued right now. Pause and poll again. |
| `400 INVALID_REQUEST` | Invalid fields, types, ID, `size`, or `valid`/`verdict` combination. Fix the request; do not retry the same invalid JSON. |
| `401 UNAUTHORIZED` | The correct key is missing; for signed downloads, the signature may be missing, invalid, or expired. Check which request failed. |
| `404 TASK_NOT_FOUND` | The AI task does not exist; for example, its metadata may have been deleted. Stop submitting the result and notify Dmytro. |
| `409 STALE_CLAIM` | The lease expired or the task was reassigned. Stop submitting the old attempt. |
| `409 RESULT_CONFLICT` | A different result is already stored for this claim. Do not overwrite it; check the client's retry behavior. |
| `413 RESULT_TOO_LARGE` | The body exceeds 16 KiB. Reduce the JSON size; do not attach the image. |
| `503 INTEGRATION_NOT_CONFIGURED` | The integration key is not configured on Validator. Stop new requests and notify Dmytro. |
| `503 DELIVERY_NOT_CONFIGURED` | B2 link delivery or the public HTTPS address is not configured. Validator-side configuration is required. |
| `503 DELIVERY_UNAVAILABLE` | A temporary delivery error prevented preparation of any image. Retry claiming later, with a delay. |
| `503 DATABASE_UNAVAILABLE` | Temporary database error. For result retries, preserve the original `claimId` and JSON. For claims, account for non-idempotency. |
| Other `5xx`, timeout, connection loss | Temporary failure or an uncertain outcome. Use bounded retries; for results, resend only the same JSON. |

For `403` or `415`, also check the path, HTTP method, and `Content-Type`. Do not use the integration key to access operator or admin endpoints.

During downloads, B2 may return its own errors, including `403/404`. These are not recognition results.
