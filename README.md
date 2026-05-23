# HealthConnect Export 📱

Android app for exporting Google Health Connect data to JSON format with optional Google Drive sync and webhook delivery.

## Features ✨

- **Daily export** — one day = one JSON file (`health_YYYY-MM-DD.json`)
- **Local storage** — files saved on device
- **Scheduled export** — daily or weekly via WorkManager
- **Google Drive sync** — optional, auto-sync to Drive
- **Webhook delivery** — POST JSON to a URL (with optional Bearer auth)
- **21 data types** — steps, heart rate, sleep, calories, exercise, nutrition, and more
- **Date range selection** — last 7/30 days or custom range
- **Manual + automatic** — export on demand or on schedule

## Architecture 🏗️

```
MainActivity → ExportScreen (Compose) → ExportViewModel
  ├─ HealthConnectRepository  — read Health Connect API
  ├─ LocalExportRepository    — save JSON files
  ├─ GoogleDriveRepository    — sync to Drive
  └─ WebhookRepository        — POST to webhook
  └─ DailyExportWorker        — scheduled background export
```

## Quick start 🚀

```bash
# Debug build
./build

# Build, install, launch
./build --run

# Pull exported JSON files
./build --pull

# Direct gradle
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Testing 🧪

```bash
# Run all unit tests
./gradlew testDebugUnitTest

# Coverage report
./gradlew jacocoTestReport
# Open: app/build/reports/jacoco/jacocoTestReport/html/index.html
```

**Test suites:**
- `HumanReadableMapperTest` — JSON serialization with human-readable constants
- `WebhookRepositoryTest` — 13 test cases for URL validation

## CI/CD 🚀

Automated builds on every push to `main`:
- Lint → unit tests → coverage → debug APK
- On tag push (`v*`): release APK + GitHub Release

[Workflow](.github/workflows/build-apk.yml)

## Google Drive setup 🔐

1. [Google Cloud Console](https://console.cloud.google.com/)
2. Create OAuth 2.0 Client ID (Android)
3. Package: `com.healthconnect.export` + SHA-1
4. Enable Google Drive API
5. Scope: `DRIVE_FILE`

## Webhook 📡

After export, JSON can be sent to any URL:
- URL + optional Bearer token in UI
- `Content-Type: application/json`
- 15s timeout
- URL validation with red highlight

## Scheduling ⏰

- **Manual**, **Daily** (24h), or **Weekly** (168h)
- Default: Daily (auto-enabled at startup)
- Uses WorkManager `PeriodicWorkRequest`

## Tech stack

| Component | Version |
|---|---|
| Kotlin | 2.1.20 |
| Compose / Material3 | BOM 2024.02 |
| AGP / Gradle | 9.1.1 / 9.4.1 |
| Health Connect | 1.1.0 |
| minSdk / targetSdk | 28 / 36 |
| JVM | 21 |
| Testing | JUnit 4.13.2, Mockito 5.11.0 |
| Coverage | JaCoCo 0.8.11 |

## License 📄

MIT
