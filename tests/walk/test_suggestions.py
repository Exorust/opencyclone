"""Offline regressions for consent and scenario isolation; never contacts a phone or server."""
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import walk


class PreflightTest(unittest.TestCase):
    def test_idle_restores_exact_setup_defaults(self):
        state = {"tasks": []}
        with patch.object(walk, "read_state", return_value=state), patch.object(walk, "send", return_value=(True, "ok")) as send:
            self.assertEqual(walk.prepare_scenario(), state)
        send.assert_called_once_with("set_model", provider="openai", base_url=f"http://localhost:{walk.PORT}/v1",
                                     model="walk-model", key="walk")

    def test_stop_once_then_restore_only_after_idle(self):
        with patch.object(walk, "read_state", side_effect=[{"tasks": [{"state": "running"}]}, {"tasks": []}]), \
                patch.object(walk, "send", return_value=(True, "ok")) as send:
            self.assertIsNotNone(walk.prepare_scenario())
        self.assertEqual([c.args[0] for c in send.call_args_list], ["stop", "set_model"])

    def test_still_queued_fails_without_loading_or_changing_model(self):
        state = {"tasks": [{"id": 4, "state": "queued", "goal": "foreign task"}]}
        output = io.StringIO()
        with patch.object(walk, "read_state", return_value=state), \
                patch.object(walk, "send", return_value=(True, "ok")) as send, \
                patch.object(walk.time, "monotonic", side_effect=[0, 6]), contextlib.redirect_stdout(output):
            self.assertIsNone(walk.prepare_scenario())
        send.assert_called_once_with("stop")
        self.assertIn("still running or queued after one stop", output.getvalue())

    def test_unreadable_state_after_stop_is_not_treated_as_idle(self):
        with patch.object(walk, "read_state", side_effect=[{"tasks": [{"state": "running"}]}, None]), \
                patch.object(walk, "send", return_value=(True, "ok")) as send:
            self.assertIsNone(walk.prepare_scenario())
        send.assert_called_once_with("stop")

    def test_failed_model_reset_aborts(self):
        with patch.object(walk, "read_state", return_value={"tasks": []}), \
                patch.object(walk, "send", return_value=(False, "offline")):
            self.assertIsNone(walk.prepare_scenario())

    def test_both_runners_refuse_before_loading_replies(self):
        with tempfile.TemporaryDirectory() as out, patch.object(walk, "prepare_scenario", return_value=None), \
                patch.object(walk, "mock") as mock, patch.object(walk, "adb") as adb:
            self.assertEqual(walk.run("tap_by_number", Path(out), 30), 2)
            self.assertEqual(walk.run_v2("missing_model", Path(out), 30), 2)
        mock.assert_not_called()
        adb.assert_not_called()

    def test_each_occupied_state_counts_but_old_proposals_do_not(self):
        for state in ("running", "paused", "awaiting_approval", "queued"):
            self.assertTrue(walk.busy_tasks({"tasks": [{"state": state}]}))
        self.assertFalse(walk.busy_tasks({"tasks": [{"state": "done"}, {"state": "proposed"}]}))


class ConsentScenariosTest(unittest.TestCase):
    def test_missing_model_uses_no_real_provider(self):
        scenario = walk.load_scenarios(walk.SCENARIOS_V2)["missing_model"]
        self.assertEqual(scenario["steps"][0]["set_model"]["provider"], "walk-unconfigured")

    def test_proposals_enable_suggestions_and_use_unique_source_and_goal(self):
        v1 = walk.load_scenarios()["notification_proposal"]
        self.assertEqual(v1["suggestions"], "on")
        self.assertIn("RUN_ID", v1["notify"]["title"])
        for name in ("proposal_accept_home", "proposal_decline_home"):
            scenario = walk.load_scenarios(walk.SCENARIOS_V2)[name]
            steps = scenario["steps"]
            self.assertLess(steps.index({"suggestions": "on"}), next(i for i, s in enumerate(steps) if "notify" in s))
            for step in steps:
                if "notify" in step:
                    self.assertIn("RUN_ID", step["notify"]["title"])
            first = walk.substitute(scenario, {"RUN_ID": "first"})
            second = walk.substitute(scenario, {"RUN_ID": "second"})
            self.assertNotEqual(first["texts"], second["texts"])
            for reply in first["texts"]:
                self.assertIn("first", reply["triage"][0]["goal"])

    def test_off_scenario_checks_all_three_absences_after_batch_delay(self):
        steps = walk.load_scenarios(walk.SCENARIOS_V2)["suggestions_off_drops_notification"]["steps"]
        self.assertIn({"suggestions": "off"}, steps)
        self.assertIn({"start": "stop handling my notifications"}, steps)
        self.assertIn({"expect_rows": {}, "count": 0}, steps)
        self.assertIn({"expect_tasks": {}, "count": 0}, steps)
        self.assertIn({"expect_requests": 0}, steps)
        self.assertGreater(next(s["pause"] for s in steps if "pause" in s), 30)

    def test_suggestions_cli_sends_value(self):
        with patch.object(walk, "send", return_value=(True, "ok")) as send:
            self.assertEqual(walk.main(["suggestions", "off"]), 0)
        send.assert_called_once_with("suggestions", value="off")
