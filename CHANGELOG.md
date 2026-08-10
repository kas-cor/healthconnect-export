# Changelog

All notable changes to HealthConnect Export will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.7] — 2026-08-10

### Added
- **Update check:** New version check against GitHub Releases with a notification dot in the app bar; `What's new` dialog shows the latest release notes, with a download button
- **Tabbed navigation:** App redesigned with a bottom navigation bar (Export / History / Integrations / Schedule / Settings)
- **Google Drive auto sign-in:** Previous session is restored silently at app start — no extra "Sign in to Google" tap; a session-only signed-out flag prevents silent reconnection after an explicit sign-out
- **History:** "Show all files (N)" toggle reveals every exported file (all rows clickable), rendered lazily with a bounded-height list; card header now shows total file count and combined size
- **Settings tab:** Theme (system/light/dark) and language selection moved to a dedicated Settings screen; About card with GitHub link and update check
- **Tests:** silent sign-in / signed-out flag / update check / release notes / file-slicing tests (ExportViewModelTest, ExportedFilesCardTest)

### Changed
- **Drive status:** `ExportViewModel` now collects `DriveManager` state, so the connection status is correct immediately at launch
- **Schedule:** No "Scheduled export set" snackbar at every app start — confirmation appears only on explicit action
- **History:** Shows the newest 10 files with an expand toggle (previously a fixed list with "… and N more")

### Fixed
- **"Show all files" button:** The `%1$d` placeholder now shows the actual file count

---

## [1.6] — 2026-07-17

### Added
- **Build script (`build.sh`):** Multi-purpose CLI with `--run`, `--release`, `--install`, `--pull`, `--test`, `--logs`, `--help` flags
- **Russian README (`README.ru.md`):** Full 437-line translation of README into Russian
- **README sync validator:** `scripts/locale-validator.py` now checks section alignment between `README.md` and `README.ru.md`
- **English badge in README.ru.md:** Symmetric `[![English](README.md)](README.md)` link

### Changed
- **README.md:** Added Russian badge, `build.sh` documentation table, step-by-step translation guide for new locales
- **AGENTS.md:** Updated build command references from `./build` to `./build.sh`

---

## [1.5.1] — 2026-06-15

### Added
- **Full localization via `stringResource()`:** All hardcoded English strings in UI components replaced with `stringResource()` calls:
  - `DataTypeCard` — 25+ strings (categories, data type names, buttons, content descriptions)
  - `DateRangeCard` — export period labels, date picker, 30-day limit warning
  - `DriveStatusCard` — connection status, sync state, sign-in/sync/sign-out buttons
  - `ScheduleCard` — scheduling mode labels, enable/disable, every-2-hours checkbox
  - `ExportScreen` — progress page counter ("page" / "стр.")
- **Russian localization section in README:** Full documentation section in Russian for Russian-speaking users
- **Locale validation script:** `scripts/locale-validator.py` added for CI
- **Locale validation job in CI:** New `locale` workflow step that validates translations on every push

### Changed
- **String length optimization:** Shortened `health_connect_access_limit` and `every_2_hours_webhook` to prevent layout breaking on narrow screens
- **ExportScreen:** `ExportScreen` refactored to use separate manager classes (`DriveManager`, `WebhookManager`, `ScheduleManager`) and `ExportDataUseCase` — cleaner MVVM
- **HealthConnectRepository:** TypeHandler pattern introduced for batch data type processing, `onPageProgress` callback, protection against 100+ page pagination loops
- **UI:** File refresh now reads from disk after export to show all files immediately
- **Tests:** `DateRangeCardTest` added — 8 Compose UI tests for presets, custom dates, date picker interaction; total test count **262 → 323**
- **Docs:** AGENTS.md fully synced with current architecture, README.md localization section updated

### Fixed
- **Git stash corruption:** Restored 5 files corrupted by `git stash pop` during development (`HealthConnectRepository.kt`, `DailyExportWorker.kt`, `ExportViewModel.kt`, `ExportScreen.kt`, `ExportViewModelTest.kt`)
- **Hardcoded Russian string:** `"стр."` → `stringResource(R.string.export_progress_page)` in `ExportScreen.kt`
- **Stale files:** Cleaned up orphaned `HealthConnectRepositoryTest.kt`

### Removed
- `HealthConnectRepositoryTest.kt` (53 tests) — old API (`readPeriod`) replaced by day-by-day `readDay` loop

## [1.5] — 2026-06-08

### Added
- **Every-2-hours webhook:** New `Every2HoursWebhookWorker` that reads today's data and sends to webhook every 2 hours (no local save, no Drive sync)
- **Test webhook button:** Manual test of webhook connection with success/failure feedback
- **File sorting descending:** Exported files list now shows newest files first (by `lastModified()`)
- **Health Connect access limit warning:** Info text in DateRangeCard about 30-day data access limit for Health Connect
- **Tests:** `Every2HoursWebhookWorkerTest` — 18 tests covering happy path, blank URL, exceptions, scheduling, constants
- **HealthConnectRepository improvements:** Logging, `onPageProgress` callback, protection against 100+ page pagination loops

### Fixed
- **Webhook URL validation:** `setAutoSendWebhookEvery2Hours()` now checks for blank webhook URL before enabling with a helpful message
- **Schedule cancellation:** `DailyExportWorker.cancel()` now also cancels the 2-hour webhook worker (was orphaned before)

### Dependencies
- Kotlin `2.1.20` → `2.3.21`
- Gradle wrapper `9.4.1` → `9.5.1`
- `core-ktx` `1.12.0` → `1.18.0`
- `lifecycle-viewmodel-compose` `2.7.0` → `2.10.0`
- `play-services-auth` `21.0.0` → `21.6.0`
- `work-runtime-ktx` `2.9.0` → `2.11.2`
- `kotlinx-serialization-json` `1.6.2` → `1.11.0`
- `mockito-core` `5.11.0` → `5.23.0`
- `mockito-kotlin` `5.11.0` → `5.23.0`
- `ktlint` `12.1.0` → `14.2.0`
- `androidx.test.ext:junit` `1.1.5` → `1.3.0`
- CI actions: `setup-python` v5→v6, `git-auto-commit-action` v5→v7, `action-gh-release` v2→v3

### Removed
- `HealthConnectRepositoryTest.kt` (53 tests) — old API (`readPeriod`) replaced by day-by-day `readDay` loop

---

## [1.4] — 2026-06-07

### Fixed
- **Date range:** `endDate` теперь включает текущий день (LocalDate.now() вместо yesterday)
- **Presets:** 7 и 30 дней корректно включают сегодняшний день (7d = today-6..today, 30d = today-29..today)
- **DatePicker UI:** Подсветка дат в пикере обновлена под новые пресеты

---

## [1.3] — 2026-05-27

### Added
- **Webhook:** JSON payload format changed to `{"messages": [...]}` envelope via `WebhookPayload` data class
- **UI:** Export progress with per-day progress bar (read/save phases)
- **UI:** Cancel export button during active export
- **Docs:** README and AGENTS.md updated with webhook payload format specification

### Changed
- **Export:** Day-by-day reading with progress tracking instead of bulk read
- **Viewmodel:** Export cancellation support via `Job.cancel()`, progress state fields
- **Strings:** Added progress and cancel-related string resources (EN + RU)

## [1.2] — 2026-05-27

### Added
- **Tests:** `LocalExportRepositoryTest` — 24 file operation tests (save, list, cleanup, isExported)
- **Tests:** `HealthConnectRepositoryTest` — expanded from 13 to 53 tests (readDay with Sleep/Weight/Calories/Speed/Menstruation/Exercise/Hydration, pagination edge cases)
- **Tests:** `GoogleDriveRepositoryTest` — expanded with 4 edge cases (delete exception, list exception, scopes, special chars)
- **Tests:** `WebhookRepositoryTest` — rewritten from 13 to 39 tests: local HTTP server via `ServerSocket(0)` replaces `mockStatic(URL)`
- **Tests:** `ExportDataUseCaseTest` — 15 use case tests
- **Tests:** `ExportViewModelTest` — 2 new tests
- **Tests:** `DailyExportWorkerTest` — expanded from 14 to 25
- **Tests:** `LocaleManagerTest` — expanded to 12 tests
- **Tests:** Coverage improvements: `repository` ~13% → **~55%**, total 295 tests

### Changed
- **Coverage gate thresholds:** `repository` LINE minimum raised to **≥ 35%**
- **README.md:** Updated test suites table with new files and counts
- **AGENTS.md:** Synced test structure and file listings

## [1.1] — 2026-05-26

### Added
- **CI/CD:** JaCoCo coverage gate with 9 rules (4 global + 5 per-package)
- **CI/CD:** Coverage badge auto-generated on push to `main`
- **CI/CD:** ktlint check integrated into pipeline
- **Lint:** ktlint plugin added, `ktlintFormat` applied
- **Webhook:** Bearer auth support, URL validation
- **Tests:** `DataModelsSerializationTest` — 36 roundtrip serialization tests
- **Tests:** `DailyExportWorkerTest` — 14 WorkManager tests
- **Tests:** `HumanReadableMapperTest` — expanded to 33 tests
- **Docs:** README updated with coverage gate, badge, CI/CD pipeline
- **Docs:** `AGENTS.md` rewritten with full project documentation
- **Docs:** `CHANGELOG.md` — this file

### Changed
- **API upgrade:** Android API 37, Gradle 9.4.1, AGP 9.2.0, Kotlin 2.3.21
- **UI:** Jetpack Compose UI refactor, themes, animations
- **Build:** JaCoCo CSV report enabled for badge generator

### Fixed
- **Data:** Pagination fixes in `readAllPages`, `Double?` safety in SpeedData
- **Fixes:** 17 Health Connect API fixes, MIUI permission workarounds, `readWeight` fix

### Localization
- **Russian (ru):** UI strings translated

## [1.0] — Initial release

### Added
- Daily JSON export of 20 Health Connect data types
- Local storage in `health_YYYY-MM-DD.json` format
- Scheduled export via WorkManager (daily / weekly)
- Google Drive sync with OAuth 2.0
- Webhook delivery (POST JSON to URL)
- Date range selection (7/30 days or custom)
- Material3 UI with Jetpack Compose
- CI/CD: lint, unit tests, JaCoCo coverage, debug & release APKs
- GitHub Release with auto-changelog

---

[1.7]: https://github.com/kas-cor/healthconnect-export/releases/tag/v1.7
[1.6]: https://github.com/kas-cor/healthconnect-export/releases/tag/v1.6
[1.5.1]: https://github.com/kas-cor/healthconnect-export/releases/tag/v1.5.1
[1.5]: https://github.com/kas-cor/healthconnect-export/releases/tag/v1.5
[1.4]: https://github.com/kas-cor/healthconnect-export/releases/tag/v1.4
[1.3]: https://github.com/kas-cor/healthconnect-export/releases/tag/v1.3
[1.2]: https://github.com/kas-cor/healthconnect-export/releases/tag/v1.2
[1.1]: https://github.com/kas-cor/healthconnect-export/releases/tag/v1.1
[1.0]: https://github.com/kas-cor/healthconnect-export/releases/tag/v1.0
