# HealthConnect Export — QWEN Context

## Project Overview

Android-приложение для экспорта данных из Google Health Connect в JSON-файлы с опциональной синхронизацией на Google Drive и отправкой на webhook.

**Язык интерфейса:** русский.

### Tech Stack

| Компонент | Технология |
|-----------|-----------|
| Язык | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material3 |
| DI | Ручное (ViewModelFactory) |
| Health API | `androidx.health.connect:connect-client:1.1.0-alpha06` |
| Google Drive | `google-api-services-drive:v3`, `google-http-client-gson` |
| Auth | `play-services-auth:21.0.0` (OAuth 2.0) |
| Фон | WorkManager (`work-runtime-ktx:2.9.0`) |
| Сериализация | `kotlinx-serialization-json:1.6.2` |
| Сборка | Gradle KTS + Android Gradle Plugin 8.7.0 |
| CI | GitHub Actions (build-apk.yml) |
| minSdk / targetSdk / compileSdk | 28 / 34 / 34 |

### Architecture

MVVM с четырьмя репозиториями:

```
MainActivity (ComponentActivity)
  └─ ExportScreen (Compose, LazyColumn)
       └─ ExportViewModel
            ├─ HealthConnectRepository  — чтение Health Connect API
            ├─ LocalExportRepository   — сохранение JSON на устройство
            ├─ GoogleDriveRepository   — синхронизация с Google Drive
            └─ WebhookRepository       — POST-отправка на webhook
       └─ DailyExportWorker (WorkManager, фоновая задача)
```

**Поток экспорта:** UI → ViewModel → HealthConnectRepository (чтение) → LocalExportRepository (JSON) → GoogleDriveRepository (опционально) → WebhookRepository (опционально).

---

## Building and Running

### Сборка

```bash
# Только сборка debug APK
./build

# Сборка, установка на устройство и запуск
./build --run

# Выгрузка JSON-файлов экспорта с устройства
./build --pull
```

### CI

GitHub Actions собирает debug и release APK на каждый push (файл: `.github/workflows/build-apk.yml`).

---

## Project Structure

```
healthconnect-export/
├── app/
│   ├── build.gradle.kts              # Модульные зависимости и конфигурация
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/healthconnect/export/
│       │   ├── MainActivity.kt       # Точка входа, Compose setContent
│       │   ├── data/
│       │   │   └── DataModels.kt     # DailyHealthRecord, ExportConfig, enums
│       │   ├── repository/
│       │   │   ├── HealthConnectRepository.kt
│       │   │   ├── LocalExportRepository.kt
│       │   │   ├── GoogleDriveRepository.kt
│       │   │   └── WebhookRepository.kt
│       │   ├── viewmodel/
│       │   │   ├── ExportViewModel.kt
│       │   │   └── ExportViewModelFactory.kt
│       │   ├── ui/
│       │   │   ├── ExportScreen.kt   # Главный экран (Compose)
│       │   │   └── theme/
│       │   │       └── AppTheme.kt   # Material3 тема
│       │   └── worker/
│       │       └── DailyExportWorker.kt  # Фоновый экспорт
│       └── res/
│           ├── values/strings.xml    # Русская локализация
│           ├── values/themes.xml
│           └── mipmap-*/             # Иконки приложения
├── build                             # Скрипт сборки (--run, --pull)
├── build.gradle.kts                  # Корневой build-файл (plugins)
├── settings.gradle.kts               # Настройки проекта
├── gradle.properties                 # JVM args, AndroidX, Kotlin style
├── client_secret_*.json              # OAuth 2.0 Client ID (Google)
└── README.md
```

---

## Key Data Models

- **`DailyHealthRecord`** — запись за день: `date`, `steps`, `heartRate`, `sleep`, `calories`, `metadata`
- **`ExportConfig`** — настройки экспорта: `enabledTypes`, `frequency`, `autoSyncDrive`, `webhookUrl`, `autoSendWebhook`, `outputDirectory`
- **`HealthDataType`** — enum: `STEPS`, `HEART_RATE`, `SLEEP`, `CALORIES`
- **`ExportFrequency`** — enum: `MANUAL` (0ч), `DAILY` (24ч), `WEEKLY` (168ч)
- **`ExportUiState`** — состояние UI: `isLoading`, `exportProgress`, `exportedFiles`, `driveStatus`, `scheduleStatus`, `selectedTypes`, `startDate`, `endDate`, `frequency`, `autoSyncDrive`, `webhookUrl`, `autoSendWebhook`, `message`

---

## Development Conventions

- **Kotlin:** `code.style=official` (Kotlin official style guide)
- **Compose:** Material3, `darkColorScheme()` / `lightColorScheme()` (системная тема)
- **Сериализация:** `kotlinx.serialization` (аннотации `@Serializable`, `@SerialName`)
- **Фоновые задачи:** WorkManager `CoroutineWorker` с `Result.retry()` при ошибках
- **Google Drive:** OAuth 2.0 через `Credential` + `Drive` service, scope `DRIVE_FILE`
- **Webhook:** POST с JSON-массивом `DailyHealthRecord`, Content-Type: `application/json`, таймаут 15с
- **Health Connect permissions:** запрос через `PermissionController.createRequestPermissionResultContract()` перед чтением данных
- **Формат файлов:** `health_YYYY-MM-DD.json`
- **Нет DI-фреймворка** — ручное создание зависимостей через фабрики
- **Нет отдельного Application-класса** — инициализация через AndroidX Startup
- **Нет тестов** — тестовая инфраструктура не настроена

---

## Google Drive Setup

1. Создать OAuth 2.0 Client ID (Android) в Google Cloud Console с package name `com.healthconnect.export` и SHA-1 отпечатком
2. Включить Google Drive API
3. Web client ID используется в `requestIdToken()` — это корректно

---

## Notes

- Приложение однокранное (single Activity, single Screen)
- Все строки UI — на русском языке
- Health Connect API находится в alpha-статусе (`1.1.0-alpha06`)
- Google Drive синхронизация опциональна (включается чекбоксом)
- Webhook отправка опциональна (URL + чекбокс)
- WorkManager использует `PeriodicWorkRequestBuilder` с интервалом 24 или 168 часов
