# HealthConnect Export — Project Documentation

## Overview

**HealthConnect Export** — Android-приложение для экспорта данных Google Health Connect в JSON-формат с опциональной синхронизацией на Google Drive и отправкой на webhook.

### Core scenario

User selects health data types and a date range, then exports to JSON files (one per day). Files can be auto-synced to Google Drive and/or sent to a webhook URL. Scheduled daily/weekly exports via WorkManager.

---

## Tech Stack

| Component | Technology |
|---|---|
| Language | Kotlin 2.1.20 |
| UI | Jetpack Compose + Material3 |
| Build | Gradle KTS + AGP 9.1.1 |
| Health API | `androidx.health.connect:connect-client:1.1.0` |
| Google Drive | `google-api-services-drive:v3`, `google-http-client-gson` |
| Auth | `play-services-auth:21.0.0` (OAuth 2.0) |
| Background | WorkManager (`work-runtime-ktx:2.9.0`) |
| Serialization | `kotlinx-serialization-json:1.6.2` |
| Testing | JUnit 4.13.2 + Mockito 5.11.0 |
| Coverage | JaCoCo 0.8.11 |
| CI | GitHub Actions |
| minSdk / targetSdk / compileSdk | 28 / 36 / 36 |
| JVM | 21 |

---

## Architecture

```
MainActivity (ComponentActivity)
  └─ ExportScreen (Compose, LazyColumn)
       └─ ExportViewModel (AndroidViewModel)
            ├─ HealthConnectRepository — read Health Connect API
            ├─ LocalExportRepository  — save JSON to device
            ├─ GoogleDriveRepository  — sync with Google Drive
            └─ WebhookRepository      — POST to webhook URL
       └─ DailyExportWorker (WorkManager, background)
```

### Project structure

```
healthconnect-export/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/com/healthconnect/export/
│       │   ├── MainActivity.kt
│       │   ├── data/
│       │   │   └── DataModels.kt       # DailyHealthRecord, ExportConfig, enums, mappers
│       │   ├── repository/
│       │   │   ├── HealthConnectRepository.kt
│       │   │   ├── LocalExportRepository.kt
│       │   │   ├── GoogleDriveRepository.kt
│       │   │   └── WebhookRepository.kt
│       │   ├── viewmodel/
│       │   │   └── ExportViewModel.kt
│       │   ├── ui/
│       │   │   ├── ExportScreen.kt
│       │   │   └── theme/
│       │   │       └── AppTheme.kt
│       │   └── worker/
│       │       └── DailyExportWorker.kt
│       ├── main/res/
│       │   ├── values/strings.xml
│       │   ├── values/themes.xml
│       │   └── xml/health_connect_permissions.xml
│       └── test/java/com/healthconnect/export/
│           ├── data/HumanReadableMapperTest.kt
│           └── repository/WebhookRepositoryTest.kt
├── build                          # Build script (bash)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── AGENTS.md                      # This file
└── README.md
```

---

## Data flow

```
UI → ViewModel → HealthConnectRepository (read records)
                → LocalExportRepository (save JSON)
                → GoogleDriveRepository (sync, optional)
                → WebhookRepository (POST, optional)
```

### JSON file format

- File name: `health_YYYY-MM-DD.json`
- One file per day
- Location: app's external files directory (`HealthConnectExport/`)

### JSON payload example

```json
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
```

---

## Unit tests

**Test files:**

| File | Tests | Scope |
|---|---|---|
| `HumanReadableMapperTest.kt` | 1 | JSON serialization with human-readable constants |
| `WebhookRepositoryTest.kt` | 13 | `isValidWebhookUrl()` URL validation |

**Run tests:**

```bash
./gradlew testDebugUnitTest
```

**Coverage:**

```bash
./gradlew jacocoTestReport
# Report: app/build/reports/jacoco/jacocoTestReport/html/index.html
```

---

## CI/CD (GitHub Actions)

Workflow: `.github/workflows/build-apk.yml`

### Triggers

- **Push** to `main`, `develop`
- **Pull Request** to `main`
- **Push tag** `v*` (e.g., `v1.1`)
- **Manual** via `workflow_dispatch`

### Build steps

1. **Checkout** + JDK 21 + Android SDK
2. **Cache** Gradle
3. **Bump version** (tag push only): updates `versionName` from tag, increments `versionCode`
4. **Commit version bump** to `main`
5. **Lint** (`lintRelease`)
6. **Unit tests** (`testDebugUnitTest`)
7. **JaCoCo coverage** (`jacocoTestReport`) → HTML/XML artifacts
8. **Debug APK** (`assembleDebug`)
9. **Decode keystore** from `KEYSTORE_BASE64` secret (tag push only)
10. **Release APK** (`assembleRelease`) with signing
11. **Rename APKs**: versioned names
12. **Upload artifacts**: Debug (7 days), Release (30 days)

### Release job (tag push)

- Downloads signed release APK
- Generates changelog from git history
- Creates GitHub Release with APK file

### GitHub Secrets

| Secret | Description |
|---|---|
| `KEYSTORE_BASE64` | `healthconnect-release.jks` in base64 |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias (default: `healthconnect`) |
| `KEY_PASSWORD` | Key password (falls back to `KEYSTORE_PASSWORD`) |

### Setup secrets in GitHub

1. Encode keystore to base64:
   ```bash
   base64 -w0 healthconnect-release.jks > healthconnect-release.jks.base64
   ```
2. Copy the entire content of `healthconnect-release.jks.base64`
3. Go to GitHub → Settings → Secrets and variables → Actions
4. Add secrets:
   - `KEYSTORE_BASE64` → paste the base64 string
   - `KEYSTORE_PASSWORD` → your keystore password
5. Delete `healthconnect-release.jks.base64` — it contains the full keystore

> **Security:** Never commit `.jks`, `.base64`, or password files to git. The `.gitignore` already excludes `*.jks`, `*.base64`, `.keystore_password.txt`.

### Google Cloud Console — Credentials

Для Google Sign-In на Android требуется **два** OAuth Client ID в одном Google Cloud Project:

| Тип | Client ID | Назначение |
|---|---|---|
| **Android** (OAuth) | `730530422387-oaffqtrvfd1rqr6jn1uq8791mgbbpmlj` | Проверка SHA-1 + package name (Google Play Services) |
| **Web application** (OAuth) | `730530422387-dveo97h089iesh4etmj74q9dn8j221f1` | `requestIdToken()` в коде (генерация ID token) |

#### SHA-1 fingerprints

Оба SHA-1 отпечатка добавлены в **Android OAuth Client ID** в Google Cloud Console:

| Build type | SHA-1 fingerprint | Команда |
|---|---|---|
| **Release** | `2A:BF:A4:CA:62:59:78:A2:5D:78:FD:74:2D:CB:CA:07:D2:37:42:72` | `keytool -list -v -keystore healthconnect-release.jks` |
| **Debug** | `8B:BB:D1:45:E2:61:5B:02:57:E1:3F:26:29:1A:AF:F0:2C:40:77:73` | `keytool -list -v -keystore ~/.android/debug.keystore -storepass android` |

#### Проверка SHA-1 из APK

```bash
# Убедиться, что APK подписан правильным ключом
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
# Должен показать: SHA-1: 2abfa4ca625978a25d78fd742dcbca07d2374272
```

**Важно:**
- `requestIdToken()` в коде использует **Web Client ID**, а Android Client ID остаётся в Google Cloud Console для верификации
- Оба Client ID должны быть в одном Google Cloud Project
- SHA-1 из `healthconnect-release.jks` (`2A:BF:A4:CA:...`) добавлен в Android Client ID

#### Настройка OAuth consent screen

1. Откройте [OAuth consent screen](https://console.cloud.google.com/apis/credentials/consent)
2. Тип: **External**
3. **Testing** (не требуется верификация)
4. Обязательно добавьте **Test users** — свой email
5. На шаге **Scopes** добавьте `https://www.googleapis.com/auth/drive.file`

#### Включённые API

- [Google Drive API](https://console.cloud.google.com/apis/library/drive.googleapis.com) — `ENABLED`
- [Identity Toolkit API](https://console.cloud.google.com/apis/library/identitytoolkit.googleapis.com) — `ENABLED`

### Release process

```bash
git tag v1.1
git push origin v1.1
# CI: bump version → build → create GitHub Release
```

---

## Build and run

```bash
# Debug build only
./build

# Build, install, launch
./build --run

# Pull exported JSON files from device
./build --pull

# Direct gradle commands
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Drive sync + webhook |
| `ACCESS_NETWORK_STATE` | Network check |
| `FOREGROUND_SERVICE` | Scheduled work |
| `RECEIVE_BOOT_COMPLETED` | Reschedule after reboot |
| `health.READ_*` | Health Connect data types (20 types) |

---

## Code conventions

- **Language**: Kotlin, official style (`code.style=official`)
- **UI**: Jetpack Compose + Material3
- **Architecture**: MVVM with manual DI (no DI framework)
- **Serialization**: `kotlinx.serialization` with `@Serializable`, `@SerialName`
- **Background**: WorkManager `CoroutineWorker`
- **Network**: `HttpURLConnection` in `Dispatchers.IO`
- **File format**: `health_YYYY-MM-DD.json`
- **UI language**: English
- **No separate Application class** — uses AndroidX Startup

---

## Scheduling

- **Manual**: Export on button press
- **Daily**: 24h interval via WorkManager `PeriodicWorkRequest`
- **Weekly**: 168h interval
- Default: **Daily** (set at app startup)
- Policy: `ExistingPeriodicWorkPolicy.KEEP`

---

## Google Drive setup

### 1. Google Cloud Console

1. Создайте **Android OAuth Client ID**:
   - Тип: **Android**
   - Package name: `com.healthconnect.export`
   - SHA-1: Release `2A:BF:A4:CA:62:59:78:A2:5D:78:FD:74:2D:CB:CA:07:D2:37:42:72`
2. Создайте **Web Application Client ID**:
   - Тип: **Web application**
   - Authorized redirect URIs: не нужны (используется для `requestIdToken`)
3. Настройте **OAuth consent screen** (External / Testing)
4. Включите **Google Drive API**
5. Включите **Identity Toolkit API**

### 2. Код

```kotlin
// GoogleDriveRepository.kt
fun getSignInOptions(): GoogleSignInOptions {
    return GoogleSignInOptions.Builder()
        .requestEmail()
        .requestIdToken("730530422387-dveo97h089iesh4etmj74q9dn8j221f1.apps.googleusercontent.com")
        .requestScopes(Scope(DriveScopes.DRIVE_FILE))
        .build()
}
```

**Важно:**
- `requestIdToken()` использует **Web Client ID** — это обязательно для Google Sign-In
- Android Client ID с SHA-1 остаётся в Google Cloud Console для верификации приложения
- Scope `DRIVE_FILE` даёт доступ к файлам, созданным этим приложением

---

## Webhook

- URL: configurable in UI
- Auth: optional Bearer token (stored in `ExportConfig`)
- Method: POST
- Content-Type: `application/json`
- Timeout: 15 seconds
- URL validation: client-side (red highlight on invalid URL)
- Works with both manual and scheduled exports
