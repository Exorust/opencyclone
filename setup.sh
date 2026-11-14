#!/usr/bin/env bash
set -euo pipefail

# Usage: setup.sh [--assistant] [package.apk]
#   --assistant  also make the app the assistant (long-press power opens it).

PACKAGE_ID=ai.opencyclone.standard
ADB=${ADB-adb}
# Seconds between attempts when a setting does not hold after it is written.
RETRY_DELAY=${SETUP_RETRY_DELAY-1}
ATTEMPTS=3
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ASSISTANT=0
APK=''
for arg in "$@"; do
    case "$arg" in
        --assistant) ASSISTANT=1 ;;
        *) APK=$arg ;;
    esac
done
APK=${APK:-$ROOT/android/app/build/outputs/apk/standard/debug/app-standard-debug.apk}

if [[ ! -f "$APK" ]]; then
    echo "FAILED: Package not found: $APK"
    echo 'Build it with: cd android && ./gradlew :app:assembleStandardDebug'
    exit 2
fi

if ! devices=$("$ADB" devices); then
    echo 'FAILED: Cannot list devices'
    exit 3
fi
count=$(printf '%s\n' "$devices" | awk -v serial="${ANDROID_SERIAL:-}" '
    $2 == "device" && (serial == "" || $1 == serial) { count++ }
    END { print count + 0 }')
if [[ "$count" == 0 ]]; then
    echo 'FAILED: No device found'
    exit 3
elif [[ "$count" != 1 ]]; then
    echo 'FAILED: More than one device; set ANDROID_SERIAL'
    exit 3
fi
echo 'ok: Device connected'

if ! sdk=$("$ADB" shell getprop ro.build.version.sdk); then
    echo 'FAILED: Cannot read Android version'
    exit 4
fi
sdk=${sdk//$'\r'/}
if [[ ! "$sdk" =~ ^[0-9]+$ ]] || (( 10#$sdk < 31 )); then
    echo 'FAILED: Needs Android 12 or later'
    exit 4
fi
echo 'ok: Android 12 or later'
if "$ADB" install -r "$APK"; then
    echo 'ok: Package installed'
else
    echo 'FAILED: Package installation'
    exit 1
fi

failed=0
grant() {
    local label=$1
    shift
    if "$ADB" "$@"; then
        echo "ok: $label"
    else
        echo "FAILED: $label"
        failed=1
    fi
}

# True when the colon-separated component list $1 contains component $2.
has_component() {
    case ":$1:" in
        *":$2:"*) return 0 ;;
        *) return 1 ;;
    esac
}

# Reads a setting; prints it without carriage returns, and empty for null.
read_setting() {
    local value
    value=$("$ADB" shell settings get "$1" "$2") || return 1
    value=${value//$'\r'/}
    [[ "$value" != null ]] || value=''
    printf '%s' "$value"
}

# Runs write function $2 and check function $3 up to ATTEMPTS times, because on a fresh
# installation a setting written once sometimes does not hold. A failed adb command stops at once.
write_and_check() {
    local label=$1 write=$2 check=$3 attempt
    for (( attempt = 1; attempt <= ATTEMPTS; attempt++ )); do
        if ! "$write"; then
            echo "FAILED: $label"
            failed=1
            return
        fi
        if "$check"; then
            echo "ok: $label"
            return
        fi
        (( attempt == ATTEMPTS )) || sleep "$RETRY_DELAY"
    done
    echo "FAILED: $label (the setting did not hold after $ATTEMPTS attempts)"
    failed=1
}

LISTENER="$PACKAGE_ID/ai.opencyclone.proactive.NotificationListener"
write_listener() { "$ADB" shell cmd notification allow_listener "$LISTENER"; }
check_listener() {
    local listeners
    listeners=$(read_setting secure enabled_notification_listeners) || return 1
    has_component "$listeners" "$LISTENER"
}
write_and_check 'Notification listener' write_listener check_listener

ACCESSIBILITY="$PACKAGE_ID/ai.opencyclone.accessibility.VdAccessibilityService"
write_accessibility() {
    local services
    services=$(read_setting secure enabled_accessibility_services) || return 1
    has_component "$services" "$ACCESSIBILITY" || services="${services:+$services:}$ACCESSIBILITY"
    "$ADB" shell settings put secure enabled_accessibility_services "$services" || return 1
    "$ADB" shell settings put secure accessibility_enabled 1
}
check_accessibility() {
    local services
    services=$(read_setting secure enabled_accessibility_services) || return 1
    has_component "$services" "$ACCESSIBILITY"
}
write_and_check 'Accessibility service' write_accessibility check_accessibility

grant 'Battery exemption' shell dumpsys deviceidle whitelist "+$PACKAGE_ID"
grant 'Write secure settings' shell pm grant "$PACKAGE_ID" android.permission.WRITE_SECURE_SETTINGS
grant 'Post notifications' shell pm grant "$PACKAGE_ID" android.permission.POST_NOTIFICATIONS
grant 'Microphone' shell pm grant "$PACKAGE_ID" android.permission.RECORD_AUDIO

if (( ASSISTANT )); then
    holders=''
    if "$ADB" shell cmd role add-role-holder --user 0 android.app.role.ASSISTANT "$PACKAGE_ID" &&
        holders=$("$ADB" shell cmd role get-role-holders --user 0 android.app.role.ASSISTANT) &&
        [[ "${holders//$'\r'/}" == *"$PACKAGE_ID"* ]]; then
        echo 'ok: Assistant role'
    else
        echo 'FAILED: Assistant role. This build of the app cannot be the assistant yet; the other permissions are not affected.'
        failed=1
    fi
    # 5 = long-press power opens the assistant.
    write_power() { "$ADB" shell settings put global power_button_long_press 5; }
    check_power() { [[ $(read_setting global power_button_long_press) == 5 ]]; }
    write_and_check 'Long-press power opens the assistant' write_power check_power
fi

if (( failed )); then
    exit 5
fi
echo 'Done'
