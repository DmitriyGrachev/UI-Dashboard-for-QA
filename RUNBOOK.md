# Recognition Validator — Docker Runbook

Практическая инструкция для запуска и обслуживания Recognition Validator через
Docker Compose. Команды выполняются из каталога проекта, где находятся
`compose.yaml` и `.env`.

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
SERVER_PORT=8080
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
VALIDATOR_IMAGE_ROOT_HOST=/opt/dataox/dmytro-test/images-source
POSTGRES_DATA_ROOT_HOST=/opt/dataox/dmytro-test/recognition-validator-postgres-data
DB_NAME=recognition_validator
DB_USERNAME=validator
DB_PASSWORD=replace-with-a-strong-password
COUNT_REMAINING_SCREENSHOTS=true
```

`VALIDATOR_IMAGE_ROOT_HOST` монтируется в контейнер read-only. Содержимое PNG не
сохраняется в PostgreSQL. `POSTGRES_DATA_ROOT_HOST` является постоянным хранилищем
БД и не должен находиться внутри контейнера.

## 2. Запуск и проверка

Собрать и запустить приложение с PostgreSQL:

```bash
docker compose up -d --build app
```

Проверить контейнеры:

```bash
docker compose ps
```

Ожидаемое состояние:

- `postgres` — `healthy`;
- `app` — `Up`;
- порт приложения опубликован только на `127.0.0.1`.

Посмотреть запуск приложения:

```bash
docker compose logs --tail=200 app
```

В логах должны присутствовать сообщения:

- `Started RecognitionValidatorApplication`;
- `Image directory watcher registered`;
- `Image directory scan completed`.

HTTP-проверка на Linux:

```bash
curl -I http://127.0.0.1:18080/login
```

Для локального порта `8080`:

```powershell
Invoke-WebRequest http://127.0.0.1:8080/login -UseBasicParsing
```

## 3. Доступ к серверному UI

Приложение слушает только loopback-интерфейс сервера. Для тестового доступа можно
использовать SSH-туннель:

```bash
ssh -L 18080:127.0.0.1:18080 dataox@SERVER_IP
```

Пока SSH-соединение открыто, UI доступен локально:

```text
http://127.0.0.1:18080/login
```

Для production внешний доступ должен настраиваться через reverse proxy и HTTPS.

## 4. Создание первого администратора

Администраторы не создаются через UI. Сначала пароль преобразуется в BCrypt-хеш,
после чего пользователь добавляется непосредственно в PostgreSQL.

### Windows PowerShell

Изменить значения пароля и имени перед выполнением:

```powershell
$adminUsername = "admin"
$adminPassword = "replace-with-a-strong-password"

$adminHash = docker compose run --rm --no-deps --entrypoint java app `
  "-Dloader.main=com.introlabsystems.recognitionvalidator.auth.PasswordHashCli" `
  -cp /app/app.jar `
  org.springframework.boot.loader.launch.PropertiesLauncher `
  $adminPassword | Select-Object -Last 1

$adminId = [guid]::NewGuid()
$adminSql = "INSERT INTO app_user (id, username, password_hash, enabled, role, created_at) VALUES ('$adminId', '$adminUsername', '$adminHash', TRUE, 'ADMIN', now());"

docker compose exec -T postgres `
  psql -U validator -d recognition_validator -v ON_ERROR_STOP=1 -c $adminSql

Remove-Variable adminPassword, adminHash
```

### Linux Bash

Изменить значения перед выполнением:

```bash
ADMIN_USERNAME='admin'
ADMIN_PASSWORD='replace-with-a-strong-password'

ADMIN_HASH="$(
  docker compose run --rm --no-deps --entrypoint java app \
    '-Dloader.main=com.introlabsystems.recognitionvalidator.auth.PasswordHashCli' \
    -cp /app/app.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    "$ADMIN_PASSWORD" | tail -n 1
)"

ADMIN_ID="$(cat /proc/sys/kernel/random/uuid)"

docker compose exec -T postgres \
  psql -U validator -d recognition_validator -v ON_ERROR_STOP=1 \
  -c "INSERT INTO app_user (id, username, password_hash, enabled, role, created_at) VALUES ('$ADMIN_ID', '$ADMIN_USERNAME', '$ADMIN_HASH', TRUE, 'ADMIN', now());"

unset ADMIN_PASSWORD ADMIN_HASH
```

Если в `.env` изменены `DB_USERNAME` или `DB_NAME`, соответствующие значения нужно
заменить и в командах `psql`.

Проверить администратора:

```bash
docker compose exec -T postgres \
  psql -U validator -d recognition_validator \
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
$newHash = docker compose run --rm --no-deps --entrypoint java app `
  "-Dloader.main=com.introlabsystems.recognitionvalidator.auth.PasswordHashCli" `
  -cp /app/app.jar `
  org.springframework.boot.loader.launch.PropertiesLauncher `
  $newPassword | Select-Object -Last 1

$sql = "UPDATE app_user SET password_hash = '$newHash' WHERE username = 'admin' AND role = 'ADMIN';"
docker compose exec -T postgres `
  psql -U validator -d recognition_validator -v ON_ERROR_STOP=1 -c $sql

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
- переключать страницы списка по 10 операторов.

Операторов не следует удалять SQL-командой: их ID связан с решениями и дневной
статистикой. Используйте деактивацию в UI.

## 7. Просмотр результатов через UI

### Оператор

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

## 8. Просмотр результатов в PostgreSQL

Все команды выполняются только на чтение.

### Количество проиндексированных файлов

```bash
docker compose exec -T postgres psql -U validator -d recognition_validator -c "
SELECT
  count(*) AS total,
  count(*) FILTER (WHERE file_available) AS available
FROM image_asset;
"
```

### Статусы парсинга

```bash
docker compose exec -T postgres psql -U validator -d recognition_validator -c "
SELECT parse_status, count(*)
FROM image_asset
GROUP BY parse_status
ORDER BY parse_status;
"
```

### Состояние очереди

```bash
docker compose exec -T postgres psql -U validator -d recognition_validator -c "
SELECT status, count(*)
FROM review_task
GROUP BY status
ORDER BY status;
"
```

### Последние 100 решений

```bash
docker compose exec -T postgres psql -U validator -d recognition_validator -c "
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
docker compose exec -T postgres psql -U validator -d recognition_validator -c "
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
docker compose exec -T postgres psql -U validator -d recognition_validator -c "
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

## 9. Проверка watcher и новых файлов

Проверить, что контейнер видит host-папку:

```bash
docker compose exec app sh -c "ls -1 /data/images | head"
```

Логи watcher на Linux:

```bash
docker compose logs --since=1h app \
  | grep -E "watcher registered|scan completed|reconciliation|overflow|Cannot process image events"
```

Логи watcher в PowerShell:

```powershell
docker compose logs --since=1h app |
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
docker compose exec -T postgres psql -U validator -d recognition_validator -c "
SELECT pg_size_pretty(pg_database_size(current_database())) AS database_size;
"
```

Размер основных таблиц и индексов:

```bash
docker compose exec -T postgres psql -U validator -d recognition_validator -c "
SELECT
  relname,
  pg_size_pretty(pg_total_relation_size(relid)) AS total_size
FROM pg_catalog.pg_statio_user_tables
ORDER BY pg_total_relation_size(relid) DESC;
"
```

Проверка диска на Linux:

```bash
df -h /opt/dataox/dmytro-test
du -sh /opt/dataox/dmytro-test/recognition-validator-postgres-data
```

Жёсткого лимита размера PostgreSQL в приложении нет. Метаданные изображений старше
`VALIDATOR_RETENTION` удаляются пакетно, по умолчанию через 7 дней. Дневная
статистика операторов сохраняется без ограничения срока.

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

Собрать текущую версию и снова запустить:

```bash
docker compose up -d --build app
```

Не удаляйте вручную `POSTGRES_DATA_ROOT_HOST` при работающем PostgreSQL. Не
используйте глобальную остановку всех Docker-контейнеров на общем сервере.

## 12. Типовые проблемы

### UI не открывается

```bash
docker compose ps
docker compose logs --tail=200 app
curl -I http://127.0.0.1:18080/login
```

Проверьте `SERVER_PORT` и отсутствие другого процесса на этом порту.

### PostgreSQL не становится healthy

```bash
docker compose logs --tail=200 postgres
docker compose exec postgres pg_isready -U validator -d recognition_validator
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
docker compose logs --since=24h app | grep -E "ERROR|Exception|overflow|reconciliation"
docker compose exec -T postgres psql -U validator -d recognition_validator -c "SELECT status, count(*) FROM review_task GROUP BY status ORDER BY status;"
docker compose exec -T postgres psql -U validator -d recognition_validator -c "SELECT pg_size_pretty(pg_database_size(current_database()));"
```

На общем сервере всегда выполняйте `docker compose` из каталога именно этого
проекта. Это защищает остальные контейнеры от случайной остановки.
