# HealthConnect Export 📱

[![CI](https://github.com/kas-cor/healthconnect-export/actions/workflows/build-apk.yml/badge.svg)](https://github.com/kas-cor/healthconnect-export/actions/workflows/build-apk.yml)
[![Coverage](https://raw.githubusercontent.com/kas-cor/healthconnect-export/main/badges/coverage.svg)](https://github.com/kas-cor/healthconnect-export/actions/workflows/build-apk.yml)
[![Branches](https://raw.githubusercontent.com/kas-cor/healthconnect-export/main/badges/branches.svg)](https://github.com/kas-cor/healthconnect-export/actions/workflows/build-apk.yml)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-purple)](https://kotlinlang.org)
[![Release](https://img.shields.io/github/v/release/kas-cor/healthconnect-export)](https://github.com/kas-cor/healthconnect-export/releases)
[![English](https://img.shields.io/badge/README-English-blue)](README.md)

> Бейдж покрытия автообновляется при каждом пуше в `main` через CI.

Android-приложение для экспорта данных Google Health Connect в JSON с опциональной синхронизацией на Google Drive и отправкой на webhook. **Два языка**: английский и русский.

## Возможности ✨

- **Ежедневный экспорт** — один день = один JSON-файл (`health_YYYY-MM-DD.json`)
- **Локальное хранение** — файлы сохраняются на устройстве (внешняя директория)
- **Экспорт по расписанию** — ежедневно или еженедельно через WorkManager
- **Google Drive синхронизация** — опционально, автосинхронизация на Drive
- **Webhook доставка** — POST JSON по URL (с опциональным Bearer-токеном, повтор при ошибке)
- **Тест вебхука** — проверка соединения с данными за сегодня прямо из UI
- **Webhook каждые 2 часа** — отправка данных за сегодня на webhook каждые 2 часа (без Drive)
- **20 типов данных** — шаги, пульс, сон, калории, тренировки, питание, скорость и другие
- **Выбор периода** — последние 7/30 дней или произвольный диапазон с календарём
- **Выбор источника данных** — предпочитаемый источник (Google Fit, Samsung Health и т.д.)
- **Отмена экспорта** — отмена текущего экспорта одним нажатием
- **Сводка на дашборде** — карточка со статистикой после экспорта: шаги, пульс, калории, дистанция, сон
- **JSON просмотрщик** — полноэкранный просмотрщик на тёмной теме с подсветкой синтаксиса + копирование в буфер
- **Переключение темы** — светлая/тёмная/как в системе с анимированным переходом (вкладка «Настройки»)
- **Переключение языка** — английский / русский через вкладку «Настройки», сохраняется между сессиями
- **Навигация по вкладкам** — Экспорт / История / Интеграции / Расписание / Настройки
- **Проверка обновлений** — проверка релизов GitHub с точкой-уведомлением в шапке + диалог «Что нового»
- **Автовход в Google Drive** — прошлая сессия восстанавливается автоматически при запуске
- **Управление историей** — раскрывающийся список «Показать все файлы» (открывается любой файл), счётчик файлов и общий размер в шапке
- **Ручной + автоматический** — экспорт по запросу или по расписанию

## Архитектура 🏗️

```
MainActivity → ExportScreen (Compose) → ExportViewModel
  ├─ ExportDataUseCase         — рабочий процесс экспорта как Flow<ExportStep>
  ├─ DriveManager              — вход/синхронизация/выход Google Drive
  ├─ WebhookManager            — настройки вебхука/отправка/тест
  ├─ ScheduleManager           — создание/отмена периодического экспорта
  ├─ HealthConnectRepository   — чтение Health Connect API (пакетно через TypeHandler)
  ├─ LocalExportRepository     — сохранение/чтение/удаление JSON-файлов
  ├─ GoogleDriveRepository     — Drive API (загрузка/список/удаление)
  ├─ WebhookRepository         — POST с повторной попыткой
  ├─ DailyExportWorker         — фоновый экспорт по расписанию (ежедневно/еженедельно)
  └─ Every2HoursWebhookWorker  — периодическая webhook-отправка (каждые 2 часа)
```

## Быстрый старт 🚀

### Build-скрипт (`./build.sh`)

Скрипт для сборки, установки, запуска и тестирования приложения.

| Флаг | Описание |
|---|---|
| *(без флагов)* | Собрать debug APK |
| `--run` | Собрать debug APK, установить на устройство и запустить |
| `--release` | Собрать release APK (подпись продакшн-ключом или debug-ключом) |
| `--install [apk]` | Установить APK на устройство (по умолч. debug APK) |
| `--pull` | Вытянуть экспортированные JSON-файлы (`health_*.json`) с устройства |
| `--test <url> [token]` | Отправить тестовый POST с демо-данными Health Connect на webhook |
| `--logs` | Показать logcat, отфильтрованный по приложению (`ExportScreen`, `HealthConnect`, `DriveManager`, etc.) |
| `--help` | Показать справку |

```bash
# Только сборка debug
./build.sh

# Сборка, установка, запуск
./build.sh --run

# Release сборка (подписанная или debug-подписанная)
./build.sh --release

# Вытянуть JSON-файлы с устройства
./build.sh --pull

# Отправить тестовые данные на webhook
./build.sh --test https://example.com/webhook

# С Bearer-токеном
./build.sh --test https://example.com/webhook my-token

# Показать логи приложения
./build.sh --logs

# Напрямую через Gradle
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Тестирование 🧪

```bash
# Запустить все модульные тесты (308 тестов)
./gradlew testDebugUnitTest

# Отчёт покрытия + проверка порогов
./gradlew jacocoTestReport jacocoTestCoverageVerification
# Открыть: app/build/reports/jacoco/jacocoTestReport/html/index.html
```

**Наборы тестов (всего 308):**

| Файл | Тестов | Что проверяет |
|---|---|---|
| `WebhookRepositoryTest` | 39 | sendRecords через локальный HTTP-сервер (успех/ошибка/авторизация/спецсимволы/JSON) + валидация URL |
| `ExportViewModelTest` | 45 | Состояния ViewModel: экспорт, webhook, вход в Drive, тихий вход/восстановление, флаг sign-out, проверка обновлений |
| `DataModelsSerializationTest` | 37 | Обратная сериализация: DailyHealthRecord, ExportConfig, enum'ы, SpeedData, sourceDisplayName |
| `HighlightJsonSyntaxTest` | 31 | Подсветка синтаксиса JSON: строки, числа, boolean, null, вложенные объекты, массивы, экранированные кавычки |
| `HumanReadableMapperTest` | 27 | 8 функций-мапперов: bodyPosition, specimenSource, sleepStage, exerciseType и т.д. |
| `DailyExportWorkerTest` | 28 | `doWork()` (успех/уже экспортировано/пусто/исключения) + `schedule()` (ежедневно/еженедельно/вручную/отмена) |
| `LocalExportRepositoryTest` | 24 | Файловые операции: сохранение, список, очистка, isExported, формат имени файла |
| `GoogleDriveRepositoryTest` | 23 | Синхронизация Drive: загрузка, список, скачивание, удаление, scopes, спецсимволы |
| `Every2HoursWebhookWorkerTest` | 18 | doWork (happy path, пустой URL, исключения) + schedule/cancel |
| `ExportDataUseCaseTest` | 16 | Рабочий процесс экспорта: разрешения, проверка Health Connect, прогресс, webhook, Drive |
| `LocaleManagerTest` | 12 | localeDisplayName (все ветки), saveLocale/getSavedLocale |
| `ExportedFilesCardTest` | 5 | Нарезка visibleExportFiles: свернуть до N, showAll, ≤N файлов, пустой список |
| `DateRangeCardTest` | 3 | Compose UI тесты (сейчас `@Ignore` — требуют Robolectric) |

## CI/CD 🚀

Workflow: `.github/workflows/build-apk.yml`

### Триггеры

- **Push** в `main`, `develop`
- **Pull Request** в `main`
- **Push тега** `v*` (например, `v1.1`)
- **Вручную** через `workflow_dispatch`

### Конвейер

```
Push → Lint + Ktlint → Модульные тесты
     → Покрытие (JaCoCo отчёт + проверка 9 правил + бейдж)
     → Debug APK
     └─ Push тега → Расшифровка keystore → Подпись → Release APK → GitHub Release
```

### Проверка покрытия

После каждого пуша JaCoCo проверяет покрытие по 9 правилам. Если какое-то правило нарушено, задача `coverage` падает:

| Область | Счётчик | Порог |
|---|---|---|
| **Весь проект** | LINE | ≥ 30% |
| | BRANCH | ≥ 12% |
| | INSTRUCTION | ≥ 15% |
| | CLASS | ≥ 45% |
| **Пакет worker** | LINE | ≥ 95% |
| **Пакет data** | LINE | ≥ 70% |
| **Пакет viewmodel** | LINE | ≥ 65% |
| **Пакет util** | LINE | ≥ 50% |
| **Пакет repository** | LINE | ≥ 35% |

При пуше в `main` бейдж покрытия автообновляется в `badges/`.

### Артефакты

| Артефакт | Хранение | Содержимое |
|---|---|---|
| `test-results` | 7 дней | HTML отчёты тестов |
| `coverage-html` | 7 дней | JaCoCo HTML отчёт |
| `coverage-xml` | 30 дней | JaCoCo XML для Codecov/SonarCloud |
| `HealthConnectExport-debug` | 7 дней | Debug APK |
| `HealthConnectExport-release` | 30 дней | Подписанный release APK (только при пуше тега) |

### GitHub Secrets

| Secret | Описание |
|---|---|
| `KEYSTORE_BASE64` | `healthconnect-release.jks` в base64 |
| `KEYSTORE_PASSWORD` | Пароль keystore |
| `KEY_ALIAS` | Псевдоним ключа (по умолч.: `healthconnect`) |
| `KEY_PASSWORD` | Пароль ключа (если не задан, используется `KEYSTORE_PASSWORD`) |

### Релиз

```bash
git tag v1.1
git push origin v1.1
# CI: увеличить версию → собрать → создать GitHub Release
```

[Workflow](.github/workflows/build-apk.yml)

## Настройка Google Drive 🔐

Для Google Sign-In требуется **два** OAuth Client ID в одном Google Cloud Project:

| Тип | Client ID | Назначение |
|---|---|---|
| **Android** (OAuth) | `730530422387-oaffqtrvfd1rqr6jn1uq8791mgbbpmlj` | Проверка SHA-1 + package name (Google Play Services) |
| **Web application** (OAuth) | `730530422387-dveo97h089iesh4etmj74q9dn8j221f1` | `requestIdToken()` в `BuildConfig.GOOGLE_CLIENT_ID` |

### SHA-1 отпечатки

| Тип сборки | SHA-1 отпечаток |
|---|---|
| **Release** | `2A:BF:A4:CA:62:59:78:A2:5D:78:FD:74:2D:CB:CA:07:D2:37:42:72` |
| **Debug** | `8B:BB:D1:45:E2:61:5B:02:57:E1:3F:26:29:1A:AF:F0:2C:40:77:73` |

### OAuth consent screen

1. Откройте [OAuth consent screen](https://console.cloud.google.com/apis/credentials/consent)
2. Тип: **External** → **Testing** (не требуется верификация)
3. Добавьте **Test users** — свой email
4. На шаге **Scopes** добавьте `https://www.googleapis.com/auth/drive.file`

### Требуемые API

- [Google Drive API](https://console.cloud.google.com/apis/library/drive.googleapis.com) — `ВКЛЮЧЁН`
- [Identity Toolkit API](https://console.cloud.google.com/apis/library/identitytoolkit.googleapis.com) — `ВКЛЮЧЁН`

### Проверка подписи

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
# SHA-1: 2abfa4ca625978a25d78fd742dcbca07d2374272
```

## Webhook 📡

После экспорта данные о здоровье можно отправить на любой URL через POST-запрос.

### Настройка

- **URL** — настраивается в UI, валидируется на стороне клиента (красная подсветка при неверном URL)
- **Авторизация** — опциональный Bearer-токен (хранится в `ExportConfig`)
- **Автоотправка** — переключатель в UI для автоматической отправки после каждого экспорта
- **Тест** — кнопка «Тест вебхука» отправляет данные за сегодня и показывает результат
- Работает как с ручным, так и с запланированным экспортом

### Формат запроса

| Атрибут | Значение |
|---|---|
| **Метод** | `POST` |
| **Content-Type** | `application/json` |
| **Authorization** | `Bearer <token>` (опционально) |
| **Таймаут** | 15 секунд |
| **Повтор** | 1 попытка через 1 с при ошибках 5xx/сетевых |

### Структура payload

Тело JSON обёрнуто в конверт `messages`:

```json
{
  "messages": [
    {
      "date": "2026-05-23",
      "steps": { "total_steps": 12453, "records_count": 480 },
      "heart_rate": { "avg_bpm": 72.5, "min_bpm": 55, "max_bpm": 142, "records_count": 18 },
      "sleep": {
        "total_duration_minutes": 420,
        "sleep_stages": { "Deep sleep": 90, "Light sleep": 195, "REM sleep": 105, "Awake": 30 },
        "records_count": 1
      },
      "metadata": {
        "app_version": "1.0.0",
        "export_timestamp": "2026-05-23T23:00:00",
        "timezone": "Europe/Moscow",
        "source_device": "test_device"
      }
    }
  ]
}
```

Каждый элемент массива `messages` — это `DailyHealthRecord` — одна запись за день, содержащая все выбранные типы данных о здоровье (шаги, пульс, сон, калории, дистанция, этажи, активные калории, вес, жир, давление, глюкоза, сатурация, температура, дыхание, гидратация, пульс покоя, тренировки, питание, скорость и цикл).

## Расписание ⏰

- **Вручную**, **Ежедневно** (24ч) или **Еженедельно** (168ч)
- **Каждые 2 часа** — опциональная webhook-отправка данных за сегодня (чекбокс в разделе Расписание)
- По умолчанию: **Ежедневно** (автовключение при запуске)
- Использует WorkManager `PeriodicWorkRequest` с ограничением battery-not-low

## Локализация 🌐

- **Языки**: английский (по умолчанию), русский
- **Переключение языка**: через вкладку «Настройки» → «Язык»
- **Покрытие**: Все **174 строки** переведены в `values-ru/strings.xml`
- **Безопасность форматов**: все плейсхолдеры `%d`, `%s`, `%.1f` совпадают между языками
- **Сохранение**: выбранный язык сохраняется в SharedPreferences

### Как переключить язык 🇷🇺

1. Откройте вкладку **Настройки** (шестерёнка в нижней панели) → раздел **Язык**
2. Выберите **Русский** (или **Системный** для автоматического выбора языка устройства)

### Что переведено

Все строки интерфейса:
- Названия типов данных (Шаги, Пульс, Сон, Калории, Дистанция и т.д.)
- Интерфейс экспорта и расписания
- Сообщения об ошибках и статусе
- Google Drive, Webhook
- JSON просмотрщик, сводка экспорта
- Все системные уведомления (WorkManager)

### Технические детали

- Файл перевода: `app/src/main/res/values-ru/strings.xml`
- Все **174 строки** переведены — ни одной пропущенной английской строки
- Форматные плейсхолдеры (`%d`, `%s`, `%.1f`) полностью совпадают с английской версией — никаких crash'ей при переключении языка
- Выбор языка сохраняется в `SharedPreferences` и восстанавливается после перезапуска
- На здоровье не влияет — JSON-данные экспортируются с английскими ключами независимо от языка интерфейса

### Добавить или исправить перевод

Отредактируйте `app/src/main/res/values-ru/strings.xml` и запустите валидатор:

```bash
python3 scripts/locale-validator.py
```

### Как добавить новый язык 🌍

Хотите перевести приложение на свой язык? Вот пошаговая инструкция:

#### 1. Создайте файл перевода

Скопируйте английский `strings.xml` в новую папку с кодом вашего языка.

Например, для французского:

```bash
# Создать папку для нового языка
mkdir -p app/src/main/res/values-fr

# Скопировать английский strings.xml
cp app/src/main/res/values/strings.xml app/src/main/res/values-fr/strings.xml
```

Подставьте свой [код языка](https://developer.android.com/guide/topics/resources/providing-resources#LocaleQualifier) вместо `fr`.

#### 2. Переведите все строки

Отредактируйте `app/src/main/res/values-fr/strings.xml` — переведите **все** значения (`<string>...</string>`), но **не трогайте** атрибуты `name`.

Важно:
- Сохраняйте форматные плейсхолдеры (`%d`, `%s`, `%.1f`) — они подставляются программно
- Брендовые названия (`HealthConnect Export`, `Google Drive`, `Health Connect`, `WorkManager`) можно оставить на английском
- Технические термины (`JSON`, `URL`, `Bearer`, `Webhook`, `WebSocket`) обычно не переводят

#### 3. Запустите валидатор

```bash
python3 scripts/locale-validator.py
```

Валидатор проверит, что все строки переведены и нет пропущенных.

#### 4. Добавьте язык в UI приложения

Отредактируйте `LocaleManager.kt` (`app/src/main/java/com/healthconnect/export/util/LocaleManager.kt`):

```kotlin
// Добавьте ваш язык в список доступных
enum class AppLocale(val code: String, val displayName: String) {
    SYSTEM("", "System"),
    EN("en", "English"),
    RU("ru", "Русский"),
    FR("fr", "Français"),  // ← ваш язык
}
```

#### 5. Создайте README на вашем языке

Скопируйте и переведите `README.md`:

```bash
cp README.md README.fr.md
# Переведите содержимое на французский
```

Добавьте бейдж-ссылку в `README.md`:

```markdown
[![Français](https://img.shields.io/badge/README-Fran%C3%A7ais-blue)](README.fr.md)
```

#### 6. Откройте Pull Request

Создайте PR на GitHub с файлами перевода. После мержа:
- В приложении появится новый язык в переключателе языков 🌐
- README на вашем языке будет доступен по бейджу

**Спасибо за ваш вклад!** 🙌

## Разрешения

| Разрешение | Назначение |
|---|---|
| `INTERNET` | Синхронизация Drive + webhook |
| `ACCESS_NETWORK_STATE` | Проверка сети |
| `FOREGROUND_SERVICE` | Фоновая работа |
| `RECEIVE_BOOT_COMPLETED` | Перезапуск после перезагрузки |
| `health.READ_*` | Типы данных Health Connect (20 типов) |

## Технологический стек

| Компонент | Версия |
|---|---|
| Язык | Kotlin 2.4.10 |
| UI | Jetpack Compose + Material3 (BOM 2026.05) |
| Сборка | AGP 9.3.1 / Gradle 9.6.1 |
| Health Connect | `connect-client:1.1.0` |
| Google Drive | `google-api-services-drive:v3-rev20240123`, `google-http-client-gson:2.1.0` |
| Авторизация | `play-services-auth:21.6.0` |
| Фон | WorkManager `work-runtime-ktx:2.11.2` |
| Сериализация | `kotlinx-serialization-json:1.11.0` |
| minSdk / targetSdk / compileSdk | 28 / 36 / 36 |
| JVM | 21 |
| Тестирование | JUnit 4.13.2, Mockito 5.23.0, mockito-kotlin 6.3.0 |
| Покрытие | JaCoCo 0.8.11 |
| CI | GitHub Actions |

## Список изменений 📋

Полная история релизов в [CHANGELOG.md](CHANGELOG.md).

| Версия | Дата | Что нового |
|---|---|---|
| [v1.7](https://github.com/kas-cor/healthconnect-export/releases/tag/v1.7) | 2026-08-10 | Проверка обновлений + диалог «Что нового», навигация по вкладкам, автовход в Google Drive, улучшения Истории |
| [v1.6](https://github.com/kas-cor/healthconnect-export/releases/tag/v1.6) | 2026-07-17 | Build-скрипт (`build.sh`), русский README (`README.ru.md`), проверка синхронизации README |
| [v1.5](https://github.com/kas-cor/healthconnect-export/releases/tag/v1.5) | 2026-06-08 | Webhook-воркер каждые 2 часа, кнопка теста webhook, сортировка файлов по убыванию, обновление зависимостей (Kotlin 2.3.21, Gradle 9.5.1) |
| [v1.4](https://github.com/kas-cor/healthconnect-export/releases/tag/v1.4) | 2026-06-07 | Исправление диапазона дат: endDate включает сегодня, исправлены пресеты 7/30 дней |
| [v1.3](https://github.com/kas-cor/healthconnect-export/releases/tag/v1.3) | 2026-05-27 | Формат webhook `{\"messages\": [...]}`, прогресс экспорта с барами по дням, кнопка отмены, чтение по дням |
| [v1.2](https://github.com/kas-cor/healthconnect-export/releases/tag/v1.2) | 2026-05-27 | **+99 тестов** (всего 295), покрытие ~55%, WebhookRepository переписан с локальным HTTP-сервером |
| [v1.1](https://github.com/kas-cor/healthconnect-export/releases/tag/v1.1) | 2026-05-26 | Проверка покрытия, ktlint, обновление API, авторизация webhook, +83 теста, русская локализация |
| [v1.0](https://github.com/kas-cor/healthconnect-export/releases/tag/v1.0) | — | Первый релиз: JSON экспорт, Drive синхронизация, webhook, WorkManager, Material3 UI |

## Лицензия 📄

MIT
