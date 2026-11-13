#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TEMP=$(mktemp -d "$ROOT/tests/setup/.tmp.XXXXXX")
trap 'rm -rf "$TEMP"' EXIT
export ADB="$TEMP/adb"
export ADB_LOG="$TEMP/adb.log"
export ADB_STATE="$TEMP/state"
export SETUP_RETRY_DELAY=0
APK="$TEMP/package with spaces.apk"
touch "$APK"
cat > "$ADB" <<'FAKE'
#!/usr/bin/env bash
# Fake adb. Settings live in files under $ADB_STATE. The first $IGNORED_WRITES writes to each
# setting are accepted but do not hold, as on a phone right after a new installation.
set -euo pipefail
printf '%s\n' "$*" >> "$ADB_LOG"
if [[ -n "${FAIL_COMMAND:-}" && "$*" == "$FAIL_COMMAND" ]]; then
    exit 1
fi
holds() {
    local count_file="$ADB_STATE/$1.writes" count=0
    [[ ! -f "$count_file" ]] || count=$(< "$count_file")
    count=$((count + 1))
    echo "$count" > "$count_file"
    (( count > ${IGNORED_WRITES:-0} ))
}
get_setting() {
    if [[ -f "$ADB_STATE/$1" ]]; then
        cat "$ADB_STATE/$1"
    else
        case "$1" in
            enabled_accessibility_services) printf '%s' "$SERVICES" ;;
            enabled_notification_listeners) printf '%s' "${LISTENERS:-null}" ;;
            *) printf 'null' ;;
        esac
    fi
}
case "$*" in
    devices) printf 'List of devices attached\n%s\n' "$DEVICES" ;;
    'shell getprop ro.build.version.sdk') printf '%s\r\n' "$SDK" ;;
    'shell settings get '*) printf '%s\r\n' "$(get_setting "$5")" ;;
    'shell settings put '*) if holds "$5"; then printf '%s' "$6" > "$ADB_STATE/$5"; fi ;;
    'shell cmd notification allow_listener '*)
        if holds enabled_notification_listeners; then
            current=$(get_setting enabled_notification_listeners)
            [[ "$current" != null ]] || current=''
            case ":$current:" in
                *":$5:"*) ;;
                *) printf '%s' "${current:+$current:}$5" > "$ADB_STATE/enabled_notification_listeners" ;;
            esac
        fi ;;
    'shell cmd role add-role-holder '*)
        [[ -z "${NO_ASSISTANT_COMPONENT:-}" ]] || { echo 'Error: package cannot hold the role' >&2; exit 1; }
        printf '%s' "$8" > "$ADB_STATE/assistant" ;;
    'shell cmd role get-role-holders '*) [[ ! -f "$ADB_STATE/assistant" ]] || printf '%s\r\n' "$(< "$ADB_STATE/assistant")" ;;
    install\ -r\ *) [[ $# == 3 && -f "$3" ]] ;;
esac
FAKE
chmod +x "$ADB"

failures=0
component=ai.opencyclon.standard/ai.opencyclon.accessibility.VdAccessibilityService
reset_case() {
    export DEVICES=$'phone\tdevice'
    export SDK=31 SERVICES=null FAIL_COMMAND='' IGNORED_WRITES=0
    unset ANDROID_SERIAL LISTENERS NO_ASSISTANT_COMPONENT
    : > "$ADB_LOG"
    rm -rf "$ADB_STATE"
    mkdir "$ADB_STATE"
}
# run_setup [package] [more options]
run_setup() {
    status=0
    bash "$ROOT/setup.sh" "${1:-$APK}" "${@:2}" > "$TEMP/output" 2>&1 || status=$?
}
count_logged() { grep -Fxc -- "$1" "$ADB_LOG" || true; }
contains() { grep -Fq -- "$1" "$TEMP/output"; }
logged() { grep -Fxq -- "$1" "$ADB_LOG"; }
no_install() { ! grep -q '^install ' "$ADB_LOG"; }
result() {
    if "$@"; then
        echo "PASS: $case_name"
    else
        echo "FAIL: $case_name"
        cat "$TEMP/output"
        failures=$((failures + 1))
    fi
}

reset_case
case_name='no device'
DEVICES=''
run_setup
check() { [[ $status == 3 ]] && contains 'No device found' && no_install; }
result check

reset_case
case_name='multiple devices'
DEVICES=$'phone\tdevice\nother\tdevice'
run_setup
check() { [[ $status == 3 ]] && contains 'More than one device; set ANDROID_SERIAL' && no_install; }
result check

reset_case
case_name='ANDROID_SERIAL selects one device'
DEVICES=$'phone\tdevice\nother\tdevice'
export ANDROID_SERIAL=other
run_setup
check() { [[ $status == 0 ]] && contains Done; }
result check

reset_case
case_name='unauthorized device'
DEVICES=$'phone\tunauthorized'
run_setup
check() { [[ $status == 3 ]] && no_install; }
result check

reset_case
case_name='SDK 30 does not install'
SDK=30
run_setup
check() { [[ $status == 4 ]] && contains 'Needs Android 12 or later' && no_install; }
result check

reset_case
case_name='normal run grants each permission once'
run_setup
check() {
    [[ $status == 0 ]] && contains Done || return 1
    # The floating control is gone: no overlay grant.
    ! grep -q SYSTEM_ALERT_WINDOW "$ADB_LOG" || return 1
    local command
    while IFS= read -r command; do
        [[ $(grep -Fxc -- "$command" "$ADB_LOG") == 1 ]] || return 1
    done <<COMMANDS
install -r $APK
shell cmd notification allow_listener ai.opencyclon.standard/ai.opencyclon.proactive.NotificationListener
shell settings put secure enabled_accessibility_services $component
shell settings put secure accessibility_enabled 1
shell dumpsys deviceidle whitelist +ai.opencyclon.standard
shell pm grant ai.opencyclon.standard android.permission.WRITE_SECURE_SETTINGS
shell pm grant ai.opencyclon.standard android.permission.POST_NOTIFICATIONS
shell pm grant ai.opencyclon.standard android.permission.RECORD_AUDIO
COMMANDS
}
result check

reset_case
case_name='existing accessibility services preserved'
SERVICES=other.package/OtherService
run_setup
check() { [[ $status == 0 ]] && logged "shell settings put secure enabled_accessibility_services $SERVICES:$component"; }
result check

case_name='second run does not duplicate accessibility service'
SERVICES="$SERVICES:$component:third.package/ThirdService"
: > "$ADB_LOG"
rm -f "$ADB_STATE"/*
run_setup
check() { [[ $status == 0 ]] && contains Done && logged "shell settings put secure enabled_accessibility_services $SERVICES"; }
result check

reset_case
case_name='missing package'
run_setup "$TEMP/missing.apk"
check() { [[ $status == 2 ]] && contains ':app:assembleStandardDebug' && [[ ! -s "$ADB_LOG" ]]; }
result check

reset_case
case_name='failed grant continues and exits 5'
FAIL_COMMAND='shell cmd notification allow_listener ai.opencyclon.standard/ai.opencyclon.proactive.NotificationListener'
run_setup
check() {
    [[ $status == 5 ]] && contains 'FAILED: Notification listener' && ! contains Done &&
        logged 'shell pm grant ai.opencyclon.standard android.permission.POST_NOTIFICATIONS'
}
result check

reset_case
case_name='failed accessibility read preserves settings and continues'
FAIL_COMMAND='shell settings get secure enabled_accessibility_services'
run_setup
check() {
    [[ $status == 5 ]] && contains 'FAILED: Accessibility service' &&
        ! grep -q '^shell settings put ' "$ADB_LOG" &&
        logged 'shell pm grant ai.opencyclon.standard android.permission.POST_NOTIFICATIONS'
}
result check

reset_case
case_name='failed accessibility write reports failure'
FAIL_COMMAND="shell settings put secure enabled_accessibility_services $component"
run_setup
check() { [[ $status == 5 ]] && contains 'FAILED: Accessibility service'; }
result check

reset_case
case_name='failed install stops before grants'
FAIL_COMMAND="install -r $APK"
run_setup
check() { [[ $status == 1 ]] && contains 'FAILED: Package installation' && ! grep -q '^shell cmd notification' "$ADB_LOG"; }
result check

reset_case
case_name='microphone grant reports ok'
run_setup
check() { [[ $status == 0 ]] && contains 'ok: Microphone' && contains Done; }
result check

reset_case
case_name='failed microphone grant reports FAILED, keeps the other grants, and exits 5'
FAIL_COMMAND='shell pm grant ai.opencyclon.standard android.permission.RECORD_AUDIO'
run_setup
check() {
    [[ $status == 5 ]] && contains 'FAILED: Microphone' && ! contains Done &&
        contains 'ok: Post notifications' && contains 'ok: Accessibility service'
}
result check

listener=ai.opencyclon.standard/ai.opencyclon.proactive.NotificationListener

reset_case
case_name='settings that do not hold on the first write are written again'
IGNORED_WRITES=1
run_setup
check() {
    [[ $status == 0 ]] && contains 'ok: Accessibility service' && contains 'ok: Notification listener' && contains Done &&
        [[ $(count_logged "shell settings put secure enabled_accessibility_services $component") == 2 ]] &&
        [[ $(count_logged "shell cmd notification allow_listener $listener") == 2 ]]
}
result check

reset_case
case_name='settings that never hold report FAILED after three attempts'
IGNORED_WRITES=99
run_setup
check() {
    [[ $status == 5 ]] && contains 'FAILED: Accessibility service (the setting did not hold' &&
        contains 'FAILED: Notification listener (the setting did not hold' && ! contains Done &&
        [[ $(count_logged "shell settings put secure enabled_accessibility_services $component") == 3 ]] &&
        [[ $(count_logged "shell cmd notification allow_listener $listener") == 3 ]] &&
        logged 'shell pm grant ai.opencyclon.standard android.permission.POST_NOTIFICATIONS'
}
result check

reset_case
case_name='listener already enabled by another app is kept'
export LISTENERS=other.package/OtherListener
run_setup
check() {
    [[ $status == 0 ]] && contains 'ok: Notification listener' &&
        [[ $(< "$ADB_STATE/enabled_notification_listeners") == "other.package/OtherListener:$listener" ]]
}
result check

reset_case
case_name='without --assistant the role is not touched'
run_setup
check() { [[ $status == 0 ]] && ! grep -q 'role' "$ADB_LOG" && ! grep -q power_button_long_press "$ADB_LOG"; }
result check

reset_case
case_name='--assistant sets the role and long-press power, twice in a row'
run_setup "$APK" --assistant
first=$status
run_setup --assistant "$APK"
check() {
    [[ $first == 0 && $status == 0 ]] && contains 'ok: Assistant role' &&
        contains 'ok: Long-press power opens the assistant' && contains Done &&
        logged 'shell cmd role add-role-holder --user 0 android.app.role.ASSISTANT ai.opencyclon.standard' &&
        logged 'shell settings put global power_button_long_press 5'
}
result check

reset_case
case_name='--assistant without a qualifying component explains and keeps the other grants'
export NO_ASSISTANT_COMPONENT=1
run_setup "$APK" --assistant
check() {
    [[ $status == 5 ]] && contains 'FAILED: Assistant role. This build of the app cannot be the assistant yet' &&
        contains 'ok: Accessibility service' && contains 'ok: Notification listener' &&
        logged 'shell pm grant ai.opencyclon.standard android.permission.POST_NOTIFICATIONS' &&
        contains 'ok: Long-press power opens the assistant'
}
result check

[[ $failures == 0 ]]
