#!/usr/bin/env bash
# Walk harness: drive the debug build on a real phone with a scripted model server on this Mac.
#
#   walk.sh setup                       start the mock server, adb reverse, open the app, point it at the mock
#   walk.sh list                        list the scenarios in scenarios.json
#   walk.sh run <scenario> [--out DIR] [--timeout SECONDS]
#   walk.sh all [--out DIR]             run every scenario, one evidence folder each
#   walk.sh state                       print the app state (engine, tasks, approval, window, display)
#   walk.sh approve | reject | stop
#   walk.sh answer <text> | edit <text> | start <text>
#   walk.sh suggestions on|off         enable or disable notification suggestions
#   walk.sh notify <package> <title> <text>
#   walk.sh voice_selftest [text]       platform voice -> the downloaded-model recognizer on the phone; prints JSON
#   walk.sh voice_selftest_android      the phone's own recognizer: services, choice, a 3 s listen; prints JSON
#   walk.sh voice_engine android|phone_model|service [--voice-model ID]   choose the voice engine
#   walk.sh voice_selftest_service [text]  the internet recognizer against the mock server; checks one request
#   walk.sh voice_mictest [ms]          open the microphone as listening does; prints the level JSON
#   walk.sh teardown                    stop the mock server and remove the adb reverse
#   walk.sh look <screen>|all [--out DIR]   look check of the screen in front (look.py); --list, --from DIR
#   walk.sh list2 | run2 <scenario> | all2   step scenarios of scenarios_v2.json (lane X2)
#   walk.sh confirm [--item last|draft|ID] [--no] | page home|history|widgets | app_seed <name> [--data JSON]
#   walk.sh widget_seed <name> --html FILE [--size small] [--sources a,b]
#
# Environment: WALK_PORT (8765), WALK_PACKAGE (ai.opencyclon.standard), ANDROID_SERIAL, ADB (adb), PYTHON (python3),
# WALK_SERVER_MAX_SECONDS (14400: the mock server exits by itself after this even if teardown never runs).
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PORT="${WALK_PORT:-8765}"
PACKAGE="${WALK_PACKAGE:-ai.opencyclon.standard}"
ADB="${ADB:-adb}"
PY="${PYTHON:-python3}"
RUN_DIR="$HERE/.run"
PIDFILE="$RUN_DIR/mock.pid"
SERVER_LOG="$RUN_DIR/mock.log"
MAX_SECONDS="${WALK_SERVER_MAX_SECONDS:-14400}"
export WALK_PORT="$PORT" WALK_PACKAGE="$PACKAGE" ADB

die() { echo "walk: $*" >&2; exit 1; }

need_device() {
  local state
  state="$("$ADB" get-state 2>/dev/null)" || die "no device: connect the phone and allow USB debugging"
  [ "$state" = "device" ] || die "device state is '$state', expected 'device'"
}

server_pid() { [ -f "$PIDFILE" ] && cat "$PIDFILE"; }

server_alive() {
  local pid
  pid="$(server_pid)" || return 1
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

server_healthy() { curl -sf --max-time 2 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; }

stop_server() {
  local pid end
  pid="$(server_pid)" || return 0
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null
    end=$((SECONDS + 5))
    while kill -0 "$pid" 2>/dev/null && [ "$SECONDS" -lt "$end" ]; do sleep 0.2; done
    kill -9 "$pid" 2>/dev/null
    echo "mock server stopped (pid $pid)"
  fi
  rm -f "$PIDFILE"
}

setup() {
  need_device
  command -v "$PY" >/dev/null || die "$PY not found"
  if server_alive; then
    echo "mock server already running (pid $(server_pid)) on port $PORT"
  else
    server_healthy && die "port $PORT is already in use by another process; set WALK_PORT"
    mkdir -p "$RUN_DIR"
    nohup "$PY" "$ROOT/tests/e2e/mock_llm_server.py" --host 127.0.0.1 --port "$PORT" --empty \
      --max-seconds "$MAX_SECONDS" >"$SERVER_LOG" 2>&1 &
    echo $! >"$PIDFILE"
    # If any step below fails, the server does not outlive this setup.
    trap stop_server EXIT
    local end=$((SECONDS + 10))
    until server_healthy; do
      [ "$SECONDS" -lt "$end" ] || die "mock server did not answer within 10 s (log: $SERVER_LOG)"
      server_alive || die "mock server exited (log: $SERVER_LOG)"
      sleep 0.5
    done
    echo "mock server running (pid $(server_pid)) on 127.0.0.1:$PORT, log $SERVER_LOG"
  fi

  "$ADB" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null || die "adb reverse failed"
  echo "adb reverse tcp:$PORT -> tcp:$PORT"

  # Opening the app starts the agent service; the receiver needs the process for start and answer.
  "$ADB" shell am start -n "$PACKAGE/ai.opencyclon.ui.ControlPanelActivity" >/dev/null \
    || die "cannot open $PACKAGE (is the debug build installed?)"
  local end=$((SECONDS + 20)) ready=""
  while [ "$SECONDS" -lt "$end" ]; do
    if "$PY" "$HERE/walk.py" state 2>/dev/null | grep -q '"service_running": true'; then ready=1; break; fi
    sleep 1
  done
  [ -n "$ready" ] || die "the app did not answer with a running agent service within 20 s (walk.sh state shows why)"

  "$PY" "$HERE/walk.py" set_model --base-url "http://localhost:$PORT/v1" || die "set_model failed"
  trap - EXIT
  echo "setup done. Next: walk.sh list, walk.sh run <scenario>"
}

teardown() {
  stop_server
  "$ADB" reverse --remove "tcp:$PORT" >/dev/null 2>&1 && echo "adb reverse tcp:$PORT removed"
  echo "The app still points at http://localhost:$PORT/v1; set a real model in Settings before a real walk."
}

run_all() {
  local out="$HERE/evidence/$(date +%Y%m%d-%H%M%S)-all" failed=0 name
  if [ "${1:-}" = "--out" ] && [ -n "${2:-}" ]; then out="$2"; fi
  for name in $("$PY" "$HERE/walk.py" list | awk '{print $1}'); do
    "$PY" "$HERE/walk.py" run "$name" --out "$out/$name" || failed=$((failed + 1))
    # An approval left waiting would block the next scenario.
    "$PY" "$HERE/walk.py" reject >/dev/null 2>&1
  done
  echo "all scenarios done, $failed failed; evidence in $out"
  [ "$failed" -eq 0 ]
}

# ── Lane X2: step scenarios and the home-page commands (kept apart from the case below) ──
run_all2() {
  local out="$HERE/evidence/$(date +%Y%m%d-%H%M%S)-all2" failed=0 name
  if [ "${1:-}" = "--out" ] && [ -n "${2:-}" ]; then out="$2"; fi
  for name in $("$PY" "$HERE/walk.py" list2 | awk '{print $1}'); do
    "$PY" "$HERE/walk.py" run2 "$name" --out "$out/$name" || failed=$((failed + 1))
    "$PY" "$HERE/walk.py" reject >/dev/null 2>&1
  done
  echo "all step scenarios done, $failed failed; evidence in $out"
  [ "$failed" -eq 0 ]
}

case "${1:-}" in
  list2) "$PY" "$HERE/walk.py" list2; exit $? ;;
  run2)
    shift
    [ -n "${1:-}" ] || die "usage: walk.sh run2 <scenario> [--out DIR] [--timeout SECONDS]"
    need_device
    server_alive || die "mock server is not running: walk.sh setup"
    "$PY" "$HERE/walk.py" run2 "$@"; exit $? ;;
  all2)
    shift
    need_device
    server_alive || die "mock server is not running: walk.sh setup"
    run_all2 "$@"; exit $? ;;
  confirm|page|app_seed|widget_seed)
    cmd2="$1"; shift
    need_device
    "$PY" "$HERE/walk.py" "$cmd2" "$@"; exit $? ;;
esac
# ── end of lane X2 block ──

cmd="${1:-}"
[ -n "$cmd" ] || { sed -n '2,21p' "$0"; exit 2; }
shift
case "$cmd" in
  setup) setup ;;
  teardown) teardown ;;
  run)
    [ -n "${1:-}" ] || die "usage: walk.sh run <scenario> [--out DIR] [--timeout SECONDS]"
    need_device
    server_alive || die "mock server is not running: walk.sh setup"
    "$PY" "$HERE/walk.py" run "$@" ;;
  all)
    need_device
    server_alive || die "mock server is not running: walk.sh setup"
    run_all "$@" ;;
  list) "$PY" "$HERE/walk.py" list ;;
  state|approve|reject|stop|answer|edit|start|notify|set_model|suggestions)
    need_device
    "$PY" "$HERE/walk.py" "$cmd" "$@" ;;
  voice_selftest)
    need_device
    "$PY" "$HERE/walk.py" voice_selftest "$@" ;;
  voice_selftest_android)
    need_device
    "$PY" "$HERE/walk.py" voice_selftest --engine android ;;
  voice_engine)
    [ -n "${1:-}" ] || die "usage: walk.sh voice_engine android|phone_model|service [--voice-model ID]"
    need_device
    "$PY" "$HERE/walk.py" voice_engine "$@" ;;
  voice_selftest_service)
    need_device
    server_alive || die "mock server is not running: walk.sh setup"
    "$PY" "$HERE/walk.py" voice_selftest_service "$@" ;;
  voice_mictest)
    need_device
    "$PY" "$HERE/walk.py" voice_mictest --ms "${1:-2000}" ;;
  look)
    # Saved evidence (--from) and the screen list need no phone.
    case " $* " in *" --from "*|*" --list "*|" ") ;; *) need_device ;; esac
    "$PY" "$HERE/look.py" "$@" ;;
  -h|--help|help) sed -n '2,21p' "$0" ;;
  *) die "unknown command '$cmd' (walk.sh help)" ;;
esac
