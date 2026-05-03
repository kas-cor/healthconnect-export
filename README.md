# Health Connect Export

Android-приложение для экспорта данных из Google Health Connect в Google Drive в формате JSON.

## Возможности

- Чтение данных Health Connect: шаги, пульс, сон, калории
- Выбор периода: 7, 30, 90 дней
- Экспорт в JSON с метаданными
- Загрузка файла в Google Drive
- Jetpack Compose UI

## Требования

- Android 8.0+ (API 26)
- Установленное приложение Health Connect
- Google-аккаунт для Drive

## Настройка

1. Создай проект в Google Cloud Console
2. Включи Google Drive API
3. Добавь OAuth 2.0 credentials
4. Обнови `AndroidManifest.xml` — добавь свой `client_id` для Google Sign-In

## Сборка

```bash
./gradlew assembleDebug
```

## Структура проекта

```
app/src/main/java/com/healthconnect/export/
├── MainActivity.kt              # Точка входа
├── data/
│   └── DataModels.kt            # Модели данных
├── repository/
│   ├── HealthConnectRepository.kt  # Чтение Health Connect
│   └── GoogleDriveRepository.kt    # Запись в Drive
├── ui/
│   ├── ExportScreen.kt          # Compose UI
│   └── theme/AppTheme.kt        # Тема
└── viewmodel/
    └── ExportViewModel.kt       # Логика экспорта
```

## Разрешения

- `android.permission.INTERNET`
- Health Connect permissions (запрашиваются в runtime)
- Google Drive OAuth scope `drive.file`
