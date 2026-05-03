# HealthConnect Export 📱

Android-приложение для экспорта данных Health Connect в JSON формат.

## Возможности ✨

- **Ежедневный экспорт** — один день = один JSON файл (`health_YYYY-MM-DD.json`)
- **Локальное хранилище** — файлы сохраняются на устройстве
- **Автоматический экспорт** — по расписанию раз в день/неделю
- **Google Drive синхронизация** — опциональная, только при подключении
- **Webhook отправка** — POST-запрос с JSON массивом записей на указанный URL
- **Выбор типов данных** — шаги, пульс, сон, калории
- **Выбор периода** — последние 7/30 дней или произвольный диапазон

## Архитектура 🏗️

```
app/
├── data/           # Data models (DailyHealthRecord, ExportConfig)
├── repository/     # HealthConnect, LocalExport, GoogleDrive, Webhook
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
# Только сборка
./build

# Сборка, установка на устройство и запуск
./build --run

# Выгрузка JSON-файлов экспорта с устройства
./build --pull
```

## Настройка Google Drive 🔐

1. [Google Cloud Console](https://console.cloud.google.com/)
2. Создать OAuth 2.0 Client ID (Android) с package name `com.healthconnect.export` и SHA-1 отпечатком
3. Включить Google Drive API

## Webhook 📡

После экспорта можно отправить JSON-массив записей на произвольный URL через POST-запрос:

- URL указывается в интерфейсе приложения
- Content-Type: `application/json`
- Таймаут: 15 секунд
- Включение/отключение через чекбокс

## Лицензия 📄

MIT
