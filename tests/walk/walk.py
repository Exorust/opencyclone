#!/usr/bin/env python3
"""Walk harness driver: talks to the phone through adb and to the mock model server over HTTP.

Called by walk.sh; standard library only (Python 3.9+). Every wait has a deadline.

    python3 tests/walk/walk.py run <scenario> [--out DIR] [--timeout 120]
    python3 tests/walk/walk.py list
    python3 tests/walk/walk.py state
    python3 tests/walk/walk.py set_model --base-url http://localhost:8765/v1
    python3 tests/walk/walk.py approve | reject | stop
    python3 tests/walk/walk.py answer <text> | edit <text> | start <text>
    python3 tests/walk/walk.py notify <package> <title> <text>
    python3 tests/walk/walk.py voice_selftest [--engine phone_model|android|service] [text]   prints JSON
    python3 tests/walk/walk.py voice_engine [android|phone_model|service] [--voice-model ID]
                               [--address URL --service-model NAME --key KEY]
    python3 tests/walk/walk.py voice_selftest_service [text]   the internet recognizer against the mock server
    python3 tests/walk/walk.py voice_mictest [--ms 2000]  open the microphone as listening does, prints JSON
    python3 tests/walk/walk.py run2 <scenario> [--out DIR] [--timeout 300]   a step scenario of scenarios_v2.json
    python3 tests/walk/walk.py list2
    python3 tests/walk/walk.py confirm [--item last|draft|ID] [--no]    answer a CONFIRM row (proposal, widget, set up)
    python3 tests/walk/walk.py page home|history|widgets
    python3 tests/walk/walk.py app_seed <name> [--data JSON]   store a built app with bridge data, prints its id
    python3 tests/walk/walk.py widget_seed <name> --html FILE|HTML [--size small] [--sources a,b]

Environment: WALK_PORT (default 8765), WALK_PACKAGE (default ai.opencyclone.standard),
ANDROID_SERIAL (adb picks the device), ADB (adb binary, default "adb").
"""

from __future__ import annotations

import uuid
import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Optional

HERE = Path(__file__).resolve().parent
SCENARIOS = HERE / "scenarios.json"
SCENARIOS_V2 = HERE / "scenarios_v2.json"
PACKAGE = os.environ.get("WALK_PACKAGE", "ai.opencyclone.standard")
RECEIVER = f"{PACKAGE}/ai.opencyclone.debug.DebugCommandReceiver"
ACTION = "ai.opencyclone.debug.CMD"
TESTAPP = "ai.opencyclone.testapp"
PORT = int(os.environ.get("WALK_PORT", "8765"))
ADB = os.environ.get("ADB", "adb")
TERMINAL = {"done", "failed", "cancelled"}
POLL_SECONDS = 2.0
MAX_TIMEOUT = 600


# ── adb ──────────────────────────────────────────────────────────────────────

def adb(args: list[str], timeout: float = 20, binary: bool = False):
    """Runs adb with a deadline. Returns (exit code, stdout); a timeout returns (124, partial or '')."""
    try:
        done = subprocess.run([ADB, *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as e:
        return 124, (e.stdout or (b"" if binary else ""))
    except FileNotFoundError:
        sys.exit(f"adb not found ({ADB})")
    out = done.stdout if binary else done.stdout.decode("utf-8", "replace")
    return done.returncode, out


def shell(command: str, timeout: float = 20) -> tuple[int, str]:
    return adb(["shell", command], timeout=timeout)


# Commands that run longer than a foreground broadcast may (10 s); sent as background broadcasts (60 s).
SLOW_COMMANDS = {"voice_selftest", "voice_mictest"}


def broadcast_command(cmd: str, extras: dict[str, str]) -> str:
    """The device shell line for one receiver command (every value quoted for the device shell)."""
    # FLAG_RECEIVER_FOREGROUND | FLAG_INCLUDE_STOPPED_PACKAGES; slow commands drop the foreground flag.
    flags = 0x00000020 if cmd in SLOW_COMMANDS else 0x10000020
    parts = ["am", "broadcast", "-n", RECEIVER, "-a", ACTION,
             "-f", str(flags), "--es", "cmd", cmd]
    for key, value in extras.items():
        parts += ["--es", key, value]
    return " ".join(shlex.quote(p) for p in parts)


def parse_broadcast(output: str) -> tuple[Optional[int], Optional[str]]:
    """(result code, result data) from `am broadcast` output; (None, None) when no receiver answered."""
    m = re.search(r"Broadcast completed: result=(-?\d+)(?:, data=\"(.*)\")?", output, re.S)
    if not m:
        return None, None
    # am prints the data raw between quotes; the greedy match runs to the last quote in the output.
    data = m.group(2)
    return int(m.group(1)), data.rstrip() if data is not None else None


def send(cmd: str, timeout: float = 15, **extras: str) -> tuple[bool, str]:
    """Sends one command. Returns (ok, result line or JSON)."""
    code, out = shell(broadcast_command(cmd, {k: v for k, v in extras.items() if v is not None}), timeout=timeout)
    if code == 124:
        return False, f"adb timed out after {timeout}s"
    result, data = parse_broadcast(out)
    if result is None:
        return False, f"no broadcast result (adb exit {code}): {out.strip()[:300]}"
    if data is None:
        return False, ("the receiver did not answer; is the debug build of "
                       f"{PACKAGE} installed and opened once? ({out.strip()[:200]})")
    return result == 0, data


def read_state() -> Optional[dict]:
    """The app's state JSON, from the broadcast result or, failing that, from the state file."""
    request_id = uuid.uuid4().hex[:8]
    ok, data = send("state", id=request_id)
    if ok:
        try:
            return json.loads(data)
        except ValueError:
            pass
    for args in (["exec-out", "cat", f"/sdcard/Android/data/{PACKAGE}/files/walk/state.json"],
                 ["exec-out", "run-as", PACKAGE, "cat", "files/walk/state.json"]):
        code, out = adb(args, timeout=10)
        if code == 0:
            try:
                state = json.loads(out)
                if state.get("request_id") == request_id:
                    return state
            except ValueError:
                continue
    print(f"  state unavailable: {data}", file=sys.stderr)
    return None


COUNTERS = "sent|pressed|touched|moved|slow|still"


def parse_counters(text: str) -> Optional[dict]:
    """counters.txt ("sent=1 pressed=0 touched=0 moved=0 slow=0 still=0") or the shared_prefs XML, as ints."""
    found = dict((k, int(v)) for k, v in re.findall(rf"\b({COUNTERS})=(\d+)", text))
    if not found:
        found = dict((k, int(v)) for k, v in
                     re.findall(rf'<int name="({COUNTERS})" value="(\d+)"', text))
    return found or None


def read_counters() -> dict:
    """The test app's counters; all zero when the app has never written them."""
    for args in (["exec-out", "cat", f"/sdcard/Android/data/{TESTAPP}/files/counters.txt"],
                 ["exec-out", "run-as", TESTAPP, "cat", "shared_prefs/counters.xml"]):
        code, out = adb(args, timeout=10)
        if code == 0:
            counters = parse_counters(out)
            if counters is not None:
                return {"sent": 0, "pressed": 0, "touched": 0, **counters}
    return {"sent": 0, "pressed": 0, "touched": 0}


# ── mock server ──────────────────────────────────────────────────────────────

def mock(path: str, body: Optional[dict] = None, timeout: float = 5):
    url = f"http://127.0.0.1:{PORT}{path}"
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method="GET" if body is None else "POST",
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read() or b"{}")


def voice_engine_extras(args) -> dict:
    """The broadcast extras of `voice_engine`; options left out are not sent."""
    pairs = {"value": args.value, "voice_model": args.voice_model, "address": args.address,
             "service_model": args.service_model, "key": args.key}
    return {k: v for k, v in pairs.items() if v}


def voice_selftest_service(text: str) -> int:
    """Points the app's internet recognizer at the mock server, runs the self-test, and checks one request arrived."""
    try:
        mock("/transcription", {"text": text, "status": 200})
        before = mock("/transcriptions")["total"]
    except (urllib.error.URLError, OSError) as e:
        print(f"FAIL mock server not reachable on port {PORT}: {e}. Run walk.sh setup first.")
        return 1
    ok, line = send("voice_engine", value="service", address=f"http://localhost:{PORT}/v1",
                    service_model="walk-transcribe", key="walk")
    if not ok:
        print(f"FAIL voice_engine: {line}")
        return 1
    ok, data = send("voice_selftest", timeout=70, engine="service", text=text)
    try:
        result = json.loads(data)
    except ValueError:
        result = {"raw": data}
    after = mock("/transcriptions")
    new = after["requests"][before:]
    report = {"selftest": result, "transcription_requests": new}
    print(json.dumps(report, indent=2, ensure_ascii=False))
    passed = (ok and len(new) == 1 and new[0].get("authorization") is True
              and new[0].get("file", {}).get("valid") is True and result.get("final_text") == text)
    print(f"{'PASS' if passed else 'FAIL'} voice_selftest_service: {len(new)} audio request(s), "
          f"returned text {result.get('final_text')!r}")
    return 0 if passed else 1


# ── scenarios ────────────────────────────────────────────────────────────────

def load_scenarios(path: Path = SCENARIOS) -> dict:
    with open(path) as f:
        data = json.load(f)
    return {k: v for k, v in data.items() if not k.startswith("_")}


def pick_task(state: dict, baseline_id: int, scenario: dict) -> Optional[dict]:
    """The scenario's task: the newest task created after the run started, else (notification
    scenarios) the open proposal with the expected goal, which the coordinator reuses for a repeat."""
    tasks = state.get("tasks") or []
    new = [t for t in tasks if t.get("id", 0) > baseline_id]
    if new:
        return new[-1]
    goal = (scenario.get("expect") or {}).get("task", {}).get("goal")
    if scenario.get("notify") and goal:
        same = [t for t in tasks if t.get("origin") == "notification" and t.get("goal") == goal
                and t.get("state") not in TERMINAL]
        if same:
            return same[-1]
    return None


def check(expect: dict, seen: dict) -> list[tuple[bool, str]]:
    """PASS/FAIL lines for the scenario's expectations against what the run observed."""
    results = []
    if "approval" in expect:
        want, got = expect["approval"], seen.get("approval")
        if want is None:
            results.append((got is None, f"no approval hold (saw {json.dumps(got)})"))
        elif got is None:
            results.append((False, "approval hold (none seen)"))
        else:
            for key, value in want.items():
                results.append((got.get(key) == value, f"approval {key} = {value!r} (saw {got.get(key)!r})"))
    if "approve_refused" in expect:
        refusal = seen.get("approve_first")
        refused = refusal is not None and not refusal[0]
        results.append((refused == expect["approve_refused"],
                        f"approve refused = {expect['approve_refused']} (answer: {refusal})"))
    if "question" in expect:
        q = seen.get("question") or ""
        results.append((expect["question"] in q, f"question contains {expect['question']!r} (saw {q!r})"))
    if "task" in expect:
        task = seen.get("task")
        if task is None:
            results.append((False, "task row (none found)"))
        else:
            for key, value in expect["task"].items():
                results.append((task.get(key) == value, f"task {key} = {value!r} (saw {task.get(key)!r})"))
    if "counters_delta" in expect:
        before, after = seen.get("counters_before") or {}, seen.get("counters_after") or {}
        for key, value in expect["counters_delta"].items():
            delta = after.get(key, 0) - before.get(key, 0)
            results.append((delta == value, f"counter {key} changed by {value} (saw {delta}: "
                                            f"{before.get(key)} -> {after.get(key)})"))
    if "model_saw" in expect:
        texts = [r.get("user_text", "") for r in seen.get("requests") or []]
        results.append((any(expect["model_saw"] in t for t in texts),
                        f"a model request contains {expect['model_saw']!r}"))
    if "steps" in expect:
        steps = seen.get("steps") or []
        for want in expect["steps"]:
            results.append((any(step_matches(want, s) for s in steps),
                            f"a step matches {json.dumps(want)} (steps: {json.dumps(steps)})"))
    if expect.get("script_used_up"):
        left = seen.get("queued_left")
        results.append((left == 0, f"every scripted tool call was used (left: {left})"))
    return results


def step_matches(want: dict, step: dict) -> bool:
    """One step fact of the state against an expected one: keys equal, wait_ms_min/max bound wait_ms (F18)."""
    for key, value in want.items():
        wait = step.get("wait_ms")
        if key == "wait_ms_min":
            if wait is None or wait < value:
                return False
        elif key == "wait_ms_max":
            if wait is None or wait > value:
                return False
        elif step.get(key) != value:
            return False
    return True


ACTIVE_TASK_STATES = {"queued", "running", "paused", "awaiting_approval"}


def busy_tasks(state: dict) -> list[dict]:
    return [t for t in state.get("tasks") or [] if t.get("state") in ACTIVE_TASK_STATES]


def prepare_scenario() -> Optional[dict]:
    """Stop once if occupied, then refuse to load replies until the task slot and queue are empty."""
    state = read_state()
    if state is None:
        print("FAIL cannot read the app state before the scenario")
        return None
    if busy_tasks(state):
        ok, line = send("stop")
        print(f"  preflight stop -> {'ok' if ok else 'FAIL'}: {line}")
        end = time.monotonic() + 5
        while True:
            state = read_state()
            if state is None:
                print("FAIL cannot verify that tasks stopped; scenario not started")
                return None
            if not busy_tasks(state):
                break
            if time.monotonic() >= end:
                print("FAIL a task is still running or queued after one stop; scenario not started: " +
                      json.dumps(busy_tasks(state)))
                return None
            time.sleep(POLL_SECONDS)
    ok, line = send("set_model", provider="openai", base_url=f"http://localhost:{PORT}/v1",
                    model="walk-model", key="walk")
    if not ok:
        print(f"FAIL cannot restore the walk model: {line}")
        return None
    return state


def run(name: str, out_dir: Path, timeout: int) -> int:
    scenarios = load_scenarios()
    if name not in scenarios:
        print(f"unknown scenario {name!r}; one of: {', '.join(scenarios)}")
        return 2
    scenario = substitute(scenarios[name], {"RUN_ID": uuid.uuid4().hex})
    if prepare_scenario() is None:
        return 2
    timeout = max(10, min(timeout, MAX_TIMEOUT))
    out_dir.mkdir(parents=True, exist_ok=True)
    print(f"== {name}: {scenario.get('description', '')}")

    try:
        mock("/reset", {})
        mock("/load", {"tool_calls": scenario.get("tool_calls", []), "texts": scenario.get("texts", [])})
    except (urllib.error.URLError, OSError) as e:
        print(f"FAIL mock server not reachable on port {PORT}: {e}. Run walk.sh setup first.")
        return 2

    # The test app starts on its menu, so open_app lands on a known screen.
    shell(f"am force-stop {TESTAPP}")
    seen: dict = {"counters_before": read_counters(), "events": []}
    state = read_state()
    if state is None:
        print("FAIL cannot read the app state (see above)")
        return 2
    if not state.get("service_running"):
        print("FAIL the agent service is not running: open the app (walk.sh setup does this)")
        return 2
    if state.get("approval"):
        print(f"FAIL an approval is already waiting for task {state['approval'].get('task_id')}: "
              "walk.sh reject first")
        return 2
    baseline = max([t.get("id", 0) for t in state.get("tasks") or []], default=0)
    started = time.time()

    def event(text: str):
        line = f"[{time.time() - started:6.1f}s] {text}"
        seen["events"].append(line)
        print("  " + line)

    if scenario.get("notify") and (state.get("window") or {}).get("active"):
        ok, line = send("start", text="stop handling my notifications")
        event(f"stop notification window -> {'ok' if ok else 'FAIL'}: {line}")
        end = time.monotonic() + 5
        while ok:
            fresh = read_state()
            if fresh is None:
                ok = False
                break
            state = fresh
            if not (state.get("window") or {}).get("active"):
                break
            if time.monotonic() >= end:
                ok = False
                break
            time.sleep(POLL_SECONDS)
        if not ok:
            return finish(name, scenario, seen, out_dir, state, aborted=True)
    if scenario.get("start"):
        ok, line = send("start", text=scenario["start"])
        event(f"start -> {'ok' if ok else 'FAIL'}: {line}")
        if not ok:
            return finish(name, scenario, seen, out_dir, state, aborted=True)
    if "suggestions" in scenario:
        ok, line = send("suggestions", value=scenario["suggestions"])
        event(f"suggestions -> {'ok' if ok else 'FAIL'}: {line}")
        if not ok:
            return finish(name, scenario, seen, out_dir, state, aborted=True)
    if scenario.get("notify"):
        n = scenario["notify"]
        ok, line = send("notify", package=n["package"], title=n.get("title", ""), text=n["text"])
        event(f"notify -> {'ok' if ok else 'FAIL'}: {line}")
        if not ok:
            return finish(name, scenario, seen, out_dir, state, aborted=True)

    end_states = {scenario["end_state"]} if scenario.get("end_state") else TERMINAL
    acted = answered = False
    held_waiting = False
    deadline = started + timeout
    task = None
    while time.time() < deadline:
        state = read_state() or state
        task = pick_task(state, baseline, scenario) or task
        approval = state.get("approval")
        if approval and not acted and (task is None or approval.get("task_id") == task.get("id")):
            seen["approval"] = approval
            event(f"held: {approval.get('title')!r} reason={approval.get('reason')} "
                  f"approve_offered={approval.get('approve_offered')}")
            if scenario.get("try_approve_first"):
                seen["approve_first"] = send("approve")
                event(f"approve (expected refusal) -> {seen['approve_first']}")
            action = scenario.get("on_hold", "wait")
            if action == "wait":
                event("left waiting for the PM: walk.sh approve | reject | edit <text>")
                held_waiting = True
                break
            extras = {"text": scenario.get("edit_text", "")} if action == "edit" else {}
            result = send(action, **extras)
            event(f"{action} -> {result}")
            acted = True
        engine = state.get("engine") or {}
        if engine.get("state") == "WaitingForUser" and not answered:
            seen["question"] = engine.get("detail")
            event(f"question: {engine.get('detail')!r}")
            if scenario.get("answer"):
                result = send("answer", text=scenario["answer"])
                event(f"answer {scenario['answer']!r} -> {result}")
                answered = True
        if task and task.get("state") in end_states:
            event(f"task {task.get('id')} {task.get('state')}: {task.get('result')!r}")
            break
        time.sleep(POLL_SECONDS)
    else:
        event(f"timed out after {timeout}s (task: {json.dumps(task)})")

    time.sleep(1.5)  # let the test app write its counters file
    seen["task"] = task
    return finish(name, scenario, seen, out_dir, state, held_waiting=held_waiting)


def finish(name: str, scenario: dict, seen: dict, out_dir: Path, state: dict,
           aborted: bool = False, held_waiting: bool = False) -> int:
    seen["counters_after"] = read_counters()
    final = read_state() or state
    seen["steps"] = final.get("steps") or []
    if seen.get("task") is not None:
        seen["task"] = next((t for t in final.get("tasks") or [] if t.get("id") == seen["task"].get("id")),
                            seen["task"])
    try:
        seen["requests"] = mock("/requests").get("requests", [])
        seen["queued_left"] = mock("/health").get("queued")
    except (urllib.error.URLError, OSError) as e:
        seen["requests"], seen["queued_left"] = [], None
        print(f"  mock server log unavailable: {e}")

    (out_dir / "state.json").write_text(json.dumps(final, indent=2))
    (out_dir / "mock_requests.json").write_text(json.dumps(seen["requests"], indent=2))
    code, png = adb(["exec-out", "screencap", "-p"], timeout=20, binary=True)
    if code == 0 and png:
        (out_dir / "screen.png").write_bytes(png)
    code, log = adb(["logcat", "-d", "-v", "time", "-t", "3000"], timeout=20)
    if code == 0:
        (out_dir / "logcat.txt").write_text(log)

    print(f"  counters: {seen['counters_before']} -> {seen['counters_after']}")
    for r in seen["requests"]:
        print(f"  model request {r.get('response_index')}: {r.get('response_type')} {r.get('detail', '')}")
    results = [(False, "run aborted")] if aborted else check(scenario.get("expect") or {}, seen)
    if held_waiting:
        results = [(True, "held and left waiting for the PM; expectations after the hold not checked")] + \
                  [r for r in results if r[1].startswith("approval")]
    for ok, text in results:
        print(f"  {'PASS' if ok else 'FAIL'} {text}")
    passed = all(ok for ok, _ in results)
    summary = {"scenario": name, "passed": passed, "results": [{"ok": ok, "check": t} for ok, t in results],
               "events": seen["events"], "counters_before": seen["counters_before"],
               "counters_after": seen["counters_after"], "task": seen.get("task"),
               "approval": seen.get("approval")}
    (out_dir / "result.json").write_text(json.dumps(summary, indent=2))
    print(f"{'PASS' if passed else 'FAIL'} {name}  (evidence: {out_dir})")
    return 0 if passed else 1


# ── step scenarios (version 2, lane X2) ─────────────────────────────────────
#
# A scenario of scenarios_v2.json is a list of steps. Each step is an object with one action key; the harness
# sends the commands through the same entry the home page uses (FeedLocator.commands) and checks the thread
# rows and task rows of the state JSON. Values may hold placeholders such as LINGO_ID (or <LINGO_ID>) that a
# seed step fills in ("as": "LINGO_ID").

STEP_ACTIONS = ("load", "set_model", "seed_app", "seed_widget", "start", "notify", "confirm", "page", "approve",
                "reject", "mark", "wait_row", "expect_row", "expect_no_row", "expect_rows", "wait_task", "expect_tasks",
                "pause", "suggestions", "expect_requests")


def substitute(value, names: dict):
    """[value] with every placeholder of [names] replaced, in strings inside lists and objects too."""
    if isinstance(value, str):
        for key, replacement in names.items():
            value = value.replace(f"<{key}>", str(replacement)).replace(key, str(replacement))
        return value
    if isinstance(value, list):
        return [substitute(v, names) for v in value]
    if isinstance(value, dict):
        return {k: substitute(v, names) for k, v in value.items()}
    return value


def step_action(step: dict) -> str:
    """The step's one action key; raises ValueError for zero, several, or unknown keys."""
    actions = [k for k in step if k in STEP_ACTIONS]
    unknown = [k for k in step if k not in STEP_ACTIONS and k not in ("timeout", "as", "count")]
    if len(actions) != 1 or unknown:
        raise ValueError(f"step needs exactly one of {', '.join(STEP_ACTIONS)}: {json.dumps(step)}")
    return actions[0]


def validate_v2(scenarios: dict) -> list[str]:
    """Problems in the step scenarios; empty when every scenario can run."""
    problems = []
    for name, scenario in scenarios.items():
        steps = scenario.get("steps")
        if not isinstance(steps, list) or not steps:
            problems.append(f"{name}: no steps")
            continue
        for i, step in enumerate(steps):
            try:
                step_action(step)
            except ValueError as e:
                problems.append(f"{name} step {i + 1}: {e}")
    return problems


def row_matches(row: dict, spec: dict) -> bool:
    """A thread row against a spec: kind, state, text, text_contains, action_word, title, kind_in, state_in."""
    if "kind" in spec and row.get("kind") != spec["kind"]:
        return False
    if "kind_in" in spec and row.get("kind") not in spec["kind_in"]:
        return False
    if "state" in spec and row.get("state") != spec["state"]:
        return False
    if "state_in" in spec and row.get("state") not in spec["state_in"]:
        return False
    for key in ("text", "action_word", "title"):
        if key in spec and row.get(key) != spec[key]:
            return False
    if "text_contains" in spec and spec["text_contains"].lower() not in (row.get("text") or "").lower():
        return False
    return True


def rows_since(state: dict, mark: int) -> list[dict]:
    return [r for r in state.get("thread") or [] if r.get("id", 0) > mark]


def tasks_since(state: dict, mark: int) -> list[dict]:
    return [t for t in state.get("tasks") or [] if t.get("id", 0) > mark]


def task_matches(task: dict, spec: dict) -> bool:
    for key in ("origin", "state", "goal"):
        if key in spec and task.get(key) != spec[key]:
            return False
    if "state_in" in spec and task.get("state") not in spec["state_in"]:
        return False
    if "goal_contains" in spec and spec["goal_contains"].lower() not in (task.get("goal") or "").lower():
        return False
    return True


def _max_id(items) -> int:
    return max([i.get("id", 0) for i in items or []], default=0)


def run_v2(name: str, out_dir: Path, timeout: int, scenarios: Optional[dict] = None) -> int:
    scenarios = scenarios if scenarios is not None else load_scenarios(SCENARIOS_V2)
    cleanup = []
    # Widget scenarios create drafts as well as seeded fixtures.
    if name in scenarios and not validate_v2({name: scenarios[name]}) and name.startswith("widget_"):
        cleanup.append(("widget_discard", {}))
    result = 2
    cleanup_results = []
    try:
        result = _run_v2(name, out_dir, timeout, scenarios, cleanup)
    finally:
        # Cancel draft work before removing sources it may still be reading.
        for command, extras in sorted(cleanup, key=lambda entry: entry[0] != "widget_discard"):
            try:
                ok, line = send(command, **extras)
            except Exception as error:
                ok, line = False, str(error)
            cleanup_results.append({"command": command, "ok": ok, "detail": line})
            if not ok:
                print(f"FAIL cleanup {command}: {line}")
                result = 1
        report = out_dir / "result.json"
        if cleanup_results and report.exists():
            data = json.loads(report.read_text())
            data["cleanup"] = cleanup_results
            data["passed"] = data["passed"] and all(r["ok"] for r in cleanup_results)
            report.write_text(json.dumps(data, indent=2))
    return result


def _run_v2(name: str, out_dir: Path, timeout: int, scenarios: Optional[dict] = None, cleanup: Optional[list] = None) -> int:
    """Runs one step scenario. Every wait has a deadline (the step's timeout, at most the scenario's)."""
    scenarios = scenarios if scenarios is not None else load_scenarios(SCENARIOS_V2)
    if name not in scenarios:
        print(f"unknown scenario {name!r}; one of: {', '.join(scenarios)}")
        return 2
    scenario = scenarios[name]
    problems = validate_v2({name: scenario})
    if problems:
        print("FAIL " + "; ".join(problems))
        return 2
    out_dir.mkdir(parents=True, exist_ok=True)
    print(f"== {name}: {scenario.get('description', '')}")
    started = time.time()
    deadline = started + max(10, min(timeout, MAX_TIMEOUT))
    names: dict = {"RUN_ID": uuid.uuid4().hex}
    results: list[tuple[bool, str]] = []
    events: list[str] = []

    def event(text: str):
        line = f"[{time.time() - started:6.1f}s] {text}"
        events.append(line)
        print("  " + line)

    def load():
        mock("/reset", {})
        mock("/load", substitute({"tool_calls": scenario.get("tool_calls", []), "texts": scenario.get("texts", [])}, names))

    state = prepare_scenario()
    if state is None:
        return 2
    marks = {"rows": _max_id(state.get("thread")), "tasks": _max_id(state.get("tasks"))}
    try:
        if not any("load" in s for s in scenario["steps"]):
            load()
    except (urllib.error.URLError, OSError) as e:
        print(f"FAIL mock server not reachable on port {PORT}: {e}. Run walk.sh setup first.")
        return 2

    def poll(check, step_timeout: float):
        """Reads the state until check(state) is true or the deadline passes; returns (ok, last state)."""
        end = min(deadline, time.time() + step_timeout)
        last = None
        while True:
            last = read_state() or last
            if last is not None and check(last):
                return True, last
            if time.time() >= end:
                return False, last
            time.sleep(POLL_SECONDS)

    for i, raw in enumerate(scenario["steps"]):
        step = substitute(raw, names)
        action = step_action(step)
        value = step[action]
        label = f"step {i + 1} {action}"
        ok, line = True, ""
        if action == "load":
            load()
            line = "scripted replies loaded"
        elif action == "set_model":
            v = value or {}
            ok, line = send("set_model", provider=v.get("provider", "openai"),
                            base_url=v.get("base_url", f"http://localhost:{PORT}/v1"),
                            model=v.get("model", "walk-model"), key=v.get("key", "walk"))
        elif action == "seed_app":
            ok, line = send("app_seed", name=value["name"], data=json.dumps(value.get("data", {})))
            if ok:
                cleanup.append(("unseed_app", {"id": str(json.loads(line)["app_id"])}))
                names[step.get("as", value["name"].upper().replace(" ", "_") + "_ID")] = json.loads(line)["app_id"]
        elif action == "seed_widget":
            ok, line = send("widget_seed", html=value["html"], name=value.get("name", "Widget"),
                            size=value.get("size", "small"), sources=",".join(value.get("sources", [])))
            if ok:
                cleanup.append(("unseed_widget", {"id": str(json.loads(line)["widget_id"])}))
        elif action == "suggestions":
            ok, line = send("suggestions", value=value)
        elif action == "expect_requests":
            requests = mock("/requests").get("requests", [])
            ok = len(requests) == value
            line = f"{len(requests)} model requests, want {value}"
        elif action == "start":
            ok, line = send("start", text=value)
        elif action == "notify":
            ok, line = send("notify", package=value["package"], title=value.get("title", ""), text=value["text"])
        elif action == "confirm":
            v = value if isinstance(value, dict) else {}
            ok, line = send("confirm", item=str(v.get("item", "last")), yes="true" if v.get("yes", True) else "false")
        elif action == "page":
            ok, line = send("page", value=value)
        elif action in ("approve", "reject"):
            ok, line = send(action)
        elif action == "mark":
            now = read_state() or state
            marks = {"rows": _max_id(now.get("thread")), "tasks": _max_id(now.get("tasks"))}
            line = f"marks {marks}"
        elif action == "pause":
            time.sleep(min(float(value), max(0.0, deadline - time.time())))
            line = f"waited {value}s"
        elif action in ("wait_row", "expect_row"):
            wait = float(step.get("timeout", 60 if action == "wait_row" else 0))
            ok, now = poll(lambda st: any(row_matches(r, value) for r in rows_since(st, marks["rows"])), wait)
            line = f"row {json.dumps(value)} {'seen' if ok else 'not seen'}"
            if not ok and now:
                line += f"; rows since mark: {json.dumps([(r.get('kind'), r.get('state'), r.get('text')) for r in rows_since(now, marks['rows'])])[:600]}"
        elif action == "expect_no_row":
            now = read_state() or state
            ok = not any(row_matches(r, value) for r in rows_since(now, marks["rows"]))
            line = f"no row {json.dumps(value)}"
        elif action == "expect_rows":
            now = read_state() or state
            found = [r for r in rows_since(now, marks["rows"]) if row_matches(r, value)]
            ok = len(found) == step.get("count", 1)
            line = f"{len(found)} row(s) {json.dumps(value)}, want {step.get('count', 1)}"
        elif action == "wait_task":
            wait = float(step.get("timeout", 120))
            ok, now = poll(lambda st: any(task_matches(t, value) for t in tasks_since(st, marks["tasks"])), wait)
            line = f"task {json.dumps(value)} {'seen' if ok else 'not seen'}"
        elif action == "expect_tasks":
            now = read_state() or state
            found = [t for t in tasks_since(now, marks["tasks"]) if task_matches(t, value)]
            ok = len(found) == step.get("count", 1)
            line = f"{len(found)} task(s) {json.dumps(value)}, want {step.get('count', 1)}"
        event(f"{label} -> {'ok' if ok else 'FAIL'}: {line}")
        results.append((ok, f"{label}: {line}"))
        if not ok:
            break

    final = read_state() or state
    (out_dir / "state.json").write_text(json.dumps(final, indent=2))
    try:
        (out_dir / "mock_requests.json").write_text(json.dumps(mock("/requests").get("requests", []), indent=2))
    except (urllib.error.URLError, OSError) as e:
        print(f"  mock server log unavailable: {e}")
    code, png = adb(["exec-out", "screencap", "-p"], timeout=20, binary=True)
    if code == 0 and png:
        (out_dir / "screen.png").write_bytes(png)
    passed = bool(results) and all(ok for ok, _ in results) and len(results) == len(scenario["steps"])
    (out_dir / "result.json").write_text(json.dumps(
        {"scenario": name, "passed": passed, "results": [{"ok": ok, "check": t} for ok, t in results],
         "events": events, "names": names}, indent=2))
    print(f"{'PASS' if passed else 'FAIL'} {name}  (evidence: {out_dir})")
    return 0 if passed else 1


# ── command line ─────────────────────────────────────────────────────────────

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("run")
    p.add_argument("scenario")
    p.add_argument("--out", default=None)
    p.add_argument("--timeout", type=int, default=120)
    sub.add_parser("list")
    sub.add_parser("state")
    p = sub.add_parser("set_model")
    p.add_argument("--base-url", default=f"http://localhost:{PORT}/v1")
    p.add_argument("--model", default="walk-model")
    p.add_argument("--provider", default="openai")
    p.add_argument("--key", default="walk")
    for name in ("approve", "reject", "stop"):
        sub.add_parser(name)
    for name in ("answer", "edit", "start"):
        sub.add_parser(name).add_argument("text", nargs="+")
    sub.add_parser("suggestions").add_argument("value", choices=["on", "off"])
    p = sub.add_parser("notify")
    p.add_argument("package")
    p.add_argument("title")
    p.add_argument("text", nargs="+")
    p = sub.add_parser("voice_selftest")
    p.add_argument("--engine", choices=["phone_model", "android", "service"], default="phone_model")
    p.add_argument("text", nargs="*")
    p = sub.add_parser("voice_engine")
    p.add_argument("value", nargs="?", choices=["android", "phone_model", "service"])
    p.add_argument("--voice-model", default=None)
    p.add_argument("--address", default=None)
    p.add_argument("--service-model", default=None)
    p.add_argument("--key", default=None)
    sub.add_parser("voice_selftest_service").add_argument("text", nargs="*")
    sub.add_parser("voice_mictest").add_argument("--ms", default="2000")
    p = sub.add_parser("run2")
    p.add_argument("scenario")
    p.add_argument("--out", default=None)
    p.add_argument("--timeout", type=int, default=300)
    sub.add_parser("list2")
    p = sub.add_parser("confirm")
    p.add_argument("--item", default="last")
    p.add_argument("--no", action="store_true")
    sub.add_parser("page").add_argument("value", choices=["home", "history", "widgets"])
    p = sub.add_parser("app_seed")
    p.add_argument("name")
    p.add_argument("--data", default="{}")
    p = sub.add_parser("widget_seed")
    p.add_argument("name")
    p.add_argument("--html", required=True)
    p.add_argument("--size", default="small")
    p.add_argument("--sources", default="")
    args = parser.parse_args(argv)

    if args.cmd == "run":
        out = Path(args.out) if args.out else HERE / "evidence" / f"{time.strftime('%Y%m%d-%H%M%S')}-{args.scenario}"
        return run(args.scenario, out, args.timeout)
    if args.cmd == "run2":
        out = Path(args.out) if args.out else HERE / "evidence" / f"{time.strftime('%Y%m%d-%H%M%S')}-{args.scenario}"
        return run_v2(args.scenario, out, args.timeout)
    if args.cmd == "list2":
        for name, s in load_scenarios(SCENARIOS_V2).items():
            print(f"{name:24} {s.get('description', '')}")
        return 0
    if args.cmd == "list":
        for name, s in load_scenarios().items():
            print(f"{name:24} {s.get('description', '')}")
        return 0
    if args.cmd == "state":
        state = read_state()
        print(json.dumps(state, indent=2) if state else "state unavailable")
        return 0 if state else 1
    if args.cmd == "set_model":
        ok, line = send("set_model", provider=args.provider, base_url=args.base_url, model=args.model, key=args.key)
    elif args.cmd in ("answer", "edit", "start"):
        ok, line = send(args.cmd, text=" ".join(args.text))
    elif args.cmd in SLOW_COMMANDS:
        extras = {}
        if args.cmd == "voice_selftest":
            extras["engine"] = args.engine
            if args.text:
                extras["text"] = " ".join(args.text)
        if args.cmd == "voice_mictest":
            extras["ms"] = args.ms
        ok, data = send(args.cmd, timeout=70, **extras)
        try:
            print(json.dumps(json.loads(data), indent=2, ensure_ascii=False))
        except ValueError:
            print(f"{'ok' if ok else 'FAIL'}: {data}")
        return 0 if ok else 1
    elif args.cmd == "voice_engine":
        ok, line = send("voice_engine", **voice_engine_extras(args))
    elif args.cmd == "voice_selftest_service":
        return voice_selftest_service(" ".join(args.text) or "open the clock app")
    elif args.cmd == "suggestions":
        ok, line = send("suggestions", value=args.value)
    elif args.cmd == "notify":
        ok, line = send("notify", package=args.package, title=args.title, text=" ".join(args.text))
    elif args.cmd == "confirm":
        ok, line = send("confirm", item=args.item, yes="false" if args.no else "true")
    elif args.cmd == "page":
        ok, line = send("page", value=args.value)
    elif args.cmd == "app_seed":
        ok, line = send("app_seed", name=args.name, data=args.data)
    elif args.cmd == "widget_seed":
        html = Path(args.html).read_text() if Path(args.html).is_file() else args.html
        ok, line = send("widget_seed", html=html, name=args.name, size=args.size, sources=args.sources)
    else:
        ok, line = send(args.cmd)
    print(f"{'ok' if ok else 'FAIL'}: {line}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
