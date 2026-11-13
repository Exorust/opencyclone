"""Offline tests for the step scenarios of walk.py (lane X2). Standard library only.

    python3 -m unittest discover -s tests/walk
"""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tests" / "walk"))

import walk  # noqa: E402

DEBUG_V2 = ROOT / "android/app/src/debug/java/ai/opencyclon/debug/DebugCommandsV2.kt"


class HelpersTest(unittest.TestCase):
    def test_substitute_fills_placeholders_everywhere(self):
        names = {"LINGO_ID": 7}
        value = {"a": "app:<LINGO_ID>", "b": ["app:LINGO_ID,history", 3], "c": {"d": "LINGO_ID"}}
        self.assertEqual(walk.substitute(value, names), {"a": "app:7", "b": ["app:7,history", 3], "c": {"d": "7"}})
        self.assertEqual(walk.substitute("LINGO_ID", {}), "LINGO_ID")

    def test_step_action_takes_exactly_one_known_action(self):
        self.assertEqual(walk.step_action({"start": "x"}), "start")
        self.assertEqual(walk.step_action({"wait_row": {}, "timeout": 5}), "wait_row")
        self.assertEqual(walk.step_action({"expect_rows": {}, "count": 2}), "expect_rows")
        for bad in ({}, {"start": "x", "notify": {}}, {"launch": 1}, {"start": "x", "colour": "red"}):
            with self.assertRaises(ValueError):
                walk.step_action(bad)

    def test_row_and_task_specs(self):
        row = {"id": 5, "kind": "CONFIRM", "state": "OPEN", "text": "Reply to Alex that dinner at 7 works?",
               "action_word": "Do it", "title": "proposal"}
        self.assertTrue(walk.row_matches(row, {"kind": "CONFIRM", "state": "OPEN", "text_contains": "DINNER"}))
        self.assertTrue(walk.row_matches(row, {"kind_in": ["PROGRESS", "CONFIRM"], "action_word": "Do it"}))
        self.assertFalse(walk.row_matches(row, {"state_in": ["REJECTED", "EXPIRED"]}))
        self.assertFalse(walk.row_matches(row, {"title": None}))
        task = {"id": 3, "origin": "user", "goal": "Add a note titled pm-test", "state": "running"}
        self.assertTrue(walk.task_matches(task, {"origin": "user", "goal_contains": "pm-test", "state_in": ["running"]}))
        self.assertFalse(walk.task_matches(task, {"state": "done"}))
        state = {"thread": [{"id": 1}, {"id": 6}], "tasks": [{"id": 2}, {"id": 4}]}
        self.assertEqual(walk.rows_since(state, 1), [{"id": 6}])
        self.assertEqual(walk.tasks_since(state, 4), [])


class ScenarioFileTest(unittest.TestCase):
    def test_every_step_scenario_is_valid_and_the_required_ones_exist(self):
        scenarios = walk.load_scenarios(walk.SCENARIOS_V2)
        self.assertEqual(walk.validate_v2(scenarios), [])
        self.assertLessEqual({"proposal_accept_home", "proposal_decline_home", "model_phrases", "open_privacy",
                              "missing_model", "built_app_change_named", "widget_build_draft",
                              "widget_refused_sources", "widget_three_repairs"}, set(scenarios))

    def test_steps_only_send_commands_the_receiver_knows(self):
        source = DEBUG_V2.read_text()
        for name in ("start", "approve", "reject", "confirm", "page", "widget_seed", "app_seed"):
            self.assertIn(f'"{name}"', source)

    def test_widget_scenarios_seed_lingo_before_loading_its_id(self):
        for name, scenario in walk.load_scenarios(walk.SCENARIOS_V2).items():
            uses_id = "LINGO_ID" in json.dumps(scenario.get("texts", []))
            if not uses_id:
                continue
            actions = [walk.step_action(s) for s in scenario["steps"]]
            self.assertIn("load", actions, name)
            self.assertLess(actions.index("seed_app"), actions.index("load"), name)

    def test_the_three_failed_repairs_scenario_scripts_four_broken_files(self):
        texts = walk.load_scenarios(walk.SCENARIOS_V2)["widget_three_repairs"]["texts"]
        broken = [t["text"] for t in texts if "broken" in t.get("text", "")]
        self.assertEqual(len(broken), 4)
        self.assertTrue(all('height:2000px' in html for html in broken))


class RunV2Test(unittest.TestCase):
    """run_v2 against a fake phone: the commands it sends and the checks it makes."""

    def setUp(self):
        self.sent = []
        self.loaded = []
        self.states = []
        self.saved = {n: getattr(walk, n) for n in ("send", "read_state", "mock", "adb", "POLL_SECONDS")}
        walk.POLL_SECONDS = 0

        def send(cmd, timeout=15, **extras):
            self.sent.append((cmd, extras))
            if cmd == "app_seed":
                return True, json.dumps({"app_id": 7})
            if cmd == "widget_seed":
                return True, json.dumps({"widget_id": 8})
            return True, "ok"

        def read_state():
            return self.states.pop(0) if len(self.states) > 1 else self.states[0]

        def mock(path, body=None, timeout=5):
            if path == "/load":
                self.loaded.append(body)
            return {"requests": []}

        walk.send, walk.read_state, walk.mock = send, read_state, mock
        walk.adb = lambda args, timeout=20, binary=False: (1, b"" if binary else "")
        self.out = Path(tempfile.mkdtemp())

    def tearDown(self):
        for k, v in self.saved.items():
            setattr(walk, k, v)
        shutil.rmtree(self.out, ignore_errors=True)

    def test_a_passing_scenario_sends_seed_load_start_and_confirm_in_order(self):
        scenario = {"texts": [{"text": "app:<LINGO_ID>"}], "steps": [
            {"seed_app": {"name": "Lingo", "data": {"streak": 4}}},
            {"load": True},
            {"start": "Make a widget"},
            {"wait_row": {"kind": "CONFIRM", "state": "OPEN"}, "timeout": 1},
            {"confirm": {"item": "last", "yes": False}},
            {"expect_tasks": {}, "count": 0},
        ]}
        self.states = [{"thread": [{"id": 1, "kind": "SENTENCE"}], "tasks": [{"id": 9}]},
                       {"thread": [{"id": 1}, {"id": 2, "kind": "CONFIRM", "state": "OPEN"}], "tasks": [{"id": 9}]}]
        code = walk.run_v2("x", self.out, 30, scenarios={"x": scenario})
        self.assertEqual(code, 0)
        self.assertEqual([c for c, _ in self.sent], ["set_model", "app_seed", "start", "confirm", "unseed_app"])
        self.assertEqual(self.sent[1][1], {"name": "Lingo", "data": json.dumps({"streak": 4})})
        self.assertEqual(self.sent[3][1], {"item": "last", "yes": "false"})
        self.assertEqual(self.loaded[-1]["texts"], [{"text": "app:7"}])
        result = json.loads((self.out / "result.json").read_text())
        self.assertTrue(result["passed"])
        self.assertEqual(result["names"]["LINGO_ID"], 7)
        self.assertTrue(result["names"]["RUN_ID"])

    def test_a_row_that_never_comes_fails_and_stops_the_scenario(self):
        scenario = {"steps": [
            {"wait_row": {"kind": "CONFIRM"}, "timeout": 0},
            {"start": "never sent"},
        ]}
        self.states = [{"thread": [], "tasks": []}]
        code = walk.run_v2("y", self.out, 30, scenarios={"y": scenario})
        self.assertEqual(code, 1)
        self.assertEqual([c for c, _ in self.sent], ["set_model"])
        self.assertFalse(json.loads((self.out / "result.json").read_text())["passed"])

    def test_only_rows_after_the_mark_count(self):
        scenario = {"steps": [{"expect_no_row": {"kind": "CONFIRM"}}]}
        self.states = [{"thread": [{"id": 3, "kind": "CONFIRM", "state": "OPEN"}], "tasks": []}]
        self.assertEqual(walk.run_v2("z", self.out, 30, scenarios={"z": scenario}), 0)

    def test_failed_widget_scenario_discards_draft_and_removes_only_seeded_id(self):
        self.states = [{"thread": [], "tasks": []}]
        scenario = {"steps": [{"seed_app": {"name": "Lingo"}},
                              {"wait_row": {"kind": "CONFIRM"}, "timeout": 0}]}
        self.assertEqual(walk.run_v2("widget_failed", self.out, 30,
                                   {"widget_failed": scenario}), 1)
        self.assertEqual(self.sent[-2:], [("widget_discard", {}), ("unseed_app", {"id": "7"})])

    def test_seeded_widget_is_removed_on_failure(self):
        self.states = [{"thread": [], "tasks": []}]
        scenario = {"steps": [{"seed_widget": {"html": "<html></html>"}},
                              {"wait_row": {"kind": "RESULT"}, "timeout": 0}]}
        self.assertEqual(walk.run_v2("widget_saved", self.out, 30, {"widget_saved": scenario}), 1)
        self.assertIn(("unseed_widget", {"id": "8"}), self.sent)

    def test_cleanup_failure_fails_the_evidence_report(self):
        self.states = [{"thread": [], "tasks": []}]
        scenario = {"steps": [{"seed_app": {"name": "Lingo"}}]}
        send = walk.send
        walk.send = lambda cmd, **extras: (False, "cleanup failed") if cmd == "unseed_app" else send(cmd, **extras)
        self.assertEqual(walk.run_v2("cleanup_failure", self.out, 30, {"cleanup_failure": scenario}), 1)
        report = json.loads((self.out / "result.json").read_text())
        self.assertFalse(report["passed"])
        self.assertFalse(report["cleanup"][0]["ok"])

    def test_exception_still_removes_seeded_app(self):
        self.states = [{"thread": [], "tasks": []}]
        scenario = {"steps": [{"seed_app": {"name": "Lingo"}}, {"start": "fail"}]}
        send = walk.send
        def failing(cmd, **extras):
            if cmd == "start":
                raise RuntimeError("test failure")
            return send(cmd, **extras)
        walk.send = failing
        with self.assertRaisesRegex(RuntimeError, "test failure"):
            walk.run_v2("widget_exception", self.out, 30, {"widget_exception": scenario})
        self.assertEqual(self.sent[-2:], [("widget_discard", {}), ("unseed_app", {"id": "7"})])

    def test_an_invalid_scenario_is_refused_before_any_command(self):
        self.states = [{"thread": [], "tasks": []}]
        self.assertEqual(walk.run_v2("bad", self.out, 30, scenarios={"bad": {"steps": [{"fly": 1}]}}), 2)
        self.assertEqual(self.sent, [])


if __name__ == "__main__":
    unittest.main()
