# HealthConnect Export 📱

Android-приложение для экспорта данных Health Connect в JSON формат.

## Возможности ✨

- **Ежедневный экспорт** — один день = один JSON файл (`health_YYYY-MM-DD.json`)
- **Локальное хранилище** — файлы сохраняются на устройстве
- **Автоматический экспорт** — по расписанию раз в день/неделю
- **Google Drive синхронизация** — опциональная, только при подключении
- **Выбор типов данных** — шаги, пульс, сон, калории
- **Выбор периода** — последние 7/30 дней или произвольный диапазон

## Архитектура 🏗️

```
app/
├── data/           # Data models (DailyHealthRecord, ExportConfig)
├── repository/     # HealthConnect, LocalExport, GoogleDrive
├── worker/         # DailyExportWorker (WorkManager)
├── viewmodel/      # ExportViewModel
├── ui/             # Jetpack Compose screens
└── theme/          # Material3 theme
```

## CI/CD 🚀

- Автоматическая сборка APK на каждый push
- Debug и Release APK в артефактах GitHub Actions

## Сборка локально 🔧

```bash
# Через Gradle CLI
gradle assembleDebug

# Через Android Studio
# File → Open → Выбрать папку проекта → Build → Make Project
```

## Настройка Google Drive 🔐

1. [Google Cloud Console](https://console.cloud.google.com/)
2. Создать OAuth 2.0 Client ID (Android)
3. Добавить `google-services.json` в `app/`
4. Включить Google Drive API

## Лицензия 📄

MIT
