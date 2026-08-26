# Первый production-деплой B2

Подготовлено 26.08.2026 для запуска утром 27.08.2026. Выполнять на сервере только после согласования окна с лидом и операторами.

## До начала

- Изменения должны быть проверены, закоммичены и опубликованы в согласованной release-ветке. Команды ниже предполагают, что **B2 уже включён в опубликованный `main`**. Пока это не сделано, обычный `pull main` не доставит новую функциональность.
- На момент подготовки B2-ветка — `dev-dmytro-backblaze-b2-storage`; в `main` отдельно есть `aadc5e7` (ссылка Slack на архив). Его нужно сохранить при согласованной интеграции. Этот документ не выполняет merge/push.
- Проверка перед merge: полный Maven-прогон (221 тест), 26 JavaScript-тестов, 21 тест benchmark-скрипта, сборка JAR и Compose прошли. На изолированной PostgreSQL 17.6 проверен переход со схемы прежнего `main`: добавлены четыре B2-колонки и индекс, тестовые пользователь, изображение, задание и дневная статистика сохранены. Это функциональная репетиция, не измерение времени миграции на production-объёме. Docker и реальные B2/Slack при этом не запускались.
- Использовать production-bucket **клиента**, а не личный бесплатный bucket или benchmark-префикс. Проверить доступ ключа к нужному bucket/prefix, оплаченный объём и Caps & Alerts.
- Bucket private; lifecycle для `validator/`: скрывать через 21 день после загрузки, удалять скрытые версии ещё через 1 день. Это не физическое удаление ровно через 21 день. Правило настраивается в B2, не через `.env` приложения.
- Деплоить в `/opt/dataox/validator-api-build/app`, **не** в `/opt/dataox/validator-b2-benchmark`. Не запускать второй uploader с той же БД.
- Сохраняем существующую БД, пользователей, статистику, порты, Slack и bind-папки. Не выполнять `down -v`, очистку БД или production PNG.

Профиль: sync S3Client, **8 работников**, один общий batch **1000**, пауза **10s** после завершения batch, память app **5g**. Восемь работников обрабатывают по одному файлу, а не по 1000 файлов каждый.

`APP_CPU_LIMIT=0.0` сохраняет текущий режим без CPU-квоты. Если лид подтвердил квоту для **всего** Validator, поставить `2.0`: её разделят B2, UI, индексация и JVM. Это не привязка к ядрам и не лимит для PostgreSQL или Docker build. [Документация Compose](https://docs.docker.com/reference/compose-file/services/#cpus).

## 1. Сохранить текущую версию и конфигурацию

Все команды ниже — Bash на сервере, в одной SSH-сессии. При любой ошибке остановиться, не продолжать следующим разделом.

```bash
cd /opt/dataox/validator-api-build/app
git branch --show-current
git status --short
docker compose ps
```

Ожидаем `main` и работающие app/DB. Если изменены отслеживаемые файлы — сначала разобраться с diff; не применять `reset --hard`. Неотслеживаемые CSV со статистикой не мешают, их оставляем.

Сохранить приватную копию конфигурации и старый образ до сборки нового:

```bash
umask 077 &&
mkdir -p /opt/dataox/validator-api-build/backups &&
DEPLOY_BACKUP_DIR="$(mktemp -d /opt/dataox/validator-api-build/backups/b2-deploy-XXXXXXXX)" &&
cp -- .env compose.yaml Dockerfile "$DEPLOY_BACKUP_DIR/" &&
git rev-parse HEAD > "$DEPLOY_BACKUP_DIR/commit.txt" &&
APP_CONTAINER="$(docker compose ps -q validator-api-app)" &&
test -n "$APP_CONTAINER" &&
OLD_IMAGE="$(docker inspect --format '{{.Image}}' "$APP_CONTAINER")" &&
OLD_IMAGE_TAG="recognition-validator:before-b2-$(date -u +%Y%m%dT%H%M%SZ)" &&
docker image tag "$OLD_IMAGE" "$OLD_IMAGE_TAG" &&
printf '%s\n' "$OLD_IMAGE_TAG" > "$DEPLOY_BACKUP_DIR/image.txt" &&
printf 'Backup directory: %s\n' "$DEPLOY_BACKUP_DIR"
```

Записать путь backup отдельно. Не присылать содержимое `.env` или полный `docker inspect`: там секреты.

## 2. Получить опубликованный release, настроить и собрать

Только после выполнения условия публикации в начале документа:

```bash
git pull --ff-only origin main
git log -1 --oneline
```

Сверить commit с согласованным release. При конфликте остановиться; production-конфигурацию не перетирать.

Открыть существующий серверный `.env` и **добавить/заменить только ключи** из [`.env.b2-production.example`](.env.b2-production.example). Не копировать этот шаблон поверх всего `.env`.

- Заполнить `B2_ENDPOINT` (с `https://`), bucket и два ключа клиента. Регион определяется по endpoint, отдельного `B2_REGION` нет.
- Для первого запуска оставить **`B2_ENABLED=false`**; явно указать `B2_UPLOAD_CONCURRENCY=8`.
- `APP_CPU_LIMIT=0.0`, либо `2.0`, если общая CPU-квота подтверждена.
- `INTEGRATION_IMAGE_API_KEY` оставить пустым до готовности HTTPS. Потом задать отдельный случайный секрет для Игоря, не ключ B2. Пустой ключ закрывает integration API, но не мешает UI/B2.
- Не изменять `DB_*`, `POSTGRES_DATA_ROOT_HOST`, `VALIDATOR_IMAGE_ROOT_HOST`, существующие порты и Slack-настройки.

Production-пути остаются `/opt/dataox/validator-api-build/postgres-data` для БД и `/data/recognition-api/completed_recognition` для PNG. Изображения монтируются read-only. Не использовать `.env` от benchmark.

```bash
docker compose config --quiet &&
docker compose build validator-api-app
```

Во время сборки старое приложение ещё работает. CPU-квота сервиса **не ограничивает сборку**: проводить её в согласованное окно и наблюдать за распознаванием. При ошибке сборки старое приложение не останавливать.

## 3. Остановить только app, сохранить БД, запустить с B2 off

Новая версия обновляет схему через Hibernate `ddl-auto=update`, поэтому backup нужен **до первого запуска**. Создание индекса на большом `image_asset` может занять время; мгновенный старт не гарантирован.

```bash
test -n "$DEPLOY_BACKUP_DIR" && test -d "$DEPLOY_BACKUP_DIR" &&
docker compose stop -t 120 validator-api-app &&
docker compose exec -T validator-api-db sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' \
  > "$DEPLOY_BACKUP_DIR/database.dump" &&
test -s "$DEPLOY_BACKUP_DIR/database.dump" &&
docker compose exec -T validator-api-db pg_restore --list \
  < "$DEPLOY_BACKUP_DIR/database.dump" > /dev/null &&
docker compose up -d --no-deps --no-build --force-recreate validator-api-app
```

Цепочка останавливается при ошибке. Проверка `pg_restore --list` подтверждает читаемость архива, но не заменяет отдельную проверку восстановления. Если backup не получился — не запускать новую версию до выяснения причины.

БД и recognition API продолжают работать. Операторам может понадобиться повторный вход: сессии находятся в памяти app.

```bash
docker compose ps
docker compose logs --since=10m --tail=150 validator-api-app
curl -sS -o /dev/null -w 'Login HTTP %{http_code}\n' \
  http://127.0.0.1:18080/login

APP_CONTAINER="$(docker compose ps -q validator-api-app)"
docker inspect --format 'Memory={{.HostConfig.Memory}} NanoCpus={{.HostConfig.NanoCpus}}' \
  "$APP_CONTAINER"
docker inspect --format '{{range .Mounts}}{{.Source}} -> {{.Destination}} RW={{.RW}}{{println}}{{end}}' \
  "$APP_CONTAINER"
docker compose exec -T validator-api-app sh -c \
  'printf "B2=%s workers=%s batch=%s delay=%s\n" "$B2_ENABLED" "$B2_UPLOAD_CONCURRENCY" "$B2_UPLOAD_BATCH_SIZE" "$B2_UPLOAD_DELAY"'
```

Ожидаем: login HTTP 200; B2=false, workers=8, batch=1000, delay=10s; память `5368709120`; `NanoCpus=0` либо `2000000000` для согласованной квоты 2 CPU. Mount `/data/images` должен указывать на production-каталог, `RW=false`.

Дождаться `Started RecognitionValidatorApplication` и `Image directory scan completed`, проверить вход существующего админа и операторские аккаунты; под оператором — Review и статистику. Не создавать заново админа и не сбрасывать задания. Стартовый scan перечитывает метаданные; пока B2 выключен, облачной загрузки нет.

## 4. Включить B2

После успешной проверки UI/схемы и подтверждения bucket/лимитов поменять в том же `.env` только `B2_ENABLED=true`:

```bash
docker compose config --quiet &&
docker compose up -d --no-deps --no-build --force-recreate validator-api-app
docker compose logs --since=5m --tail=150 validator-api-app
```

Это второй короткий перезапуск app, БД не пересоздаётся. `docker compose restart` не подходит для изменения env — нужно пересоздание контейнера. [Документация Compose up](https://docs.docker.com/reference/cli/docker/compose/up/).

После запуска uploader начнёт выбирать незагруженные записи из существующей БД. Сохранение той же БД, bucket и prefix позволяет продолжать прогресс после передеплоя; успешные загрузки не выбираются заново. Неопределённые попытки проверяются через HEAD. Это не абсолютная гарантия отсутствия новых B2-версий при любом сетевом сбое.

## 5. Наблюдение в течение дня

```bash
docker compose logs --since=10m validator-api-app \
  | grep -E 'B2 upload batch completed|Retention cleanup completed|ERROR|Exception'
docker stats --no-stream
```

В batch-логе: `candidates`, `uploaded`, `missing`, `failed`, `durationMs`. Смотреть прогресс нескольких batch, не экстраполировать один быстрый запрос. Docker CPU 100% означает одно логическое CPU; квота 2 CPU — общий бюджет app, не обещание отсутствия кратких пиков в замерах.

В браузере, **после входа админом**, открыть `/admin/api/storage/status` на том же адресе Validator. Это существующая сводка из PostgreSQL, без запросов в B2:

- `uploaded`, `backlog`, `dueNow`, `retrying` — прогресс очереди;
- `oldestPendingAt` — возраст самых старых ожидающих PNG;
- `localOnly`, `cloudOnly`, `bothStores`, `unavailable` — доступность хранилищ.

Обновлять периодически, например раз в 5–10 минут, а не каждую секунду: SQL-агрегация тоже требует ресурсов. Параллельно следить за отзывчивостью Review и состоянием recognition API. Новый SLA-мониторинг пока не добавляем.

Проверить несколько существующих изображений: свежие локальные отдаются PNG; для уже загруженных старше 3 дней — 307 на временную B2-ссылку. Не удалять PNG и не менять production-даты ради проверки fallback. Браузер следует redirect автоматически; ссылку и секреты не публиковать.

PNG удаляет внешний сервис примерно в 06:00, DB cleanup Validator — в 09:00 UTC. Если внешний сервис успел удалить PNG до upload, B2 не восстановит его из метаданных. Поэтому в первый день особенно важны `oldestPendingAt`, `missing` и размер очереди; расчёт скорости не гарантирует сохранение всего старого backlog.

## 6. Быстро отключить B2 при проблеме

Поставить `B2_ENABLED=false`, проверить конфигурацию и пересоздать только app той же новой версии:

```bash
docker compose config --quiet &&
docker compose up -d --no-deps --no-build --force-recreate validator-api-app
```

Загрузки прекратятся; локальные PNG останутся доступны, cloud-only временно нет. Облачные метаданные новая версия сохраняет по 21-дневному правилу даже при B2 off. Если выбран неправильный bucket — сначала остановить только app, исправить настройки и лишь затем запускать.

**Не откатывать автоматически на старый `main`:** его четырёхдневный cleanup не знает о B2 и может удалить облачные метаданные. Старый образ и dump сохранены для согласованного восстановления, не для слепого отката. Не восстанавливать dump поверх новых операторских решений без отдельного плана.

## После запуска

- Проверить в B2, что растёт именно production-prefix клиента и нет предупреждений об исчерпании лимита.
- После готовности HTTPS настроить отдельный image API key и передать Игорю контракт из раздела «Read-only API изображений для другого сервиса» в [RUNBOOK.md](RUNBOOK.md).
- AI scheduler, отправка запросов в сервис Игоря и SLA-мониторинг не входят в этот запуск.
