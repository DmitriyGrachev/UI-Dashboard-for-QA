# AI failure diagnostics and large ZIP exports

## Behavior

- The global Failed count opens all failed AI tasks. A rule's Failed count opens that rule's failed tasks. Unsaved rule edits remove the stale count link.
- Screenshot Explorer accepts `aiTaskStatus=PENDING|PROCESSING|COMPLETED|FAILED` and `issuedRuleId=<UUID>`. Omit either to leave it unrestricted. Search, summary, pagination, saved filters and CSV share the same predicates. Other filters still combine with AND; FAILED plus a completed verdict intentionally yields no rows.
- The rule picker shows saved rules, including disabled ones. Deleted/unavailable rules keep their UUID when opening an existing link, so a settings failure never silently widens that search.
- Failure details open automatically and show the error message/code/time, assignment attempts and issuing rule. Rule names reflect the current saved name; the UUID survives deletion. Errors and names are rendered as text. FAILED is an operational error, independent of a completed MISMATCH verdict.
- Existing admin authorization applies; neither inspection nor CSV changes task state. CSV's thirteen-column contract is unchanged.
- ZIP export fixes its candidate snapshot in one streamed SQL query (fetch size 500), then releases the database connection before fetching image bytes. Candidate metadata and the CSV manifest use private temporary files that are removed on success or failure. ZIP image delivery remains streamed.
- Download markers are updated in batches of 1,000 inside the existing completion transaction, only after the whole ZIP finishes and flushes. A failure rolls back every marker batch and the associated Slack outbox write. No external Slack delivery was performed in testing. AI-mismatch ZIP exports remain repeatable and do not mark operator rejects.
- ZIP's central directory and the list of successfully written IDs still grow with entry count. This is not an unlimited-size/background export system. A process crash can leave its temporary files for OS maintenance; normal exception paths remove them.

## Verification

Final verification on 2026-09-18: `mvn clean verify -Dai.rules.scale.url=jdbc:postgresql://127.0.0.1:55440/rv_ai_stats_analysis` passed with 442 tests, no failures/errors/skips (2m45s). `node --test src/test/js/*.test.cjs` passed all 90 tests. Both browser flows passed. Tracked application changes were committed separately for export, SQL, diagnostics and cleanup.

Regression checks cover the former 65,536 SQL-parameter failure, more than 1,000 exported images with identical/null processed dates, concurrent arrivals, output/header/flush/storage failures, multi-batch rollback, all AI statuses, deleted issuing rules, game filters, summary/CSV parity, invalid inputs and existing authorization.

Browser validation uses the real templates, CSS and JavaScript with controlled HTTP responses on the local fixture server. It covers Failed → Explorer → diagnostics → CSV, keyboard activation, deleted rules, empty results, unavailable settings, and activity polling/focus regression at 1440×900 dark, 1280×720 light and 768×900 dark. No normal-flow console errors or horizontal overflow. The fixture uses the existing native date-input fallback, not the optional Flatpickr calendar. Production B2, production auth sessions and actual Slack delivery were not exercised.

SQL evidence and the concurrent index rollout command are in `ai-operations-performance.md`. Production data, deployment, push and merge were not touched.

## Changed files

- docs/ai-failed-and-export.md
- docs/ai-operations-performance.md
- scripts/ai-rule-statistics-index.sql
- src/main/java/com/introlabsystems/recognitionvalidator/ai/dto/AiResultDetails.java
- src/main/java/com/introlabsystems/recognitionvalidator/ai/mapper/AiJdbcMapping.java
- src/main/java/com/introlabsystems/recognitionvalidator/ai/model/AiTaskStatus.java
- src/main/java/com/introlabsystems/recognitionvalidator/ai/repository/AiOperationsRepository.java
- src/main/java/com/introlabsystems/recognitionvalidator/ai/repository/AiResultFilterSql.java
- src/main/java/com/introlabsystems/recognitionvalidator/ai/repository/AiTaskRepository.java
- src/main/java/com/introlabsystems/recognitionvalidator/dao/jdbc/AdminScreenshotRepository.java
- src/main/java/com/introlabsystems/recognitionvalidator/dao/jdbc/RejectedScreenshotExportRepository.java
- src/main/java/com/introlabsystems/recognitionvalidator/dto/request/AdminScreenshotSearchRequest.java
- src/main/java/com/introlabsystems/recognitionvalidator/model/value/AdminScreenshotFilters.java
- src/main/java/com/introlabsystems/recognitionvalidator/service/impl/RejectedScreenshotExportServiceImpl.java
- src/main/resources/static/css/admin.css
- src/main/resources/static/js/admin-screenshots.js
- src/main/resources/static/js/ai-queue.js
- src/main/resources/static/js/ai-result.js
- src/main/resources/templates/admin-ai-queue.html
- src/main/resources/templates/admin-screenshots.html
- src/test/browser/ai-failed.js
- src/test/browser/review-preload-server.cjs
- src/test/java/com/introlabsystems/recognitionvalidator/ai/AiRuleStatisticsLargeScaleTest.java
- src/test/java/com/introlabsystems/recognitionvalidator/controller/AdminScreenshotCsvWebTest.java
- src/test/java/com/introlabsystems/recognitionvalidator/controller/RejectedScreenshotExportWebTest.java
- src/test/java/com/introlabsystems/recognitionvalidator/controller/SlackExportBoundaryTest.java
- src/test/java/com/introlabsystems/recognitionvalidator/service/impl/CoreOperationalLoggingTest.java
- src/test/java/com/introlabsystems/recognitionvalidator/slack/SlackOperationsDataTest.java
- src/test/js/admin-screenshots.test.cjs
- src/test/js/ai-pull-ui.test.cjs
- src/test/js/uiux-regressions.test.cjs
