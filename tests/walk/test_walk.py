"""Offline tests for the walk harness: the mock server's phone path and walk.py's parsing and checks.

Standard library only:  python3 -m unittest discover -s tests/walk
"""

from __future__ import annotations

import json
import re
import sys
import unittest
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tests" / "walk"))

from tests.e2e import mock_llm_server as mls  # noqa: E402
import walk  # noqa: E402

TOOL_SCHEMA = ROOT / "android/app/src/main/java/ai/opencyclone/llm/ToolSchema.kt"

# A step message as AgentEngine.buildUserMessage sends it (instruction, header, view tree).
VIEW_TREE_MESSAGE = (
    "Open Gate Test and tap Send\n\n### Current UI Elements ###\n"
    "FrameLayout (0,0)-(1000,1000)\n"
    "  LinearLayout (0,0)-(1000,1000)\n"
    "    EditText \"Message\" editable (40,60)-(960,110)\n"
    "    Button \"Send\" clickable (40,120)-(960,180)\n"
    "    TextView \"Sent: 0\" (40,190)-(300,220)\n"
    "    ImageButton clickable (40,230)-(200,300)\n"
    "    Button \"Chat (beta)\" (Open the chat) clickable (40,310)-(960,370)\n"
)


# The same screen as the engine lists it since F18.1: elements that can be acted on start with [n].
NUMBERED_MESSAGE = (
    "Open Gate Test and tap Send\n\n### Current UI Elements ###\n"
    "FrameLayout (0,0)-(1000,1000)\n"
    "[1]     EditText \"Message\" editable (40,60)-(960,110)\n"
    "[2]     Button \"SEND\" clickable (40,120)-(960,180)\n"
    "    TextView \"Sent: 0\" (40,190)-(300,220)\n"
    "[3]     ImageButton clickable,long-clickable (40,230)-(200,300)\n"
    "(keyboard is open; type with type_text, do not tap its keys)\n"
)


def step_body(text: str, stream: bool = True) -> dict:
    return {"model": "walk-model", "stream": stream, "messages": [
        {"role": "system", "content": "system prompt"},
        {"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,AAAA"}},
            {"type": "text", "text": text}]}]}


def triage_prompt(items: list[dict]) -> str:
    delimiter = "NOTIFICATION_DATA_1234abcd-0000-1111-2222-333344445555"
    return ("You classify notifications...\n\nClassify each notification as ignore, summarize, hide, or propose.\n"
            'Return only a JSON array, one object per key: {"key":"...","result":"...","goal":"..."}.\n'
            f"BEGIN_{delimiter}\n{json.dumps(items)}\nEND_{delimiter}")


class ViewTreeTest(unittest.TestCase):
    def test_parses_element_lines(self):
        nodes = mls.parse_view_tree(VIEW_TREE_MESSAGE)
        send = next(n for n in nodes if n["text"] == "Send")
        self.assertEqual(send["class"], "Button")
        self.assertEqual(send["bounds"], (40, 120, 960, 180))
        self.assertIn("clickable", send["attrs"])
        image = next(n for n in nodes if n["class"] == "ImageButton")
        self.assertIsNone(image["text"])
        chat = next(n for n in nodes if n["text"] == "Chat (beta)")
        self.assertEqual(chat["desc"], "Open the chat")

    def test_resolves_targets_to_centres(self):
        self.assertEqual(mls.resolve_target({"text": "send"}, VIEW_TREE_MESSAGE), (500, 150))
        self.assertEqual(mls.resolve_target({"class": "ImageButton"}, VIEW_TREE_MESSAGE), (120, 265))
        self.assertEqual(mls.resolve_target({"desc": "Open the chat"}, VIEW_TREE_MESSAGE), (500, 340))
        self.assertIsNone(mls.resolve_target({"text": "Sent: 0", "clickable": True}, VIEW_TREE_MESSAGE))
        self.assertIsNone(mls.resolve_target({"text": "Pay"}, VIEW_TREE_MESSAGE))
        self.assertIsNone(mls.resolve_target({"class": "Button", "index": 5}, VIEW_TREE_MESSAGE))

    def test_parses_numbered_lines(self):
        nodes = mls.parse_view_tree(NUMBERED_MESSAGE)
        self.assertEqual([n["number"] for n in nodes], [None, 1, 2, None, 3])
        self.assertEqual(nodes[2]["text"], "SEND")
        self.assertEqual(nodes[4]["attrs"], {"clickable", "long-clickable"})
        self.assertEqual(mls.resolve_target({"text": "Send"}, NUMBERED_MESSAGE), (500, 150))
        self.assertEqual(mls.resolve_number({"text": "Send"}, NUMBERED_MESSAGE), 2)
        self.assertEqual(mls.resolve_number({"class": "ImageButton"}, NUMBERED_MESSAGE), 3)
        self.assertIsNone(mls.resolve_number({"text": "Sent: 0"}, NUMBERED_MESSAGE))
        self.assertIsNone(mls.resolve_number({"text": "Pay"}, NUMBERED_MESSAGE))

    def test_last_user_text_reads_string_and_parts(self):
        self.assertEqual(mls.last_user_text({"messages": [{"role": "user", "content": "hi"}]}), "hi")
        self.assertIn("Send", mls.last_user_text(step_body(VIEW_TREE_MESSAGE)))


class ServerCase(unittest.TestCase):
    """A mock server on a free port for each test, with JSON helpers. No tests of its own."""

    def setUp(self):
        self.server = mls.MockLLMServer(port=0, host="127.0.0.1")
        self.server.start()
        self.base = f"http://127.0.0.1:{self.server._server.server_address[1]}"

    def tearDown(self):
        self.server.stop()

    def post(self, path: str, body: dict):
        request = urllib.request.Request(self.base + path, data=json.dumps(body).encode(), method="POST",
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.read().decode()
        except urllib.error.HTTPError as e:
            with e:
                return e.code, e.read().decode()

    def get(self, path: str) -> dict:
        with urllib.request.urlopen(self.base + path, timeout=5) as response:
            return json.loads(response.read())


class MockServerTest(ServerCase):

    def streamed_arguments(self, body: dict) -> dict:
        status, text = self.post("/v1/chat/completions", body)
        self.assertEqual(status, 200)
        args = ""
        for line in text.splitlines():
            if line.startswith("data: ") and line != "data: [DONE]":
                for call in json.loads(line[6:])["choices"][0]["delta"].get("tool_calls", []):
                    args += call.get("function", {}).get("arguments", "")
        return json.loads(args)

    def test_text_reply_for_non_streamed_request(self):
        self.server.load_text_responses(["[]", {"text": "<html></html>"}])
        status, text = self.post("/v1/chat/completions", step_body("build me a timer", stream=False))
        self.assertEqual(status, 200)
        reply = json.loads(text)
        self.assertEqual(reply["choices"][0]["message"]["content"], "[]")
        self.assertEqual(reply["choices"][0]["finish_reason"], "stop")
        status, text = self.post("/v1/chat/completions", step_body("again", stream=False))
        self.assertEqual(json.loads(text)["choices"][0]["message"]["content"], "<html></html>")
        status, _ = self.post("/v1/chat/completions", step_body("one more", stream=False))
        self.assertEqual(status, 500)
        self.assertEqual([r["response_type"] for r in self.get("/requests")["requests"]], ["text", "text", "exhausted"])

    def test_key_placeholders_take_the_prompt_keys(self):
        self.server.load_text_responses(['[{"key":"$KEY1","result":"propose","goal":"Reply"}]'])
        prompt = triage_prompt([{"key": "k|a", "package": "p", "title": "A", "text": "x"},
                                {"key": "k|\"b\"", "package": "p", "title": "B", "text": "y"}])
        _, text = self.post("/v1/chat/completions", step_body(prompt, stream=False))
        content = json.loads(json.loads(text)["choices"][0]["message"]["content"])
        self.assertEqual(content, [{"key": 'k|"b"', "result": "propose", "goal": "Reply"}])

    def test_triage_entry_waits_for_a_matching_notification(self):
        self.post("/load", {"texts": [{"triage": [{"match": {"title": "Alex"}, "result": "propose",
                                                   "goal": "Reply to Alex"}]}]})
        other = triage_prompt([{"key": "real|1", "package": "com.mail", "title": "News", "text": "x"}])
        _, text = self.post("/v1/chat/completions", step_body(other, stream=False))
        self.assertEqual(json.loads(text)["choices"][0]["message"]["content"], "[]")
        self.assertEqual(self.get("/health")["queued_text"], 1)
        ours = triage_prompt([{"key": "real|2", "package": "com.mail", "title": "News", "text": "x"},
                              {"key": "walk|p|1", "package": "p", "title": "Alex", "text": "Dinner?"}])
        _, text = self.post("/v1/chat/completions", step_body(ours, stream=False))
        content = json.loads(json.loads(text)["choices"][0]["message"]["content"])
        self.assertEqual(content, [{"key": "walk|p|1", "result": "propose", "goal": "Reply to Alex"}])
        self.assertEqual(self.get("/health")["queued_text"], 0)

    def test_load_and_target_fill_coordinates(self):
        status, text = self.post("/load", {"tool_calls": [
            {"tool_call": {"thought": "Tap Send", "action_type": "tap"}, "target": {"text": "Send"}},
            {"tool_call": {"thought": "Tap Pay", "action_type": "tap"}, "target": {"text": "Pay"}},
        ]})
        self.assertEqual(status, 200)
        args = self.streamed_arguments(step_body(VIEW_TREE_MESSAGE))
        self.assertEqual(args, {"thought": "Tap Send", "action_type": "tap", "x": 500, "y": 150})
        missing = self.streamed_arguments(step_body(VIEW_TREE_MESSAGE))
        self.assertEqual(missing["action_type"], "fail")
        self.assertIn("Pay", missing["reason"])
        log = self.get("/requests")["requests"]
        self.assertEqual([r["response_type"] for r in log], ["tool_call", "target_missing"])
        self.assertIn("Send", log[0]["user_text"])

    def test_numbered_target_fills_index(self):
        self.post("/load", {"tool_calls": [
            {"tool_call": {"thought": "Tap Send", "action_type": "tap"}, "target": {"text": "Send", "number": True},
             "delay_ms": 50},
            {"tool_call": {"thought": "Tap text", "action_type": "tap"}, "target": {"text": "Sent: 0", "number": True}},
        ]})
        args = self.streamed_arguments(step_body(NUMBERED_MESSAGE))
        self.assertEqual(args, {"thought": "Tap Send", "action_type": "tap", "index": 2})
        missing = self.streamed_arguments(step_body(NUMBERED_MESSAGE))
        self.assertEqual(missing["action_type"], "fail")
        log = self.get("/requests")["requests"]
        self.assertEqual([r["response_type"] for r in log], ["tool_call", "target_missing"])

    def test_existing_streaming_path_unchanged(self):
        self.server.load_responses([{"tool_call": {"thought": "done", "action_type": "finish"}}])
        self.assertEqual(self.streamed_arguments(step_body("x")), {"thought": "done", "action_type": "finish"})
        status, _ = self.post("/v1/chat/completions", step_body("x"))
        self.assertEqual(status, 500)

    def test_reset_clears_both_queues(self):
        self.post("/load", {"tool_calls": [{"tool_call": {"thought": "t", "action_type": "wait"}}], "texts": ["[]"]})
        self.post("/reset", {})
        health = self.get("/health")
        self.assertEqual((health["queued"], health["queued_text"]), (0, 0))


class TranscriptionEndpointTest(ServerCase):
    """The internet recognizer's endpoint (F17): one multipart request with a WAV, answered with scripted text."""

    @staticmethod
    def wav(samples: int, rate: int = 16000) -> bytes:
        data = b"\x01\x00" * samples
        header = (b"RIFF" + (36 + len(data)).to_bytes(4, "little") + b"WAVEfmt " + (16).to_bytes(4, "little")
                  + (1).to_bytes(2, "little") + (1).to_bytes(2, "little") + rate.to_bytes(4, "little")
                  + (rate * 2).to_bytes(4, "little") + (2).to_bytes(2, "little") + (16).to_bytes(2, "little")
                  + b"data" + len(data).to_bytes(4, "little"))
        return header + data

    def transcribe(self, parts: dict, key: str = "walk"):
        boundary = "walkboundary"
        body = b""
        for name, value in parts.items():
            if name == "file":
                body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                         f"Content-Type: audio/wav\r\n\r\n").encode() + value + b"\r\n"
            else:
                body += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode()
        body += f"--{boundary}--\r\n".encode()
        request = urllib.request.Request(self.base + "/v1/audio/transcriptions", data=body, method="POST", headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}", "Authorization": f"Bearer {key}"})
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.loads(e.read() or b"{}")

    def test_answers_the_scripted_text_and_records_the_request(self):
        self.assertEqual(self.post("/transcription", {"text": "call Sam"})[0], 200)
        status, reply = self.transcribe({"file": self.wav(1600), "model": "gpt-4o-transcribe", "language": "en"})
        self.assertEqual((status, reply), (200, {"text": "call Sam"}))
        record = self.get("/transcriptions")["requests"][-1]
        self.assertEqual(record["parts"], ["file", "language", "model"])
        self.assertTrue(record["authorization"])
        self.assertEqual(record["model"], "gpt-4o-transcribe")
        self.assertEqual(record["file"]["filename"], "speech.wav")
        self.assertEqual(record["file"]["content_type"], "audio/wav")
        self.assertEqual({k: record["file"][k] for k in ("valid", "rate", "channels", "bits", "samples")},
                         {"valid": True, "rate": 16000, "channels": 1, "bits": 16, "samples": 1600})
        self.assertNotIn("walk", json.dumps(record))  # the key itself is never recorded

    def test_default_text_scripted_failures_and_reset(self):
        self.assertEqual(self.transcribe({"file": self.wav(10), "model": "m"})[1], {"text": mls.DEFAULT_TRANSCRIPTION})
        self.post("/transcription", {"status": 401})
        self.assertEqual(self.transcribe({"file": self.wav(10), "model": "m"})[0], 401)
        self.assertEqual(self.get("/transcriptions")["total"], 2)
        self.post("/reset", {})
        self.assertEqual(self.get("/transcriptions")["total"], 0)
        self.assertEqual(self.transcribe({"model": "m"})[0], 400)  # no file part

    def test_chat_requests_are_not_transcriptions(self):
        self.post("/v1/chat/completions", step_body("hi", stream=False))
        self.assertEqual(self.get("/transcriptions")["total"], 0)


class WalkDriverTest(unittest.TestCase):
    def test_parse_broadcast(self):
        out = ('Broadcasting: Intent { act=ai.opencyclone.debug.CMD flg=0x10000020 cmp=x/y (has extras) }\n'
               'Broadcast completed: result=0, data="{"request_id":"ab","tasks":[]}"\n')
        self.assertEqual(walk.parse_broadcast(out), (0, '{"request_id":"ab","tasks":[]}'))
        self.assertEqual(walk.parse_broadcast("Broadcast completed: result=1, data=\"the phone is locked\""),
                         (1, "the phone is locked"))
        self.assertEqual(walk.parse_broadcast("Broadcast completed: result=0"), (0, None))
        self.assertEqual(walk.parse_broadcast("error: no devices"), (None, None))

    def test_broadcast_command_quotes_for_the_device_shell(self):
        line = walk.broadcast_command("answer", {"text": "Alex's phone; rm -rf /"})
        self.assertIn("--es cmd answer", line)
        self.assertIn("'Alex'\"'\"'s phone; rm -rf /'", line)
        self.assertIn("ai.opencyclone.debug.DebugCommandReceiver", line)

    def test_parse_counters(self):
        self.assertEqual(walk.parse_counters("sent=3 pressed=1 touched=0\n"), {"sent": 3, "pressed": 1, "touched": 0})
        self.assertEqual(walk.parse_counters("sent=0 pressed=0 touched=0 moved=1 slow=2 still=3\n"),
                         {"sent": 0, "pressed": 0, "touched": 0, "moved": 1, "slow": 2, "still": 3})
        xml = '<map>\n<int name="sent" value="2" />\n<int name="pressed" value="0" />\n</map>'
        self.assertEqual(walk.parse_counters(xml), {"sent": 2, "pressed": 0})
        self.assertIsNone(walk.parse_counters("No such file"))

    def test_pick_task(self):
        state = {"tasks": [{"id": 3, "origin": "notification", "goal": "Reply", "state": "proposed"},
                           {"id": 4, "origin": "user", "goal": "x", "state": "done"}]}
        self.assertEqual(walk.pick_task(state, 3, {})["id"], 4)
        self.assertIsNone(walk.pick_task(state, 4, {}))
        notify = {"notify": {"package": "p", "text": "t"}, "expect": {"task": {"goal": "Reply"}}}
        self.assertEqual(walk.pick_task(state, 4, notify)["id"], 3)

    def test_voice_engine_sends_only_the_options_given(self):
        args = walk.main.__globals__["argparse"].Namespace(value="service", voice_model=None,
                                                           address="http://localhost:8765/v1", service_model=None, key="k")
        self.assertEqual(walk.voice_engine_extras(args),
                         {"value": "service", "address": "http://localhost:8765/v1", "key": "k"})
        args = walk.main.__globals__["argparse"].Namespace(value=None, voice_model="en_20m_int8", address=None,
                                                           service_model=None, key=None)
        self.assertEqual(walk.voice_engine_extras(args), {"voice_model": "en_20m_int8"})

    def test_check_reports_each_expectation(self):
        expect = {"approval": {"label": "Send", "approve_offered": True},
                  "task": {"state": "done"}, "counters_delta": {"sent": 1}, "model_saw": "User's answer: Alex",
                  "script_used_up": True, "question": "Which", "approve_refused": True}
        seen = {"approval": {"label": "Send", "approve_offered": True}, "task": {"state": "done"},
                "counters_before": {"sent": 2}, "counters_after": {"sent": 3},
                "requests": [{"user_text": "User's answer: Alex\nPlease continue"}], "queued_left": 0,
                "question": "Which friend?", "approve_first": (False, "Approve is not offered")}
        results = walk.check(expect, seen)
        self.assertTrue(all(ok for ok, _ in results), results)
        seen["counters_after"] = {"sent": 2}
        seen["approval"] = None
        failed = [text for ok, text in walk.check(expect, seen) if not ok]
        self.assertEqual(len(failed), 2, failed)
        self.assertEqual(walk.check({"approval": None}, {"approval": None}), [(True, "no approval hold (saw null)")])


class StepsCheckTest(unittest.TestCase):
    def test_step_facts_match_keys_and_wait_bounds(self):
        steps = [{"step": 2, "action": "tap", "by_number": True, "label": "LOAD", "wait_ms": 2100, "success": True},
                 {"step": 3, "action": "finish", "feedback": "Look", "wait_ms": None, "success": False}]
        expect = {"steps": [{"action": "tap", "label": "LOAD", "wait_ms_min": 1800, "wait_ms_max": 3000},
                            {"action": "finish", "success": False}]}
        self.assertTrue(all(ok for ok, _ in walk.check(expect, {"steps": steps})))
        failed = walk.check({"steps": [{"action": "tap", "wait_ms_max": 800}, {"action": "finish", "wait_ms_min": 1}]},
                            {"steps": steps})
        self.assertEqual([ok for ok, _ in failed], [False, False])


class ScenarioFileTest(unittest.TestCase):
    """Every scripted tool call uses only phone_action fields and action types that ToolSchema.kt declares."""

    def test_scenarios_match_the_tool_schema(self):
        schema = TOOL_SCHEMA.read_text()
        enum_block = schema[schema.index('put("action_type"'):schema.index('put("description", LlmPrompts.paramDescription("action_type"))')]
        action_types = set(re.findall(r'put\("(\w+)"\)', enum_block))
        self.assertIn("tap", action_types)
        scenarios = walk.load_scenarios()
        self.assertEqual(set(scenarios), {"send_approve", "send_reject", "icon_only_unreadable",
                                          "finish_without_commit", "ask_owner", "notification_proposal",
                                          "tap_by_number", "unknown_number", "moving_button", "changed_label",
                                          "no_elements_position", "send_by_number", "slow_screen", "never_still",
                                          "read_only_field", "going_in_circles", "link_web", "link_sms",
                                          "link_call", "link_blocked", "link_unknown", "type_without_field"})
        for name, scenario in scenarios.items():
            self.assertTrue(scenario.get("start") or scenario.get("notify"), name)
            for entry in scenario.get("tool_calls", []):
                self.assertLessEqual(set(entry), {"tool_call", "target", "delay_ms"}, name)
                call = entry["tool_call"]
                self.assertIn(call["action_type"], action_types, name)
                self.assertIn("thought", call, name)
                for key in call:
                    self.assertIn(f'"{key}"', schema, f"{name}: {key} is not a phone_action field")
                if call["action_type"] == "tap":
                    self.assertTrue("target" in entry or {"x", "y"} <= set(call) or "index" in call, name)


if __name__ == "__main__":
    unittest.main()
