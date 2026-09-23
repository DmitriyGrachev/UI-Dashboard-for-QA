# Local UI demo

The preview uses `recognition_validator_ui_demo` on local PostgreSQL port 5436. The original `recognition_validator` database is unchanged. Existing users were copied, so the same login credentials work. The header displays **Local demo · synthetic data**.

Fixtures in `scripts/ui-demo.sql` provide 30 days of daily statistics, four AI rules (including a fallback and a disabled rule), and 400 retained AI tasks: 256 completed, 20 failed, 40 processing, 84 pending. Each image also has its operator queue projection so Explorer can list it. Historical daily totals intentionally exceed retained task counts. This represents history retained after image cleanup, not additional live tasks.

The image files are demo copies of one local screenshot, under `%LOCALAPPDATA%\RecognitionValidator\ui-demo-images`. They are illustrative; game/card metadata is synthetic. No AI service is connected. Processing deadlines expire normally, so the number of overdue tasks increases over time.

Refresh the demo dates and deadlines without adding duplicates:

```powershell
Get-Content -Raw scripts/ui-demo.sql | docker exec -i validator-api-db psql -U validator -d recognition_validator_ui_demo -v ON_ERROR_STOP=1
```

The fixture refuses to run in any other database. It is not a production migration and is never run by application startup or tests.

For a fresh demo database, copy only the schema and users from the local database, then run the fixture:

```powershell
docker exec validator-api-db createdb -U validator recognition_validator_ui_demo
docker exec validator-api-db bash -o pipefail -c 'pg_dump -U validator -d recognition_validator --schema-only --no-owner --no-acl | psql -U validator -d recognition_validator_ui_demo -v ON_ERROR_STOP=1 -q'
docker exec validator-api-db bash -o pipefail -c 'pg_dump -U validator -d recognition_validator --data-only --table=app_user --no-owner --no-acl | psql -U validator -d recognition_validator_ui_demo -v ON_ERROR_STOP=1 -q'
```

Provide PNG copies named `demo-1-1.png` through `demo-4-100.png` in the demo image root. Start the existing jar with these local overrides (DB credentials remain those of the local container):

```text
--spring.datasource.url=jdbc:postgresql://localhost:5436/recognition_validator_ui_demo
--spring.jpa.hibernate.ddl-auto=validate
--server.port=8080 --server.address=127.0.0.1
--validator.ui-demo=true
--validator.image-root=<absolute demo image root>
--validator.watch-enabled=false --validator.cleanup-cron=-
--validator.b2.enabled=false --slack.enabled=false
--slack.operations.daily-summary-enabled=false --slack.operations.alerts-enabled=false
```

To return to real local data, restart with the original datasource and image root and omit `--validator.ui-demo=true`. No `.env` changes are needed.
