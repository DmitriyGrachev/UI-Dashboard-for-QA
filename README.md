# Recognition Validator

MVP QA-застосунку для ручної перевірки повного результату розпізнавання. Оператор бачить скриншот і
розпізнані з імені файлу дані, після чого приймає остаточне рішення:
`ACCEPTED` або `REJECTED`.

## Що реалізовано

- вхід оператора й адміністратора через звичайну сесію Spring Security та BCrypt;
- сесія тривалістю 24 години;
- розмежування доступу за ролями `ADMIN` і `OPERATOR`;
- адміністрування операторів: створення, деактивація, відновлення та зміна пароля;
- адміністративна статистика операторів за останні 7 днів і за весь час із пагінацією по 10 облікових записів;
- первинна індексація наявних PNG з однієї папки;
- пакетне відстеження створення та видалення файлів через `WatchService` з обмеженим буфером подій;
- розбір гри, session ID, карт, кнопок, часу завершення та duration розпізнавання з імені PNG;
- атомарна черга: один скриншот одночасно отримує лише один оператор;
- автоматичні фільтри черги за датою створення, session ID, грою та notification з опціональним лічильником залишку;
- показ зображення без збереження його вмісту в PostgreSQL;
- zoom, pan, reset і fullscreen;
- остаточне рішення без можливості повторної зміни;
- особиста UTC-статистика за сьогодні, останні 7 днів і за весь час із денним графіком;
- видалення з БД метаданих зображень, старших за 7 днів, без видалення фізичних файлів; денна статистика операторів зберігається безстроково.

## Як працює індексація

Під час запуску застосунок один раз переглядає файли безпосередньо в
`VALIDATOR_IMAGE_ROOT`. Вкладені папки не обходяться. До БД пакетами записуються
лише метадані допустимих PNG, ім'я яких починається з одного з налаштованих кодів
ігор.

Після стартового обходу окремий watcher-потік лише додає події створення та видалення
до обмеженого буфера. Події одного шляху об'єднуються до останнього стану, а окремий
потік пакетно оновлює БД раз на 2 секунди або відразу після накопичення 1 000 шляхів.
Постійного повторного читання всіх 600 000 зображень немає. Якщо аварійний буфер на
50 000 шляхів або системна черга подій переповнюється, накопичені події замінюються
однією повною звіркою папки. Помилка PostgreSQL не запускає поштучні повтори: одна
звірка повторюється із затримками 2, 5, 15 і максимум 30 секунд.

Ідентифікатор зображення — SHA-256 нормалізованого відносного шляху. Тому повторна
індексація не створює дублікати та не скидає вже прийняте рішення. Абсолютний шлях
і байти зображення в БД не зберігаються.

## Вимоги

- Docker Desktop із Docker Compose;
- доступ Docker до папки зі скриншотами;
- Java 21 потрібна лише для запуску без Docker та локальних тестів.

## Запуск у Docker

Застосунок збирається на Amazon Corretto 21 і запускається на звичайному образі
`amazoncorretto:21`. PostgreSQL використовує звичайний образ `postgres:17`.
Alpine-образи не використовуються.

Створити локальну конфігурацію:

```powershell
Copy-Item .env.example .env
```

У `.env` вказати папки зі скриншотами та файлами PostgreSQL. Для Windows
шляхи записуються через `/`. Папку PostgreSQL потрібно розміщувати на
локальному диску поза OneDrive, мережевими та іншими синхронізованими папками:

```dotenv
VALIDATOR_IMAGE_ROOT_HOST=C:/Users/dimag/Downloads/test
POSTGRES_DATA_ROOT_HOST=C:/recognition-validator-data/postgres
```

На Linux-сервері, наприклад:

```dotenv
POSTGRES_DATA_ROOT_HOST=/srv/recognition-validator/postgres
```

Значення `POSTGRES_DATA_ROOT_HOST` монтується в
`/var/lib/postgresql/data` контейнера. Тому видалення або пересоздання
контейнера не видаляє файли бази даних із вказаної папки.

Підняти застосунок разом із PostgreSQL:

```powershell
docker compose up -d --build app
docker compose ps
docker compose logs -f app
```

Перший запуск створює таблиці через Hibernate `ddl-auto=update`. Створити першого
адміністратора без локально встановленої Java:

```powershell
$adminPasswordHash = docker compose run --rm --no-deps --entrypoint java app "-Dloader.main=com.introlabsystems.recognitionvalidator.auth.PasswordHashCli" -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher change-me-now | Select-Object -Last 1
$adminUserId = [guid]::NewGuid()
$adminSql = "INSERT INTO app_user (id, username, password_hash, enabled, role, created_at) VALUES ('$adminUserId', 'admin', '$adminPasswordHash', TRUE, 'ADMIN', now());"
docker compose exec -T postgres psql -U validator -d recognition_validator -v ON_ERROR_STOP=1 -c $adminSql
```

Після запуску сторінка входу доступна за адресою
[http://localhost:8080/login](http://localhost:8080/login). Для зупинки:

```powershell
docker compose down
```

Команда `docker compose down -v` не видаляє bind-mounted папку PostgreSQL.
База видаляється лише вручну разом із каталогом `POSTGRES_DATA_ROOT_HOST`;
перед цим PostgreSQL повинен бути зупинений.

## Запуск без Docker

Запустити лише основну PostgreSQL:

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

Перший запуск створить таблиці через Hibernate `ddl-auto=update`. Поки застосунок працює, у другому вікні PowerShell створити
першого адміністратора:

```powershell
$adminPasswordHash = .\mvnw.cmd -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.introlabsystems.recognitionvalidator.auth.PasswordHashCli" "-Dexec.args=change-me-now" | Select-Object -Last 1
$adminUserId = [guid]::NewGuid()
$adminSql = "INSERT INTO app_user (id, username, password_hash, enabled, role, created_at) VALUES ('$adminUserId', 'admin', '$adminPasswordHash', TRUE, 'ADMIN', now());"
docker compose exec -T postgres psql -U validator -d recognition_validator -v ON_ERROR_STOP=1 -c $adminSql
```

Після цього відкрити [http://localhost:8080/login](http://localhost:8080/login) і
увійти як `admin` з паролем `change-me-now`. На сторінці `/admin` створити
операторів та передати їм тимчасові паролі безпечним каналом.

## Тести

Тести використовують окрему тимчасову PostgreSQL на порту `5433`. Testcontainers
не використовується.

```powershell
docker compose --profile test up -d postgres-test
docker compose exec postgres-test pg_isready -U validator -d recognition_validator_test
.\mvnw.cmd "-Dtest=FilenameParserTest,ImageEventBufferTest,FolderWatchCoordinatorTest,ImageProcessingBackoffTest,ImageIndexingTest,ReviewQueueServiceTest,ReviewWorkflowTest,RemainingCountDisabledWebTest,WebSecurityTest" clean test
```

Це весь основний набір: дев'ять тестових класів для парсингу, індексації,
буфера й координатора файлових подій, backoff, конкурентної черги/статистики та web/security.

## Конфігурація

| Змінна | Значення за замовчуванням | Призначення |
|---|---|---|
| `VALIDATOR_IMAGE_ROOT` | `./data/images` | Папка PNG на сервері |
| `DB_URL` | `jdbc:postgresql://localhost:5432/recognition_validator` | JDBC URL окремої БД |
| `DB_USERNAME` | `validator` | Користувач PostgreSQL |
| `DB_PASSWORD` | `validator` | Пароль PostgreSQL |
| `DB_MAX_POOL_SIZE` | `10` | Максимум з'єднань застосунку з PostgreSQL |
| `POSTGRES_DATA_ROOT_HOST` | `./data/postgres` | Постійна папка файлів PostgreSQL на Docker-host |
| `POSTGRES_MAX_CONNECTIONS` | `50` | Максимум одночасних з'єднань PostgreSQL у Docker |
| `POSTGRES_SHM_SIZE` | `256mb` | Розмір Docker shared memory для PostgreSQL |
| `POSTGRES_MAX_PARALLEL_WORKERS_PER_GATHER` | `0` | Додаткові parallel workers одного SQL-запиту |
| `SERVER_PORT` | `8080` | HTTP-порт |
| `VALIDATOR_IMAGE_ROOT_HOST` | `./data/images` | Папка скриншотів на Docker-host, що монтується read-only |
| `VALIDATOR_BATCH_SIZE` | `1000` | Розмір DB-batch і поріг негайної обробки файлових подій |
| `VALIDATOR_LEASE_DURATION` | `30m` | Час резервування завдання |
| `VALIDATOR_RETENTION` | `7d` | Строк зберігання метаданих у БД |
| `VALIDATOR_CLEANUP_CRON` | `0 15 * * * *` | Розклад очищення в UTC |
| `VALIDATOR_CLEANUP_BATCH_SIZE` | `5000` | Максимум рядків в одній короткій транзакції очищення |
| `VALIDATOR_CLEANUP_MAX_BATCHES` | `20` | Максимум транзакцій очищення за один запуск |
| `VALIDATOR_WATCH_ENABLED` | `true` | Увімкнення відстеження папки |
| `VALIDATOR_WATCH_FLUSH_INTERVAL` | `2s` | Інтервал пакетної обробки файлових подій |
| `VALIDATOR_WATCH_MAX_PENDING_EVENTS` | `50000` | Аварійний ліміт унікальних шляхів у буфері до повної звірки |
| `COUNT_REMAINING_SCREENSHOTS` | `true` | Показувати залишок за фільтрами; `false` також вимикає відповідні `COUNT(*)` запити |

Список допустимих ігор задається у `validator.games` файлу
`src/main/resources/application.yml`.

## Основні URL

- `/login` — вхід;
- `/admin` — керування операторами та статистика за останні 7 днів;
- `/review` — черга, фільтри та рішення;
- `/statistics` — особиста статистика;
- `POST /api/review-tasks/claim` — отримати перше завдання або оновити чергу після зміни фільтрів;
- `POST /api/review-tasks/{imageId}/decision` — зберегти рішення та одразу отримати наступне завдання за поточними фільтрами;
- `GET /api/images/{imageId}/content` — отримати зображення;
- `GET /api/statistics/me` — статистика оператора за сьогодні, останні 7 днів і за весь час.

## Обмеження MVP

- підтримується один плоский каталог і формат PNG;
- рішення одного оператора остаточне;
- адміністратори створюються безпосередньо в БД; UI створює лише операторів;
- аудит адміністративних змін не реалізований;
- застосунок не виконує повторне розпізнавання й не читає карти із зображення — він лише парсить
  готовий результат з імені файлу, сформований зовнішнім пайплайном;
- невідомі ігри та невідповідні файли не потрапляють до черги;
- фізичні зображення видаляє зовнішній сервіс, Validator їх ніколи не видаляє.
