# Recognition Validator

MVP QA-приложения для ручной проверки результатов OCR. Оператор видит скриншот и
распознанные из имени файла данные, после чего принимает окончательное решение:
`ACCEPTED` или `REJECTED`.

## Что реализовано

- вход оператора через обычную Spring Security session и BCrypt;
- сессия длительностью 24 часа;
- первичная индексация существующих PNG из одной папки;
- отслеживание создания и удаления файлов через `WatchService`;
- разбор игры, session ID, карт, кнопок, времени OCR и duration из имени PNG;
- атомарная очередь: один скриншот одновременно получает только один оператор;
- фильтры очереди по дате создания, session ID, игре и notification;
- показ изображения без сохранения его содержимого в PostgreSQL;
- zoom, pan, reset и fullscreen;
- окончательное решение без возможности повторного изменения;
- личная статистика за выбранный период;
- удаление из БД записей старше 7 дней без удаления физических файлов.

## Как работает индексирование

При старте приложение один раз просматривает файлы непосредственно в
`VALIDATOR_IMAGE_ROOT`. Вложенные папки не обходятся. В БД пакетами записываются
только метаданные допустимых PNG, имя которых начинается с одного из настроенных
кодов игр.

После стартового обхода приложение получает события создания и удаления файлов.
Постоянного перечитывания всех 600 000 изображений нет. При переполнении системной
очереди файловых событий выполняется повторная сверка папки.

Идентификатор изображения — SHA-256 нормализованного относительного пути. Поэтому
повторная индексация не создаёт дубликаты и не сбрасывает уже принятое решение.
Абсолютный путь и байты изображения в БД не сохраняются.

## Требования

- Java 21;
- Docker Desktop с Docker Compose;
- PowerShell;
- доступ приложения к папке со скриншотами.

## Локальный запуск

Запустить основную PostgreSQL:

```powershell
docker compose up -d postgres
docker compose exec postgres pg_isready -U validator -d recognition_validator
```

В первом PowerShell-окне указать тестовую папку и запустить приложение:

```powershell
$env:VALIDATOR_IMAGE_ROOT='C:\Users\dimag\Downloads\test'
$env:DB_URL='jdbc:postgresql://localhost:5432/recognition_validator'
$env:DB_USERNAME='validator'
$env:DB_PASSWORD='validator'
.\mvnw.cmd spring-boot:run
```

Первый запуск создаст таблицы через Hibernate `ddl-auto=update`. Flyway в проекте
не используется. Пока приложение работает, во втором PowerShell-окне создать
оператора:

```powershell
$validatorPasswordHash = .\mvnw.cmd -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.introlabsystems.recognitionvalidator.auth.PasswordHashCli" "-Dexec.args=change-me" | Select-Object -Last 1
$validatorUserId = [guid]::NewGuid()
$validatorSql = "INSERT INTO app_user (id, username, password_hash, enabled, created_at) VALUES ('$validatorUserId', 'operator', '$validatorPasswordHash', TRUE, now());"
docker compose exec -T postgres psql -U validator -d recognition_validator -v ON_ERROR_STOP=1 -c $validatorSql
```

После этого открыть [http://localhost:8080/login](http://localhost:8080/login) и
войти как `operator` с паролем `change-me`.

## Тесты

Тесты используют отдельную временную PostgreSQL на порту `5433`. Testcontainers
не используется.

```powershell
docker compose up -d postgres-test
docker compose exec postgres-test pg_isready -U validator -d recognition_validator_test
.\mvnw.cmd "-Dtest=FilenameParserTest,ImageIndexingTest,ReviewWorkflowTest,WebSecurityTest" clean test
```

Это весь основной набор: четыре тестовых класса для парсинга, индексации,
конкурентной очереди/статистики и web/security.

## Конфигурация

| Переменная | Значение по умолчанию | Назначение |
|---|---|---|
| `VALIDATOR_IMAGE_ROOT` | `./data/images` | Папка PNG на сервере |
| `DB_URL` | `jdbc:postgresql://localhost:5432/recognition_validator` | JDBC URL отдельной БД |
| `DB_USERNAME` | `validator` | Пользователь PostgreSQL |
| `DB_PASSWORD` | `validator` | Пароль PostgreSQL |
| `SERVER_PORT` | `8080` | HTTP-порт |
| `VALIDATOR_BATCH_SIZE` | `1000` | Размер batch при стартовой индексации |
| `VALIDATOR_LEASE_DURATION` | `30m` | Время резервирования задания |
| `VALIDATOR_RETENTION` | `7d` | Срок хранения метаданных в БД |
| `VALIDATOR_CLEANUP_CRON` | `0 15 * * * *` | Расписание очистки в UTC |
| `VALIDATOR_WATCH_ENABLED` | `true` | Включение отслеживания папки |

Список допустимых игр задаётся в `validator.games` файла
`src/main/resources/application.yml`.

## Основные URL

- `/login` — вход;
- `/review` — очередь, фильтры и решение;
- `/statistics` — личная статистика;
- `POST /api/review-tasks/claim` — получить текущее/следующее задание;
- `POST /api/review-tasks/{imageId}/decision` — сохранить решение;
- `GET /api/images/{imageId}/content` — получить изображение;
- `GET /api/statistics/me?from=YYYY-MM-DD&to=YYYY-MM-DD` — статистика оператора.

## Ограничения MVP

- поддерживается один плоский каталог и формат PNG;
- операторы добавляются напрямую в БД, административного UI нет;
- решение одного оператора окончательное;
- роли и аудит изменений пользователей не реализованы;
- приложение не выполняет OCR и не читает карты из изображения — оно только
  парсит уже распознанные значения из имени файла;
- неизвестные игры и неподходящие файлы не попадают в очередь;
- физические изображения удаляет внешний сервис, Validator их никогда не удаляет.
