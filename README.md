# Recognition Validator

MVP QA-застосунку для ручної перевірки повного результату розпізнавання. Оператор бачить скриншот і
розпізнані з імені файлу дані, після чого приймає остаточне рішення:
`ACCEPTED` або `REJECTED`.

Команди запуску, створення адміністратора, перевірки результатів і діагностики
зібрані в [RUNBOOK.md](RUNBOOK.md).

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
- автоматичні UTC-фільтри черги за датою створення, session ID, грою, notification і наявністю карт користувача;
- опціональний лічильник залишку з фактичними мінімальною та максимальною датами вибірки;
- показ зображення без збереження його вмісту в PostgreSQL;
- zoom, pan, reset, fullscreen, світла/темна тема та вбудована довідка FAQ;
- збереження фільтрів і масштабу в межах вкладки браузера;
- остаточне рішення без можливості повторної зміни;
- особиста UTC-статистика за сьогодні, останні 7 днів і за весь час із денним графіком;
- ZIP-експорт доступних `REJECTED`-скриншотів з адміністративної сторінки;
- видалення з БД метаданих зображень, старших за 4 календарні UTC-дні, без видалення фізичних файлів; денна статистика операторів зберігається безстроково.

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

## Опційне сховище Backblaze B2

B2 вимкнено за замовчуванням (`B2_ENABLED=false`). Увімкнення передає конфігурацію
до контейнера застосунку через `compose.yaml`; усі значення зберігаються тільки в
`.env` або в секретному менеджері:

| Змінна | Значення за замовчуванням | Призначення |
|---|---|---|
| `B2_ENABLED` | `false` | Увімкнення клієнта, scheduler і cloud-доставки |
| `B2_ENDPOINT` | порожньо | HTTPS S3 endpoint, наприклад `https://s3.eu-central-003.backblazeb2.com` |
| `B2_BUCKET` | порожньо | Ім'я одного B2 bucket |
| `B2_ACCESS_KEY_ID` | порожньо | Ідентифікатор application key |
| `B2_SECRET_ACCESS_KEY` | порожньо | Секрет application key |
| `B2_OBJECT_PREFIX` | `validator/` | Префікс об'єктів і обмеження application key |
| `B2_UPLOAD_BATCH_SIZE` | `1000` | Максимум кандидатів за один цикл |
| `B2_UPLOAD_CONCURRENCY` | `32` | Максимум одночасних PUT; локальний benchmark не показав користі вище 32 |
| `B2_UPLOAD_DELAY` | `10s` | Затримка між циклами завантаження |
| `B2_UPLOAD_RETRY_DELAY` | `5m` | Затримка повторної спроби після помилки |
| `B2_LOCAL_PREFERRED_AGE` | `3d` | Локальний файл має перевагу до цього віку |
| `B2_PRESIGNED_URL_TTL` | `30m` | Час дії pre-signed GET (не більше 7 днів) |
| `B2_METADATA_RETENTION` | `21d` | Фіксований строк cloud-метаданих; інше значення не пройде валідацію |
| `B2_CONNECT_TIMEOUT` | `5s` | Максимальний час встановлення з'єднання з B2 |
| `B2_SOCKET_TIMEOUT` | `30s` | Максимальний час очікування мережевої операції |
| `B2_API_CALL_ATTEMPT_TIMEOUT` | `45s` | Максимальний час однієї спроби SDK-виклику |
| `B2_API_CALL_TIMEOUT` | `2m` | Загальний час SDK-виклику разом із retry |
| `B2_MAX_ATTEMPTS` | `4` | Максимум спроб SDK для retryable-помилок |

Контейнер ходить до B2 тільки через HTTPS. Для PostgreSQL використовується
внутрішній URL `jdbc:postgresql://validator-api-db:5432/<DB_NAME>`; новий host-порт
для B2 не потрібен і в Compose не публікується.

Endpoint має бути адресою зі сторінки B2 без імені bucket. Регіон автоматично
визначається з host `s3.<region>.backblazeb2.com`. Створіть окремий application key
з доступом лише до одного bucket, правами читання/запису та обмеженням file name
prefix `validator/`. Дозвіл на перелік усіх bucket для штатної роботи не потрібен:
застосунок виконує PUT/GET конкретних ключів і підписує GET.

Налаштуйте в B2 lifecycle rule саме для prefix `validator/`: приховати об'єкти через
21 день, а приховані версії видалити через ще 1 день. Застосунок зберігає локальні
метадані 4 календарні UTC-дні, а cloud-backed метадані — 21 день від
`cloud_uploaded_at`. PNG Validator не видаляє.

Поки PNG молодший за 3 дні й доступний локально, браузер отримує його напряму з
застосунку. Після 3 днів (або якщо локальний файл зник) автентифікований broker
повертає `307 Temporary Redirect` на короткоживучий pre-signed B2 URL з
`Cache-Control: no-store`. ZIP-експорт читає B2 на сервері потоково, без redirect.
Перед кожним PUT застосунок зберігає детермінований object key та час retry у БД.
Якщо PUT завершився, а фінальне оновлення БД не вдалося, наступний цикл перевіряє
об'єкт через HEAD і завершує стан без повторного завантаження PNG.

AWS SDK використовує стандартну retry-стратегію з exponential backoff для
тимчасових помилок і throttling. Таймаути обмежують завислі мережеві виклики;
після вичерпання SDK-спроб діє наявний scheduler retry через
`B2_UPLOAD_RETRY_DELAY`. Це не є окремою чергою або SLA-механізмом.

Для rollback встановіть `B2_ENABLED=false` і перезапустіть `validator-api-app`.
Нові upload, клієнт B2 та scheduler не запускаються; локальна індексація і 4-денне
локальне очищення продовжують працювати. Об'єкти B2 при цьому не видаляються — ними
керує lifecycle rule.

### Явний benchmark транспортів B2

`B2TransportBenchmarkIT` не запускається звичайним `mvn test`. Це окремий
мережевий стенд без PostgreSQL, WatchService та upload scheduler. Він не змінює
клієнт робочого застосунку. Додаткові транспорти підключені лише для тестів.

| Значення `b2.benchmark.transports` | Реалізація |
| --- | --- |
| `sync` | `S3Client` + `UrlConnectionHttpClient`, поточний базовий варіант |
| `async` | `S3AsyncClient` + Netty |
| `transfer-manager` | `S3TransferManager` поверх Netty `S3AsyncClient` |
| `apache` | `S3Client` + Apache, явний ліміт пулу з'єднань |
| `crt` | Нативний AWS CRT S3 async client, не Netty |

За замовчуванням залишаються лише перші три варіанти. Для нових використовуйте
`apache,crt`, для всіх — `all`. Transfer Manager у цьому стенді не є CRT-клієнтом.

Перед запуском експортуйте `B2_ENDPOINT`, `B2_BUCKET`, `B2_ACCESS_KEY_ID`,
`B2_SECRET_ACCESS_KEY` і, за потреби, `B2_OBJECT_PREFIX`; не передавайте секрети
в аргументах Maven. Стенд створює унікальний підпрефікс та один синтетичний файл
розміром 1 474 560 байтів, який використовується для всіх PUT. Це не справжній
PNG і не перевірка UI, файлового диска або швидкості всього застосунку.

Малий smoke нових транспортів:

```text
./mvnw -Dtest=B2TransportBenchmarkIT \
  -Db2.benchmark.enabled=true \
  -Db2.benchmark.transports=apache,crt \
  -Db2.benchmark.count=3 \
  -Db2.benchmark.concurrency=3 test
```

У PowerShell використовуйте `mvnw.cmd` і беріть `-D...` у лапки, наприклад
`mvnw.cmd -Dtest=B2TransportBenchmarkIT "-Db2.benchmark.enabled=true" test`.

Для порівняння на одному сервері можна вибрати `all`, повторити кожен варіант
через `-Db2.benchmark.repetitions=3` та зафіксувати порядок за допомогою
`-Db2.benchmark.seed=20260826`. Стенд перемішує початковий порядок та змінює його
між повторами, друкує кожен результат і медіану швидкості з мінімумом/максимумом.
Три файли перевіряють сумісність, але не дають надійної оцінки продуктивності.

Обмеження: `count` — 1–500 **на транспорт і повтор** (типово 10),
`concurrency` — 1–128 (типово 8), `repetitions` — 1–5 (типово 1).
Порожні, невідомі та дубльовані назви транспортів або некоректні числа
відхиляються до мережевих операцій. Кожен варіант додатково робить один warmup PUT,
який не входить до вимірювання. Загальне очікування вимірюваного batch обмежене
15 хвилинами; для малих batch діє менший бюджет за кількістю хвиль завантаження.
Це запобіжник стенда, не ліміт швидкості або часу роботи production uploader.
У звіті `bytes` та files/s враховують лише
успішні логічні завантаження; SDK HTTP attempts/retries показуються окремо.
CRT не надає ці SDK-метрики, тому для нього виводиться `unavailable`, а не нуль.
Для нього також немає стандартного API-attempt timeout; ці обмеження описані
в [документації AWS CRT](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/crt-based-s3-client.html).
У стенді native memory CRT обмежена 1 GiB, окремо від Java heap: врахуйте обидва
види пам'яті при запуску Docker. Якщо локальне очікування async PUT перерване або
перевищило таймаут, стенд зупиняється з префіксом для ручної перевірки, а не
вважає скасований Java future доказом завершення мережевого запиту.

Перед PUT стенд друкує очікуваний загальний обсяг відправки та номінальний
піковий обсяг зберігання. Це різні величини: після кожного транспорту/повтору
видаляються **всі версії та delete markers лише його тестового підпрефікса**,
після чого перевіряється, що він порожній. Наступний прогін не починається,
якщо cleanup не підтверджений. Якщо підпрефікс уже зайнятий до першого PUT,
тест завершується без видалення його вмісту. Помилка cleanup містить префікс
для подальшої ручної перевірки. Повторні PUT можуть створювати додаткові версії,
тому номінальний обсяг не є жорсткою гарантією storage cap. Залиште запас у bucket.
Якщо процес аварійно завершився, перевірте надрукований префікс і видаліть
його тестові версії перед наступним запуском; робочі об'єкти не чіпайте.

Звичайний `mvn test` не запускає цей стенд і не потребує реальних B2 credentials.

Будь-який smoke upload у реальний bucket потребує окремого явного погодження.
Перевірка має використовувати лише один спеціально створений тестовий PNG і ключ
під `validator/`; не використовуйте робочі зображення та не запускайте таку
процедуру автоматично. Покроковий runbook наведено в [RUNBOOK.md](RUNBOOK.md).

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
VALIDATOR_IMAGE_ROOT_HOST=/data/recognition-api/completed_recognition
POSTGRES_DATA_ROOT_HOST=/srv/recognition-validator/postgres
```

Значення `POSTGRES_DATA_ROOT_HOST` монтується в
`/var/lib/postgresql/data` контейнера. Тому видалення або пересоздання
контейнера не видаляє файли бази даних із вказаної папки.

Підняти застосунок разом із PostgreSQL:

```powershell
docker compose up -d --build validator-api-app
docker compose ps
docker compose logs -f validator-api-app
```

Перший запуск створює таблиці через Hibernate `ddl-auto=update`. Створити першого
адміністратора без локально встановленої Java:

```powershell
$adminPasswordHash = docker compose run --rm --no-deps --entrypoint java validator-api-app "-Dloader.main=com.introlabsystems.recognitionvalidator.cli.PasswordHashCli" -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher change-me-now | Select-Object -Last 1
$adminUserId = [guid]::NewGuid()
$adminSql = "INSERT INTO app_user (id, username, password_hash, enabled, role, created_at) VALUES ('$adminUserId', 'admin', '$adminPasswordHash', TRUE, 'ADMIN', now());"
$dbUser = (docker compose exec -T validator-api-db printenv POSTGRES_USER).Trim()
$dbName = (docker compose exec -T validator-api-db printenv POSTGRES_DB).Trim()
docker compose exec -T validator-api-db psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -c $adminSql
```

Після запуску сторінка входу доступна за адресою
[http://localhost:18080/login](http://localhost:18080/login) за стандартного `SERVER_PORT`. Для зупинки:

```powershell
docker compose down
```

Команда `docker compose down -v` не видаляє bind-mounted папку PostgreSQL.
База видаляється лише вручну разом із каталогом `POSTGRES_DATA_ROOT_HOST`;
перед цим PostgreSQL повинен бути зупинений.

## Запуск без Docker

Запустити лише основну PostgreSQL:

```powershell
docker compose up -d validator-api-db
$dbUser = (docker compose exec -T validator-api-db printenv POSTGRES_USER).Trim()
$dbName = (docker compose exec -T validator-api-db printenv POSTGRES_DB).Trim()
docker compose exec validator-api-db pg_isready -U $dbUser -d $dbName
```

У першому вікні PowerShell вказати тестову папку та запустити застосунок:

```powershell
$env:VALIDATOR_IMAGE_ROOT='C:\Users\dimag\Downloads\test'
$env:DB_URL='jdbc:postgresql://localhost:5436/recognition_validator'
$env:DB_USERNAME='value-from-DB_USERNAME-in-.env'
$env:DB_PASSWORD='value-from-DB_PASSWORD-in-.env'
.\mvnw.cmd spring-boot:run
```

Перший запуск створить таблиці через Hibernate `ddl-auto=update`. Поки застосунок працює, у другому вікні PowerShell створити
першого адміністратора:

```powershell
$adminPasswordHash = .\mvnw.cmd -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.introlabsystems.recognitionvalidator.cli.PasswordHashCli" "-Dexec.args=change-me-now" | Select-Object -Last 1
$adminUserId = [guid]::NewGuid()
$adminSql = "INSERT INTO app_user (id, username, password_hash, enabled, role, created_at) VALUES ('$adminUserId', 'admin', '$adminPasswordHash', TRUE, 'ADMIN', now());"
docker compose exec -T validator-api-db psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -c $adminSql
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
.\mvnw.cmd clean test
```

Набір охоплює парсинг, індексацію, конфігурацію, cleanup, ZIP-експорт,
буфер і координатор файлових подій, backoff, конкурентну чергу/статистику та web/security.

## Конфігурація

| Змінна | Значення за замовчуванням | Призначення |
|---|---|---|
| `VALIDATOR_IMAGE_ROOT` | `./data/images` | Папка PNG на сервері |
| `DB_URL` | `jdbc:postgresql://validator-api-db:5432/recognition_validator` у Docker | JDBC URL окремої БД |
| `DB_USERNAME` | `validator` | Користувач PostgreSQL |
| `DB_PASSWORD` | `validator` | Пароль PostgreSQL |
| `DB_MAX_POOL_SIZE` | `10` | Максимум з'єднань застосунку з PostgreSQL |
| `DB_MIN_IDLE` | `2` | Мінімум idle-з'єднань у пулі застосунку |
| `POSTGRES_DATA_ROOT_HOST` | `./data/postgres` | Постійна папка файлів PostgreSQL на Docker-host |
| `POSTGRES_HOST_PORT` | `5436` | Порт PostgreSQL на Docker-host; між контейнерами використовується `5432` |
| `POSTGRES_MAX_CONNECTIONS` | `50` | Максимум одночасних з'єднань PostgreSQL у Docker |
| `POSTGRES_SHM_SIZE` | `256mb` | Розмір Docker shared memory для PostgreSQL |
| `POSTGRES_MAX_PARALLEL_WORKERS_PER_GATHER` | `0` | Додаткові parallel workers одного SQL-запиту |
| `SERVER_PORT` | `18080` | HTTP-порт на Docker-host |
| `APP_MEMORY_LIMIT` | `5g` | Жорсткий Docker-ліміт усієї пам'яті JVM-процесу |
| `VALIDATOR_IMAGE_ROOT_HOST` | `./data/images` | Папка скриншотів на Docker-host, що монтується read-only |
| `VALIDATOR_BATCH_SIZE` | `1000` | Розмір DB-batch і поріг негайної обробки файлових подій |
| `VALIDATOR_LEASE_DURATION` | `30m` | Час резервування завдання |
| `VALIDATOR_RETENTION` | `4d` | Кількість календарних UTC-днів зберігання метаданих у БД |
| `VALIDATOR_CLEANUP_CRON` | `0 0 9 * * *` | Розклад очищення в UTC |
| `VALIDATOR_CLEANUP_BATCH_SIZE` | `5000` | Максимум рядків в одній короткій транзакції очищення |
| `VALIDATOR_CLEANUP_MAX_BATCHES` | `0` | `0` — очистити всі прострочені записи пакетами; додатне число обмежує кількість пакетів за запуск |
| `VALIDATOR_WATCH_ENABLED` | `true` | Увімкнення відстеження папки |
| `VALIDATOR_WATCH_FLUSH_INTERVAL` | `2s` | Інтервал пакетної обробки файлових подій |
| `VALIDATOR_WATCH_MAX_PENDING_EVENTS` | `50000` | Аварійний ліміт унікальних шляхів у буфері до повної звірки |
| `COUNT_REMAINING_SCREENSHOTS` | `true` | Показувати залишок за фільтрами; `false` також вимикає відповідні `COUNT(*)` запити |

Список допустимих ігор задається у `validator.games` файлу
`src/main/resources/application.yml`.

## Основні URL

- `/login` — вхід;
- `/admin` — керування операторами та статистика за останні 7 днів;
- `POST /admin/rejected-screenshots.zip` — ZIP-експорт доступних відхилених скриншотів за UTC-часом завершення розпізнавання;
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

## Експорт відхилених скриншотів

Адміністратор може завантажити ZIP на сторінці `/admin`. Межі дат застосовуються до
`processed_at` — UTC-часу завершення розпізнавання, розібраного з імені PNG. Нижня
межа включна, верхня — виключна. Порожні поля охоплюють усі доступні дані.

До стандартного експорту потрапляють лише доступні `REJECTED`-файли, які ще не
завантажувалися. Після успішного запису PNG до ZIP завдання позначається часом
завантаження. Прапорець `Include previously downloaded` дозволяє повторно включити
такі файли. Відсутні на диску PNG пропускаються.
