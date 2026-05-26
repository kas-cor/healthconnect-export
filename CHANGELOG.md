# Changelog

All notable changes to HealthConnect Export will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

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

[1.1]: https://github.com/kas-cor/healthconnect-export/releases/tag/v1.1
[1.0]: https://github.com/kas-cor/healthconnect-export/releases/tag/v1.0
