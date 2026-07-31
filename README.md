# Recognition Validator

MVP QA-застосунку для ручної перевірки результатів OCR. Оператор бачить скриншот і
розпізнані з імені файлу дані, після чого приймає остаточне рішення:
`ACCEPTED` або `REJECTED`.

## Що реалізовано

- вхід оператора через звичайну сесію Spring Security та BCrypt;
- сесія тривалістю 24 години;
- первинна індексація наявних PNG з однієї папки;
- відстеження створення та видалення файлів через `WatchService`;
- розбір гри, session ID, карт, кнопок, часу OCR і duration з імені PNG;
- атомарна черга: один скриншот одночасно отримує лише один оператор;
- фільтри черги за датою створення, session ID, грою та notification;
- показ зображення без збереження його вмісту в PostgreSQL;
- zoom, pan, reset і fullscreen;
- остаточне рішення без можливості повторної зміни;
- особиста статистика за вибраний період;
- видалення з БД записів, старших за 7 днів, без видалення фізичних файлів.

## Як працює індексація

Під час запуску застосунок один раз переглядає файли безпосередньо в
`VALIDATOR_IMAGE_ROOT`. Вкладені папки не обходяться. До БД пакетами записуються
лише метадані допустимих PNG, ім'я яких починається з одного з налаштованих кодів
ігор.

Після стартового обходу застосунок отримує події створення та видалення файлів.
Постійного повторного читання всіх 600 000 зображень немає. У разі переповнення
системної черги файлових подій виконується повторна звірка папки.

Ідентифікатор зображення — SHA-256 нормалізованого відносного шляху. Тому повторна
індексація не створює дублікати та не скидає вже прийняте рішення. Абсолютний шлях
і байти зображення в БД не зберігаються.

## Вимоги

- Java 21;
- Docker Desktop із Docker Compose;
- PowerShell;
- доступ застосунку до папки зі скриншотами.

## Локальний запуск

Запустити основну PostgreSQL:

```powershell
docker compose up -d postgres
docker compose exec postgres pg_isready -U validator -d recognition_validator
```

У першому вікні PowerShell вказати тестову папку та запустити застосунок:

```powershell
$env:VALIDATOR_IMAGE_ROOT='C:\Users\dimag\Downloads\test'
$env:DB_URL='jdbc:postgresql://localhost:5432/recognition_validator'
$env:DB_USERNAME='validator'
$env:DB_PASSWORD='validator'
.\mvnw.cmd spring-boot:run
```

Перший запуск створить таблиці через Hibernate `ddl-auto=update`. Flyway у проєкті
не використовується. Поки застосунок працює, у другому вікні PowerShell створити
оператора:

```powershell
$validatorPasswordHash = .\mvnw.cmd -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.introlabsystems.recognitionvalidator.auth.PasswordHashCli" "-Dexec.args=change-me" | Select-Object -Last 1
$validatorUserId = [guid]::NewGuid()
$validatorSql = "INSERT INTO app_user (id, username, password_hash, enabled, created_at) VALUES ('$validatorUserId', 'operator', '$validatorPasswordHash', TRUE, now());"
docker compose exec -T postgres psql -U validator -d recognition_validator -v ON_ERROR_STOP=1 -c $validatorSql
```

Після цього відкрити [http://localhost:8080/login](http://localhost:8080/login) і
увійти як `operator` з паролем `change-me`.

## Тести

Тести використовують окрему тимчасову PostgreSQL на порту `5433`. Testcontainers
не використовується.

```powershell
docker compose up -d postgres-test
docker compose exec postgres-test pg_isready -U validator -d recognition_validator_test
.\mvnw.cmd "-Dtest=FilenameParserTest,ImageIndexingTest,ReviewWorkflowTest,WebSecurityTest" clean test
```

Це весь основний набір: чотири тестові класи для парсингу, індексації,
конкурентної черги/статистики та web/security.

## Конфігурація

| Змінна | Значення за замовчуванням | Призначення |
|---|---|---|
| `VALIDATOR_IMAGE_ROOT` | `./data/images` | Папка PNG на сервері |
| `DB_URL` | `jdbc:postgresql://localhost:5432/recognition_validator` | JDBC URL окремої БД |
| `DB_USERNAME` | `validator` | Користувач PostgreSQL |
| `DB_PASSWORD` | `validator` | Пароль PostgreSQL |
| `SERVER_PORT` | `8080` | HTTP-порт |
| `VALIDATOR_BATCH_SIZE` | `1000` | Розмір batch під час стартової індексації |
| `VALIDATOR_LEASE_DURATION` | `30m` | Час резервування завдання |
| `VALIDATOR_RETENTION` | `7d` | Строк зберігання метаданих у БД |
| `VALIDATOR_CLEANUP_CRON` | `0 15 * * * *` | Розклад очищення в UTC |
| `VALIDATOR_WATCH_ENABLED` | `true` | Увімкнення відстеження папки |

Список допустимих ігор задається у `validator.games` файлу
`src/main/resources/application.yml`.

## Основні URL

- `/login` — вхід;
- `/review` — черга, фільтри та рішення;
- `/statistics` — особиста статистика;
- `POST /api/review-tasks/claim` — отримати поточне/наступне завдання;
- `POST /api/review-tasks/{imageId}/decision` — зберегти рішення;
- `GET /api/images/{imageId}/content` — отримати зображення;
- `GET /api/statistics/me?from=YYYY-MM-DD&to=YYYY-MM-DD` — статистика оператора.

## Обмеження MVP

- підтримується один плоский каталог і формат PNG;
- оператори додаються безпосередньо до БД, адміністративного UI немає;
- рішення одного оператора остаточне;
- ролі та аудит змін користувачів не реалізовані;
- застосунок не виконує OCR і не читає карти із зображення — він лише парсить уже
  розпізнані значення з імені файлу;
- невідомі ігри та невідповідні файли не потрапляють до черги;
- фізичні зображення видаляє зовнішній сервіс, Validator їх ніколи не видаляє.
