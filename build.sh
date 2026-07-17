#!/usr/bin/env bash
set -euo pipefail

# ===== Настройки =====
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
APK_PATH_RELEASE="$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
GRADLE="$PROJECT_DIR/gradlew"
ADB="adb"
PACKAGE="com.healthconnect.export"
EXPORT_DIR="HealthConnectExport"

# ===== Цвета =====
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

# ===== Проверка зависимостей =====
check_deps() {
    info "Проверка зависимостей..."

    if [ ! -f "$GRADLE" ]; then
        error "gradlew не найден: $GRADLE"
        exit 1
    fi

    if ! command -v "$ADB" &>/dev/null; then
        error "adb не найден. Установите Android SDK platform-tools."
        exit 1
    fi

    ok "Все базовые зависимости на месте."
}

check_curl() {
    if ! command -v curl &>/dev/null; then
        error "curl не найден. Установите curl."
        exit 1
    fi
}

# ===== Сборка debug =====
build() {
    info "Сборка debug APK..."
    cd "$PROJECT_DIR"
    chmod +x "$GRADLE"
    "$GRADLE" assembleDebug --no-daemon 2>&1
    ok "Сборка завершена."
}

# ===== Сборка release =====
build_release() {
    info "Сборка release APK..."

    local keystore="$PROJECT_DIR/healthconnect-release.jks"
    local sign_with_debug=false

    if [ -f "$keystore" ] && [ -n "${KEYSTORE_PASSWORD:-}" ]; then
        info "Подпись продакшн-ключом: $keystore"
    elif [ -f "$keystore" ] && [ -z "${KEYSTORE_PASSWORD:-}" ]; then
        warn "KEYSTORE_PASSWORD не задан. Буду подписывать debug-ключом Android SDK."
        sign_with_debug=true
    else
        warn "Keystore не найден. Буду подписывать debug-ключом Android SDK."
        sign_with_debug=true
    fi

    cd "$PROJECT_DIR"
    chmod +x "$GRADLE"

    if $sign_with_debug; then
        # Временно убираем keystore, чтобы build.gradle пропустил подпись
        if [ -f "$keystore" ]; then
            mv "$keystore" "${keystore}.bak"
            # Гарантируем восстановление keystore при любом исходе
            trap 'mv "${keystore}.bak" "$keystore"' EXIT
        fi

        "$GRADLE" assembleRelease --no-daemon 2>&1

        # trap восстановит keystore автоматически

        # Подпись debug-ключом Android SDK
        local debug_ks="$HOME/.android/debug.keystore"
        if [ ! -f "$debug_ks" ]; then
            error "Debug keystore не найден: $debug_ks"
            return 1
        fi

        local build_tools
        build_tools=$(ls -d "$HOME/Android/Sdk/build-tools/"* 2>/dev/null | sort -V | tail -1)
        if [ -z "$build_tools" ]; then
            error "build-tools не найдены в Android SDK."
            return 1
        fi

        local unsigned="$APK_PATH_RELEASE"
        local aligned="${unsigned%.*}-aligned.apk"

        "$build_tools/zipalign" -v -p 4 "$unsigned" "$aligned" > /dev/null 2>&1
        "$build_tools/apksigner" sign \
            --ks "$debug_ks" \
            --ks-pass pass:android \
            --ks-key-alias androiddebugkey \
            --key-pass pass:android \
            "$aligned" 2>&1

        ok "Release APK подписан debug-ключом: $aligned"
    else
        # Продакшн-подпись — пароль уже в переменной окружения
        "$GRADLE" assembleRelease --no-daemon 2>&1
        ok "Release APK подписан продакшн-ключом: $APK_PATH_RELEASE"
    fi
}

# ===== Проверка APK =====
check_apk() {
    local apk="${1:-$APK_PATH}"
    if [ ! -f "$apk" ]; then
        error "APK не найден: $apk"
        exit 1
    fi
    local size
    size=$(du -h "$apk" | cut -f1)
    ok "APK найден: $apk ($size)"
}

# ===== Проверка устройств =====
check_device() {
    info "Поиск подключённых устройств..."
    local devices
    devices=$("$ADB" devices 2>/dev/null | grep -v "^List" | grep -v "^$" || true)

    if [ -z "$devices" ]; then
        error "Нет подключённых устройств."
        echo ""
        echo "  Подключите устройство по USB и включите отладку по USB,"
        echo "  или запустите эмулятор."
        echo ""
        echo "  Для подключения по Wi-Fi:"
        echo "    adb tcpip 5555"
        echo "    adb connect <IP_устройства>:5555"
        exit 1
    fi

    local count
    count=$(echo "$devices" | wc -l)
    ok "Найдено устройств: $count"
    echo "$devices" | while read -r line; do
        echo "  → $line"
    done
}

# ===== Установка APK =====
install_apk() {
    local apk="${1:-$APK_PATH}"
    info "Установка APK на устройство..."
    "$ADB" install -r "$apk" 2>&1
    ok "APK установлен."
}

# ===== Запуск приложения =====
launch_app() {
    info "Запуск приложения..."
    "$ADB" shell am start -n "$PACKAGE/.MainActivity" 2>&1
    ok "Приложение запущено."
}

# ===== Вытянуть экспортированные JSON-файлы =====
pull_exports() {
    info "Поиск экспортированных JSON-файлов на устройстве..."

    local remote_base
    remote_base=$("$ADB" shell "echo \$EXTERNAL_STORAGE" 2>/dev/null | tr -d '\r\n')
    if [ -z "$remote_base" ]; then
        remote_base="/sdcard"
    fi

    local remote_dir="$remote_base/Android/data/$PACKAGE/files/$EXPORT_DIR"

    # Проверяем, существует ли директория
    local exists
    exists=$("$ADB" shell "test -d '$remote_dir' && echo 1 || echo 0" 2>/dev/null | tr -d '\r\n')

    if [ "$exists" != "1" ]; then
        error "Директория $remote_dir не найдена на устройстве."
        info "Сначала выполните экспорт в приложении."
        exit 1
    fi

    # Создаём локальную директорию для экспорта
    local local_dir="$PROJECT_DIR/exports"
    mkdir -p "$local_dir"

    info "Вытягиваю файлы из: $remote_dir"
    info "В локальную папку: $local_dir"
    echo ""

    "$ADB" pull "$remote_dir/." "$local_dir" 2>&1

    local count
    count=$(find "$local_dir" -name 'health_*.json' 2>/dev/null | wc -l)

    echo ""
    if [ "$count" -gt 0 ]; then
        ok "Вытянуто файлов: $count"
        echo ""
        ls -lh "$local_dir"/*.json 2>/dev/null || true
    else
        warn "JSON-файлы не найдены. Экспорт пуст."
    fi
}

# ===== Отправка тестового POST на webhook =====
send_test() {
    info "Отправка тестового POST на webhook..."

    if [ $# -lt 1 ]; then
        error "Укажите URL webhook'а: $0 --test https://example.com/webhook"
        exit 1
    fi

    check_curl

    local url="$1"
    local token="${2:-}"

    local today
    today=$(date +"%Y-%m-%d")
    local ts_iso
    ts_iso=$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")

    # Формируем payload с тестовыми данными Health Connect
    local payload
    payload=$(printf '{
  "messages": [
    {
      "date": "%s",
      "steps": { "total_steps": 8754, "records_count": 320 },
      "heart_rate": { "avg_bpm": 72.5, "min_bpm": 55, "max_bpm": 142, "records_count": 18 },
      "sleep": {
        "total_duration_minutes": 420,
        "sleep_stages": { "Deep sleep": 90, "Light sleep": 195, "REM sleep": 105, "Awake": 30 },
        "records_count": 1
      },
      "calories": { "total_calories_kcal": 2150.0, "records_count": 1 },
      "distance": { "total_distance_meters": 6240.0, "records_count": 1 },
      "metadata": {
        "app_version": "1.5.5",
        "export_timestamp": "%s",
        "timezone": "Europe/Moscow",
        "source_device": "test_script"
      }
    }
  ]
}' "$today" "$ts_iso")

    local http_code
    if [ -n "$token" ]; then
        http_code=$(curl -s -o /dev/null -w "%{http_code}" \
            -X POST "$url" \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $token" \
            -d "$payload")
    else
        http_code=$(curl -s -o /dev/null -w "%{http_code}" \
            -X POST "$url" \
            -H "Content-Type: application/json" \
            -d "$payload")
    fi

    if [ "$http_code" -eq 200 ] || [ "$http_code" -eq 201 ] || [ "$http_code" -eq 204 ]; then
        ok "Тестовый POST отправлен. HTTP $http_code"
    else
        warn "Тестовый POST ответил HTTP $http_code"
    fi
}

# ===== Просмотр логов (logcat) =====
show_logs() {
    info "Логи HealthConnect Export (logcat). Для выхода: Ctrl+C"
    echo ""
    "$ADB" logcat -v time \
        | grep -E "(ExportScreen|ExportViewModel|HealthConnect|MainActivity|AndroidRuntime|$PACKAGE|ExportDataUseCase|DriveManager|WebhookManager)" \
        --line-buffered
}

# ===== Справка =====
usage() {
    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  HealthConnect Export — Build Tool   ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════╝${NC}"
    echo ""
    echo "Использование: $0 [флаги]"
    echo ""
    echo "Флаги:"
    echo "  --run               Собрать debug APK, установить и запустить"
    echo "  --release           Собрать release APK (с подписью)"
    echo "  --install [apk]     Установить APK (по умолч. debug)"
    echo "  --pull              Вытянуть экспортированные JSON-файлы с устройства"
    echo "  --test <url> [токен] Отправить тестовый POST на webhook"
    echo "  --logs              Показать logcat, отфильтрованный по приложению"
    echo ""
    echo "Без флагов — только сборка debug APK."
    echo ""
}

# ===== Главный процесс =====
main() {
    local do_run=false
    local do_release=false
    local do_install=""
    local do_pull=false
    local do_test=""
    local do_test_token=""
    local do_logs=false

    if [ $# -eq 0 ]; then
        build
        exit 0
    fi

    while [ $# -gt 0 ]; do
        case "$1" in
            --run)
                do_run=true
                shift
                ;;
            --release)
                do_release=true
                shift
                ;;
            --install)
                shift
                if [ $# -gt 0 ] && [[ "$1" != --* ]]; then
                    do_install="$1"
                    shift
                else
                    do_install="$APK_PATH"
                fi
                ;;
            --pull)
                do_pull=true
                shift
                ;;
            --test)
                shift
                if [ $# -gt 0 ] && [[ "$1" != --* ]]; then
                    do_test="$1"
                    shift
                else
                    error "--test требует URL"
                    exit 1
                fi
                if [ $# -gt 0 ] && [[ "$1" != --* ]]; then
                    do_test_token="$1"
                    shift
                fi
                ;;
            --logs)
                do_logs=true
                shift
                ;;
            --help|-h)
                usage
                exit 0
                ;;
            *)
                error "Неизвестный флаг: $1"
                usage
                exit 1
                ;;
        esac
    done

    # --run: build + install + launch
    if $do_run; then
        echo ""
        echo -e "${GREEN}╔══════════════════════════════════════╗${NC}"
        echo -e "${GREEN}║ HealthConnect Export — Build & Run   ║${NC}"
        echo -e "${GREEN}╚══════════════════════════════════════╝${NC}"
        echo ""
        check_deps
        echo ""
        build
        echo ""
        check_apk
        echo ""
        check_device
        echo ""
        install_apk
        echo ""
        launch_app
        echo ""
        ok "Готово! 🎉"
    fi

    # --release: build signed release APK
    if $do_release; then
        echo ""
        echo -e "${GREEN}╔══════════════════════════════════════╗${NC}"
        echo -e "${GREEN}║ HealthConnect Export — Release Build ║${NC}"
        echo -e "${GREEN}╚══════════════════════════════════════╝${NC}"
        echo ""
        check_deps
        echo ""
        build_release
        echo ""
        ok "Готово! 🎉"
    fi

    # --install: install APK
    if [ -n "$do_install" ]; then
        echo ""
        echo -e "${GREEN}╔══════════════════════════════════════╗${NC}"
        echo -e "${GREEN}║ HealthConnect Export — Install APK   ║${NC}"
        echo -e "${GREEN}╚══════════════════════════════════════╝${NC}"
        echo ""
        check_apk "$do_install"
        echo ""
        check_device
        echo ""
        install_apk "$do_install"
        echo ""
        ok "Готово! 🎉"
    fi

    # --pull: pull exported JSON files from device
    if $do_pull; then
        echo ""
        echo -e "${GREEN}╔══════════════════════════════════════╗${NC}"
        echo -e "${GREEN}║ HealthConnect Export — Pull Files    ║${NC}"
        echo -e "${GREEN}╚══════════════════════════════════════╝${NC}"
        echo ""
        check_device
        echo ""
        pull_exports
        echo ""
        ok "Готово! 🎉"
    fi

    # --test: send test POST
    if [ -n "$do_test" ]; then
        echo ""
        echo -e "${GREEN}╔══════════════════════════════════════╗${NC}"
        echo -e "${GREEN}║ HealthConnect Export — Test POST     ║${NC}"
        echo -e "${GREEN}╚══════════════════════════════════════╝${NC}"
        echo ""
        send_test "$do_test" "$do_test_token"
        echo ""
    fi

    # --logs: show logcat
    if $do_logs; then
        show_logs
    fi
}

main "$@"
