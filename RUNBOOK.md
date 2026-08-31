# Recognition Validator — Docker Runbook

Практическая инструкция для запуска и обслуживания Recognition Validator через
Docker Compose. Команды выполняются из каталога проекта, где находятся
`compose.yaml` и `.env`.

Для первого включения B2 поверх работающей production-БД используйте
[DEPLOY-B2.md](DEPLOY-B2.md): backup, проверка без upload, включение и отключение B2.

## 1. Подготовка окружения

Требования:

- Docker Desktop на Windows или Docker Engine с Compose plugin на Linux;
- доступ Docker к папке со скриншотами;
- отдельная папка на хосте для файлов PostgreSQL.

Если `.env` отсутствует:

```powershell
Copy-Item .env.example .env
```

Минимальный пример `.env` для Windows:

```dotenv
SERVER_PORT=18080
POSTGRES_HOST_PORT=5436
APP_MEMORY_LIMIT=5g
VALIDATOR_IMAGE_ROOT_HOST=C:/Users/dimag/Downloads/test
POSTGRES_DATA_ROOT_HOST=C:/recognition-validator-data/postgres
DB_NAME=recognition_validator
DB_USERNAME=validator
DB_PASSWORD=replace-with-a-strong-password
COUNT_REMAINING_SCREENSHOTS=true
```

Пример путей для Linux-сервера:

```dotenv
SERVER_PORT=18080
POSTGRES_HOST_PORT=5436
APP_MEMORY_LIMIT=5g
VALIDATOR_IMAGE_ROOT_HOST=/data/recognition-api/completed_recognition
POSTGRES_DATA_ROOT_HOST=/opt/dataox/validator-api-build/postgres-data
DB_NAME=recognition_validator
DB_USERNAME=validator
DB_PASSWORD=replace-with-a-strong-password
COUNT_REMAINING_SCREENSHOTS=true
```

`VALIDATOR_IMAGE_ROOT_HOST` монтируется в контейнер read-only. Содержимое PNG не
сохраняется в PostgreSQL. `POSTGRES_DATA_ROOT_HOST` является постоянным хранилищем
БД и не должен находиться внутри контейнера.

### Опциональное хранилище Backblaze B2

B2 отключено по умолчанию. Для включения заполните в `.env` все следующие значения
(`compose.yaml` передаёт их в `validator-api-app` без изменения имён):

```dotenv
B2_ENABLED=true
B2_ENDPOINT=https://s3.eu-central-003.backblazeb2.com
B2_BUCKET=replace-with-one-bucket
B2_ACCESS_KEY_ID=replace-with-application-key-id
B2_SECRET_ACCESS_KEY=replace-with-application-key-secret
B2_OBJECT_PREFIX=validator/
B2_UPLOAD_BATCH_SIZE=1000
B2_UPLOAD_CONCURRENCY=8
B2_UPLOAD_DELAY=10s
B2_UPLOAD_RETRY_DELAY=5m
B2_LOCAL_PREFERRED_AGE=3d
B2_PRESIGNED_URL_TTL=30m
B2_METADATA_RETENTION=21d
B2_CONNECT_TIMEOUT=5s
B2_SOCKET_TIMEOUT=30s
B2_API_CALL_ATTEMPT_TIMEOUT=45s
B2_API_CALL_TIMEOUT=2m
B2_MAX_ATTEMPTS=4
```

`B2_METADATA_RETENTION` должен оставаться равным `21d`: приложение отклонит
другое значение, чтобы срок метаданных не расходился с lifecycle bucket.

`B2_ENDPOINT` берётся со страницы bucket без имени bucket; регион выводится из
`s3.<region>.backblazeb2.com`. Соединение с B2 всегда HTTPS. Application key
ограничьте одним bucket, правами чтения/записи и prefix `validator/`; право
`list all buckets` для штатной работы не нужно. В B2 настройте lifecycle для
prefix `validator/`: скрыть объекты через 21 день, удалить hidden versions ещё
через 1 день.

Внутри Compose PostgreSQL использует
`jdbc:postgresql://validator-api-db:5432/<DB_NAME>`; отдельный host-порт B2 не
нужен и в Compose не публикуется. До возраста 3 дней локальный PNG имеет приоритет;
после 3 дней или при пропаже локального файла broker возвращает авторизованный
`307 Temporary Redirect` на pre-signed URL с `Cache-Control: no-store`. ZIP-экспорт
читает B2 серверным потоком. Локальные метаданные хранятся 4 календарных UTC-дня,
cloud-backed — 21 день от `cloud_uploaded_at`; физические PNG Validator не удаляет.

Для rollback установите `B2_ENABLED=false` и перезапустите `validator-api-app`.
Новые upload, B2 client и scheduler отключатся, локальная индексация продолжит
работать, а объекты B2 удаляться не будут (ими управляет lifecycle).

#### Smoke-проверка (только после явного approval, не выполнять автоматически)

Перед любым PUT в реальный bucket требуется отдельное письменное разрешение
владельца. Используйте только один выделенный тестовый PNG и не рабочий файл,
например `bj_xchange_1_00000000-0000-0000-0000-000000000001_d_A_01-01-2026-00-00-00_1.png`.
После approval включите B2, дождитесь строки с этим именем в `image_asset` и
`cloud_uploaded_at`, проверьте `cloud_object_key` с prefix `validator/`, затем
проверьте в UI `307` и один серверный ZIP. Результат зафиксируйте; тестовый объект
оставьте lifecycle или удалите отдельной согласованной процедурой.

## 2. Запуск и проверка

Собрать и запустить приложение с PostgreSQL:

```bash
docker compose up -d --build validator-api-app
```

Проверить контейнеры:

```bash
docker compose ps
```

Ожидаемое состояние:

- `validator-api-db` — `healthy`;
- `validator-api-app` — `Up`;
- порт приложения опубликован на `SERVER_PORT` Docker-host.

Посмотреть запуск приложения:

```bash
docker compose logs --tail=200 validator-api-app
```

В логах должны присутствовать сообщения:

- `Started RecognitionValidatorApplication`;
- `Image directory watcher registered`;
- `Image directory scan completed`.

HTTP-проверка на Linux:

```bash
curl -I http://127.0.0.1:18080/login
```

Для стандартного локального порта `18080`:

```powershell
Invoke-WebRequest http://127.0.0.1:18080/login -UseBasicParsing
```

## 3. Доступ к серверному UI

Текущий Compose публикует `SERVER_PORT` на всех интерфейсах Docker-host. Если порт
разрешён firewall, UI доступен по адресу:

```text
http://SERVER_IP:18080/login
```

Если прямой доступ закрыт firewall, для тестирования можно использовать SSH-туннель:

```bash
ssh -L 18080:127.0.0.1:18080 dataox@SERVER_IP
```

Пока SSH-соединение открыто, UI доступен локально:

```text
http://127.0.0.1:18080/login
```

Для production рекомендуется закрыть прямой доступ к порту firewall и публиковать
UI через reverse proxy с HTTPS.

## 4. Создание первого администратора

Администраторы не создаются через UI. Сначала пароль преобразуется в BCrypt-хеш,
после чего пользователь добавляется непосредственно в PostgreSQL.

### Windows PowerShell

Изменить значения пароля и имени перед выполнением:

```powershell
$adminUsername = "admin"
$adminPassword = "replace-with-a-strong-password"

$adminHash = docker compose run --rm --no-deps --entrypoint java validator-api-app `
  "-Dloader.main=com.introlabsystems.recognitionvalidator.cli.PasswordHashCli" `
  -cp /app/app.jar `
  org.springframework.boot.loader.launch.PropertiesLauncher `
  $adminPassword | Select-Object -Last 1

$adminId = [guid]::NewGuid()
$adminSql = "INSERT INTO app_user (id, username, password_hash, enabled, role, created_at) VALUES ('$adminId', '$adminUsername', '$adminHash', TRUE, 'ADMIN', now());"
$dbUser = (docker compose exec -T validator-api-db printenv POSTGRES_USER).Trim()
$dbName = (docker compose exec -T validator-api-db printenv POSTGRES_DB).Trim()

docker compose exec -T validator-api-db `
  psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -c $adminSql

Remove-Variable adminPassword, adminHash
```

### Linux Bash

Изменить значения перед выполнением:

```bash
ADMIN_USERNAME='admin'
ADMIN_PASSWORD='replace-with-a-strong-password'

ADMIN_HASH="$(
  docker compose run --rm --no-deps --entrypoint java validator-api-app \
    '-Dloader.main=com.introlabsystems.recognitionvalidator.cli.PasswordHashCli' \
    -cp /app/app.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    "$ADMIN_PASSWORD" | tail -n 1
)"

ADMIN_ID="$(cat /proc/sys/kernel/random/uuid)"
DB_USER="$(docker compose exec -T validator-api-db printenv POSTGRES_USER)"
DB_NAME="$(docker compose exec -T validator-api-db printenv POSTGRES_DB)"

docker compose exec -T validator-api-db \
  psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 \
  -c "INSERT INTO app_user (id, username, password_hash, enabled, role, created_at) VALUES ('$ADMIN_ID', '$ADMIN_USERNAME', '$ADMIN_HASH', TRUE, 'ADMIN', now());"

unset ADMIN_PASSWORD ADMIN_HASH
```

Имя роли и БД читаются из окружения контейнера, поэтому команды работают и при
переопределении `DB_USERNAME` и `DB_NAME` в `.env`.

Проверить администратора:

```bash
docker compose exec -T validator-api-db \
  psql -U "$DB_USER" -d "$DB_NAME" \
  -c "SELECT username, role, enabled, created_at FROM app_user ORDER BY created_at;"
```

После входа пользователь с ролью `ADMIN` автоматически попадает на `/admin`.
Ошибка `duplicate key value violates unique constraint` означает, что такое имя
уже существует. В этом случае не создавайте вторую запись с тем же именем —
используйте процедуру смены пароля ниже.

## 5. Смена пароля администратора

Сначала получить новый BCrypt-хеш тем же `PasswordHashCli`, затем обновить запись.
Пример для PowerShell:

```powershell
$newPassword = "replace-with-a-new-strong-password"
$newHash = docker compose run --rm --no-deps --entrypoint java validator-api-app `
  "-Dloader.main=com.introlabsystems.recognitionvalidator.cli.PasswordHashCli" `
  -cp /app/app.jar `
  org.springframework.boot.loader.launch.PropertiesLauncher `
  $newPassword | Select-Object -Last 1

$sql = "UPDATE app_user SET password_hash = '$newHash' WHERE username = 'admin' AND role = 'ADMIN';"
$dbUser = (docker compose exec -T validator-api-db printenv POSTGRES_USER).Trim()
$dbName = (docker compose exec -T validator-api-db printenv POSTGRES_DB).Trim()
docker compose exec -T validator-api-db `
  psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -c $sql

Remove-Variable newPassword, newHash
```

Проверить, что обновлена одна строка. При результате `UPDATE 0` нужно проверить имя
администратора и его роль.

## 6. Управление операторами

На странице `/admin` администратор может:

- создать оператора;
- изменить пароль оператора;
- деактивировать оператора без удаления его статистики;
- восстановить оператора;
- посмотреть его статистику по дням;
- переключать страницы списка по 10 операторов;
- выгрузить доступные отклонённые скриншоты в ZIP.

Операторов не следует удалять SQL-командой: их ID связан с решениями и дневной
статистикой. Используйте деактивацию в UI.

## 7. Просмотр результатов через UI

### Оператор

На странице `/review` оператор видит скриншот, распознанные из имени PNG значения
и принимает решение `Matches` или `Does not match`. Фильтры применяются автоматически:

- `Created from` — включительно, `Created to` — исключительно; введённое время
  трактуется как UTC без преобразования часового пояса браузера;
- `Session`, `Game` и `Notification` фильтруют соответствующие поля;
- `Has user hand` проверяет наличие хотя бы активной или другой руки пользователя.

При `COUNT_REMAINING_SCREENSHOTS=true` под фильтрами показываются количество,
минимальная и максимальная UTC-даты фактической выборки. Фильтры и масштаб
сохраняются в `sessionStorage` вкладки. При переходе к следующему скриншоту масштаб
сохраняется, а позиция изображения возвращается к центру. Панель фильтров и FAQ
можно свернуть, также доступно переключение светлой и тёмной темы.

Страница `/statistics` показывает:

- выполнено сегодня по UTC;
- выполнено за последние 7 дней;
- выполнено за всё время;
- график работы по дням;
- количество `Matches` и `Does not match`.

### Администратор

Страница `/admin` показывает по каждому оператору:

- активность аккаунта;
- количество решений сегодня, за 7 дней и за всё время;
- дневной график за последние 7 дней;
- распределение `Matches`/`Does not match`.

Решение оператора окончательное и через UI не изменяется.

### Экспорт отклонённых скриншотов

Блок `Rejected screenshots export` на странице `/admin` выгружает только доступные
PNG с решением `REJECTED`. Поля дат фильтруют по `processed_at` — UTC-времени
завершения распознавания, извлечённому из имени файла. Нижняя граница включна,
верхняя — исключительна; пустые поля охватывают все доступные данные.

После успешной записи файла в ZIP задача помечается как скачанная и в следующий
стандартный экспорт не попадает. Флажок `Include previously downloaded` включает
такие файлы повторно. Если физический PNG уже удалён, он пропускается.

## 8. Просмотр результатов в PostgreSQL

Все команды выполняются только на чтение.

Один раз получить фактические имя роли и имя БД из контейнера:

```bash
DB_USER="$(docker compose exec -T validator-api-db printenv POSTGRES_USER)"
DB_NAME="$(docker compose exec -T validator-api-db printenv POSTGRES_DB)"
```

### Количество проиндексированных файлов

```bash
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "
SELECT
  count(*) AS total,
  count(*) FILTER (WHERE file_available) AS available
FROM image_asset;
"
```

### Статусы парсинга

```bash
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "
SELECT parse_status, count(*)
FROM image_asset
GROUP BY parse_status
ORDER BY parse_status;
"
```

### Состояние очереди

```bash
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "
SELECT status, count(*)
FROM review_task
GROUP BY status
ORDER BY status;
"
```

### Последние 100 решений

```bash
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "
SELECT
  rt.reviewed_at,
  u.username,
  rt.decision,
  ia.game_code,
  ia.session_id,
  ia.file_name
FROM review_task rt
JOIN image_asset ia ON ia.id = rt.image_id
LEFT JOIN app_user u ON u.id = rt.assigned_to
WHERE rt.status = 'COMPLETED'
ORDER BY rt.reviewed_at DESC
LIMIT 100;
"
```

В БД решения по-прежнему называются `ACCEPTED` и `REJECTED`:

- `ACCEPTED` соответствует `Matches`;
- `REJECTED` соответствует `Does not match`.

### Итоги по операторам

```bash
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "
SELECT
  u.username,
  coalesce(sum(ds.total_checked), 0) AS total_checked,
  coalesce(sum(ds.matched_count), 0) AS matches,
  coalesce(sum(ds.not_matched_count), 0) AS does_not_match
FROM app_user u
LEFT JOIN operator_daily_statistics ds ON ds.operator_id = u.id
WHERE u.role = 'OPERATOR'
GROUP BY u.id, u.username
ORDER BY total_checked DESC, u.username;
"
```

### Работа по дням за последние 7 дней

```bash
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "
SELECT
  ds.statistics_date,
  u.username,
  ds.total_checked,
  ds.matched_count,
  ds.not_matched_count
FROM operator_daily_statistics ds
JOIN app_user u ON u.id = ds.operator_id
WHERE ds.statistics_date >= (current_date - 6)
ORDER BY ds.statistics_date, u.username;
"
```

### Состояние B2 upload и двух окон хранения

Все запросы ниже только читают PostgreSQL. `upload_backlog` — локальные строки
без успешной загрузки; `due_now` — строки, которые scheduler может взять сейчас;
`retrying` — строки с хотя бы одной неудачной попыткой.

```bash
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "
SELECT
  count(*) FILTER (WHERE cloud_uploaded_at IS NOT NULL) AS uploaded_count,
  count(*) FILTER (
    WHERE cloud_uploaded_at IS NULL
      AND (file_available OR (cloud_object_key IS NOT NULL AND cloud_upload_attempt_count > 0))
  ) AS upload_backlog,
  count(*) FILTER (
    WHERE cloud_uploaded_at IS NULL
      AND (file_available OR (cloud_object_key IS NOT NULL AND cloud_upload_attempt_count > 0))
      AND (cloud_upload_next_attempt_at IS NULL OR cloud_upload_next_attempt_at <= now())
  ) AS due_now,
  count(*) FILTER (
    WHERE file_available AND cloud_uploaded_at IS NULL AND cloud_upload_attempt_count > 0
  ) AS retrying
FROM image_asset;
"
```

Самый старый due-кандидат:

```bash
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "
SELECT id, file_name, relative_path, file_created_at,
       cloud_upload_next_attempt_at, cloud_upload_attempt_count
FROM image_asset
WHERE cloud_uploaded_at IS NULL
  AND (file_available OR (cloud_object_key IS NOT NULL AND cloud_upload_attempt_count > 0))
  AND (cloud_upload_next_attempt_at IS NULL OR cloud_upload_next_attempt_at <= now())
ORDER BY file_created_at, id
LIMIT 1;
"
```

Локальная/cloud-доступность (очередь и rejected ZIP используют `local OR cloud`):

```bash
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "
SELECT
  count(*) FILTER (WHERE file_available AND cloud_uploaded_at IS NULL) AS local_only,
  count(*) FILTER (
    WHERE NOT file_available
      AND cloud_object_key IS NOT NULL
      AND cloud_uploaded_at > now() - interval '21 days'
  ) AS cloud_only,
  count(*) FILTER (
    WHERE file_available
      AND cloud_object_key IS NOT NULL
      AND cloud_uploaded_at > now() - interval '21 days'
  ) AS both_stores,
  count(*) FILTER (
    WHERE NOT file_available
      AND NOT COALESCE((
        cloud_object_key IS NOT NULL
        AND cloud_uploaded_at > now() - interval '21 days'
      ), FALSE)
  ) AS unavailable,
  count(*) FILTER (
    WHERE file_available
       OR (
         cloud_object_key IS NOT NULL
         AND cloud_uploaded_at > now() - interval '21 days'
       )
  ) AS logically_available
FROM image_asset;
"
```

Проверка 21-дневной cloud-очистки:

```bash
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "
SELECT
  count(*) FILTER (WHERE cloud_uploaded_at < now() - interval '21 days') AS past_cutoff,
  count(*) FILTER (WHERE cloud_uploaded_at >= now() - interval '21 days') AS in_window,
  min(cloud_uploaded_at) AS oldest_cloud_upload
FROM image_asset
WHERE cloud_uploaded_at IS NOT NULL;
"
```

После ежедневного cleanup `past_cutoff` должен быть `0`. Сравнение строгое (`<`):
точная граница 21 дня удаляется только после её прохождения. Для cloud-backed
строк cleanup использует `B2_METADATA_RETENTION=21d`, для локальных —
`VALIDATOR_RETENTION=4d` по календарным UTC-дням. Cleanup удаляет метаданные и
связанные задания БД, но не PNG и не B2 objects.

## 9. Проверка watcher и новых файлов

Проверить, что контейнер видит host-папку:

```bash
docker compose exec validator-api-app sh -c "ls -1 /data/images | head"
```

Логи watcher на Linux:

```bash
docker compose logs --since=1h validator-api-app \
  | grep -E "watcher registered|scan completed|reconciliation|overflow|Cannot process image events"
```

Логи watcher в PowerShell:

```powershell
docker compose logs --since=1h validator-api-app |
  Select-String "watcher registered|scan completed|reconciliation|overflow|Cannot process image events"
```

После копирования нового допустимого PNG в `VALIDATOR_IMAGE_ROOT_HOST` он должен
появиться в `image_asset` через несколько секунд. Файлы с неподдерживаемым префиксом,
например `solution_*`, игнорируются.

Если watcher или его внутренний буфер получает overflow, приложение запускает
полную reconciliation папки. Это означает задержку индексации, а не потерю уже
сохранённых решений.

## 10. Размер БД и диска

Размер текущей PostgreSQL-базы:

```bash
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "
SELECT pg_size_pretty(pg_database_size(current_database())) AS database_size;
"
```

Размер основных таблиц и индексов:

```bash
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "
SELECT
  relname,
  pg_size_pretty(pg_total_relation_size(relid)) AS total_size
FROM pg_catalog.pg_statio_user_tables
ORDER BY pg_total_relation_size(relid) DESC;
"
```

Проверка диска на Linux:

```bash
df -h /opt/dataox/validator-api-build
sudo du -sh /opt/dataox/validator-api-build/postgres-data
```

Жёсткого лимита размера PostgreSQL в приложении нет. Метаданные изображений старше
`VALIDATOR_RETENTION` удаляются пакетно, по умолчанию после четырёх календарных
UTC-дней. Cleanup запускается ежедневно в 09:00 UTC и удаляет метаданные и связанные
задания независимо от статуса. Физические PNG он не трогает. Дневная статистика
операторов сохраняется без ограничения срока.

## 11. Остановка, повторный запуск и обновление

Остановить контейнеры, сохранив их:

```bash
docker compose stop
```

Запустить остановленные контейнеры:

```bash
docker compose start
```

Удалить контейнеры и Compose-сеть, сохранив bind-mounted данные:

```bash
docker compose down
```

Перед обновлением убедиться, что в отслеживаемых файлах нет локальных изменений,
получить текущий `main`, затем пересобрать только приложение:

```bash
git status --short
git pull --ff-only origin main
docker compose up -d --build validator-api-app
docker compose ps
```

Файл `.env` и bind-mounted каталог PostgreSQL не перезаписываются командой
`git pull`. Если `git status --short` показывает изменения `compose.yaml` или других
отслеживаемых файлов, сначала сохраните их отдельно или согласуйте перенос в Git;
`--ff-only` не создаёт автоматический merge-коммит.

Не удаляйте вручную `POSTGRES_DATA_ROOT_HOST` при работающем PostgreSQL. Не
используйте глобальную остановку всех Docker-контейнеров на общем сервере.

### Обязательная миграция очереди перед первым деплоем оптимизации

Перед первым запуском версии с `review_task.file_created_at` остановите только
приложение Validator и выполните миграцию. PostgreSQL и остальные сервисы сервера
останавливать не нужно:

```bash
docker compose stop validator-api-app

docker compose exec -T validator-api-db \
  sh -lc 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < scripts/review-queue-performance.sql
```

Скрипт повторяемый: он дополняет старые задания датой создания пакетами по 10 000,
проверяет отсутствие `NULL`, создаёт два индекса без блокировки чтения таблиц и
обновляет статистику планировщика. На production с большим числом строк операция
может занять несколько минут. Не запускайте новое приложение, пока команда не
завершилась строкой `Review queue performance migration completed`.

Проверка результата:

```bash
docker compose exec -T validator-api-db \
  sh -lc 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT count(*) FILTER (WHERE file_created_at IS NULL) AS missing_queue_dates
FROM review_task;

SELECT indexrelid::regclass AS index_name, indisvalid, indisready
FROM pg_index
WHERE indexrelid::regclass::text IN (
  'ix_review_pending_order',
  'ix_image_cloud_pending_order'
)
ORDER BY index_name;

SELECT stxname
FROM pg_statistic_ext
WHERE stxname = 'st_image_user_hand_presence';
SQL
```

Ожидается `missing_queue_dates = 0`, оба индекса имеют `indisvalid = t` и
`indisready = t`, статистика присутствует. После этого пересоберите только
приложение:

```bash
docker compose up -d --build --no-deps validator-api-app
```

Если миграция завершилась ошибкой, не запускайте новую версию. Верните тот же
остановленный контейнер старого приложения командой
`docker compose start validator-api-app`: он игнорирует добавленную колонку, а
миграцию можно безопасно повторить после устранения причины.

## 12. Типовые проблемы

### UI не открывается

```bash
docker compose ps
docker compose logs --tail=200 validator-api-app
curl -I http://127.0.0.1:18080/login
```

Проверьте `SERVER_PORT` и отсутствие другого процесса на этом порту.

### PostgreSQL не становится healthy

```bash
docker compose logs --tail=200 validator-api-db
docker compose exec validator-api-db sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Проверьте права на `POSTGRES_DATA_ROOT_HOST`, свободное место и значения
`DB_USERNAME`, `DB_PASSWORD`, `DB_NAME`.

### Скриншоты не появляются

1. Проверить `VALIDATOR_IMAGE_ROOT_HOST` в `.env`.
2. Проверить файлы внутри `/data/images` контейнера.
3. Проверить watcher-логи.
4. Проверить `image_asset` и `parse_status` SQL-командами выше.
5. Убедиться, что имя начинается с поддерживаемого кода игры.

### После фильтра очередь пустая

Сбросить фильтры в UI. Если `Matching screenshots` равен `0`, подходящих
непроверенных файлов действительно нет. Значение
`COUNT_REMAINING_SCREENSHOTS=false` скрывает счётчик и отключает соответствующие
`COUNT(*)` запросы.

### Ошибка авторизации или HTML вместо JSON

Обновить страницу и войти повторно. Сессия действует 24 часа, но может исчезнуть
после очистки cookies или изменения пользователя администратором. Затем проверить
логи приложения на `401`, `403` и исключения.

## 13. Быстрая ежедневная проверка

```bash
docker compose ps
docker compose logs --since=24h validator-api-app | grep -E "ERROR|Exception|overflow|reconciliation"
DB_USER="$(docker compose exec -T validator-api-db printenv POSTGRES_USER)"
DB_NAME="$(docker compose exec -T validator-api-db printenv POSTGRES_DB)"
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "SELECT status, count(*) FROM review_task GROUP BY status ORDER BY status;"
docker compose exec -T validator-api-db psql -U "$DB_USER" -d "$DB_NAME" -c "SELECT pg_size_pretty(pg_database_size(current_database()));"
```

На общем сервере всегда выполняйте `docker compose` из каталога именно этого
проекта. Это защищает остальные контейнеры от случайной остановки.
