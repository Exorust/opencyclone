"""Mock LLM server for deterministic E2E smoke tests.

Implements a minimal OpenAI-compatible /v1/chat/completions endpoint
that returns scripted responses as SSE streams. No external dependencies.

Usage:
    # Standalone test:
    python3 -m tests.e2e.mock_llm_server

    # Programmatic:
    server = MockLLMServer(port=9876)
    server.load_responses([
        {"tool_call": {"thought": "...", "action_type": "open_app", "app_name": "设置"}},
        {"tool_call": {"thought": "done", "action_type": "finish", "summary": "已打开"}},
    ])
    server.start()
    # ... run test ...
    server.stop()

Phone walk path (tests/walk/):
    - Requests without ``stream: true`` (triage, the app builder) get the next
      scripted plain-text reply from a separate queue (``load_text_responses``).
      ``$KEY0``, ``$KEY1``... in a reply are replaced by the notification keys found
      in the triage prompt's data block. A ``{"triage": [{"match": {...}, "result": ...,
      "goal": ...}]}`` entry answers only a triage batch holding a matching notification
      (other batches, such as real notifications on the phone, get ``[]``).
    - A tool-call entry may carry ``"target": {"text": "Send"}`` (or ``"desc"``,
      ``"class"``, ``"clickable"``, ``"index"``). The server finds that element in the
      view tree of the last user message and fills ``x``/``y`` (0-1000) with its
      centre. The ``target`` key itself is never sent to the app. With ``"number": true``
      in the target the server fills ``index`` with the element's number from the list
      (a tap by number, F18.1) instead of a position.
    - ``"delay_ms": 1500`` on a tool-call entry makes the server wait that long before it
      answers (a slow model; the screen may change in the meantime).
    - ``POST /load`` with ``{"tool_calls": [...], "texts": [...]}`` replaces both queues.
    - ``POST /v1/audio/transcriptions`` (the internet recognizer, F17) answers ``{"text": ...}`` with the
      scripted transcription and records the request (parts, sizes, whether a key was sent; never the key).
      ``POST /transcription`` with ``{"text": "...", "status": 200}`` scripts it; ``GET /transcriptions``
      lists the recorded requests.
"""

from __future__ import annotations

import argparse
import email.parser
import email.policy
import json
import re
import signal
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import Any


@dataclass
class RequestRecord:
    """Captured request for debugging/artifacts."""
    timestamp: float
    method: str
    path: str
    body: dict
    response_index: int
    response_type: str  # "tool_call" | "error" | "exhausted" | "text" | "target_missing"
    detail: str = ""
    user_text: str = ""  # the last user message's text, cut to 4000 characters


# ── Phone walk helpers ────────────────────────────────────────────────────────

# One view-tree line from VdAccessibilityService.traverseNode:
#   [[n] ]<indent><Class>[ "<text>"][ (<desc>)][ attr,attr] (x1,y1)-(x2,y2)
#   [n]: the element's number, on elements that can be acted on (F18.1)
_BOUNDS = re.compile(r"\((\d+),(\d+)\)-\((\d+),(\d+)\)\s*$")
_NUMBER = re.compile(r"^\[(\d+)\]\s")
_ATTRS = {"editable", "scrollable", "checked", "unchecked", "clickable", "long-clickable"}


def last_user_text(body: dict) -> str:
    """The text parts of the last user message (the step's instruction and view tree)."""
    for message in reversed(body.get("messages") or []):
        if message.get("role") != "user":
            continue
        content = message.get("content")
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            return "\n".join(p.get("text", "") for p in content
                             if isinstance(p, dict) and p.get("type") == "text")
    return ""


def parse_view_tree(text: str) -> list[dict]:
    """Elements of the view tree in ``text``; lines that are not element lines are skipped."""
    nodes = []
    for line in text.splitlines():
        number = None
        n = _NUMBER.match(line)
        if n:
            number = int(n.group(1))
            line = line[n.end():]
        m = _BOUNDS.search(line)
        if not m:
            continue
        body = line[:m.start()].strip()
        if not body:
            continue
        cls, _, rest = body.partition(" ")
        if not re.fullmatch(r"[A-Za-z_$][\w$]*", cls):
            continue
        node_text = None
        if rest.startswith('"'):
            end = rest.rfind('"')
            if end > 0:
                node_text, rest = rest[1:end], rest[end + 1:].strip()
        attrs = set()
        words = rest.rsplit(" ", 1)
        if words and set(words[-1].split(",")) <= _ATTRS and words[-1]:
            attrs = set(words[-1].split(","))
            rest = words[0] if len(words) == 2 else ""
        desc = rest.strip()
        desc = desc[1:-1] if desc.startswith("(") and desc.endswith(")") else None
        x1, y1, x2, y2 = (int(v) for v in m.groups())
        nodes.append({"class": cls, "text": node_text, "desc": desc, "attrs": attrs,
                      "bounds": (x1, y1, x2, y2), "number": number})
    return nodes


def find_target(target: dict, text: str):
    """The element (parsed line) matching ``target``, or None."""
    def same(a, b):
        return a is not None and b is not None and a.strip().casefold() == b.strip().casefold()

    matches = []
    for node in parse_view_tree(text):
        if "text" in target and not same(node["text"], target["text"]):
            continue
        if "desc" in target and not same(node["desc"], target["desc"]):
            continue
        if "class" in target and node["class"] != target["class"]:
            continue
        if target.get("clickable") and "clickable" not in node["attrs"]:
            continue
        matches.append(node)
    index = int(target.get("index", 0))
    if index >= len(matches):
        return None
    return matches[index]


def resolve_target(target: dict, text: str):
    """Centre (x, y) in 0-1000 of the element matching ``target``, or None."""
    node = find_target(target, text)
    if node is None:
        return None
    x1, y1, x2, y2 = node["bounds"]
    return (x1 + x2) // 2, (y1 + y2) // 2


def resolve_number(target: dict, text: str):
    """The element number of the element matching ``target``, or None (no match, or it has no number)."""
    node = find_target(target, text)
    return None if node is None else node.get("number")


def notification_items(prompt: str) -> list[dict]:
    """Notifications (key, package, title, text) in the triage prompt's BEGIN_<d> ... END_<d> data block."""
    m = re.search(r"BEGIN_(NOTIFICATION_DATA_[\w-]+)\s*(.*?)\s*END_\1", prompt, re.S)
    if not m:
        return []
    try:
        data = json.loads(m.group(2))
    except ValueError:
        return []
    return [item for item in data if isinstance(item, dict)] if isinstance(data, list) else []


def notification_keys(prompt: str) -> list[str]:
    return [str(item.get("key", "")) for item in notification_items(prompt)]


def triage_reply(rules: list[dict], items: list[dict]):
    """Triage JSON for the items each rule's ``match`` selects (all given fields equal), or None if none match.

    A rule: {"match": {"title": "Alex", "package": "..."}, "result": "propose", "goal": "..."}.
    Items no rule matches are left out, which the app treats as "summarize".
    """
    out = []
    for item in items:
        for rule in rules:
            match = rule.get("match") or {}
            if all(str(item.get(k, "")) == str(v) for k, v in match.items()):
                entry = {"key": item.get("key", ""), "result": rule.get("result", "summarize")}
                for extra in ("goal", "rule_text"):
                    if extra in rule:
                        entry[extra] = rule[extra]
                out.append(entry)
                break
    return json.dumps(out) if out else None


def fill_keys(text: str, keys: list[str]) -> str:
    """Replace $KEY0, $KEY1, ... with the keys, as JSON string content."""
    def sub(m):
        i = int(m.group(1))
        return json.dumps(keys[i])[1:-1] if i < len(keys) else m.group(0)
    return re.sub(r"\$KEY(\d+)", sub, text)


DEFAULT_TRANSCRIPTION = "open the clock app"


def parse_multipart(content_type: str, body: bytes) -> dict:
    """Form parts of a multipart/form-data body: name -> {"filename", "content_type", "data"}."""
    message = email.parser.BytesParser(policy=email.policy.HTTP).parsebytes(
        b"Content-Type: " + content_type.encode() + b"\r\n\r\n" + body)
    parts = {}
    for part in message.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if name:
            parts[name] = {"filename": part.get_filename(), "content_type": part.get_content_type(),
                           "data": part.get_payload(decode=True) or b""}
    return parts


def wav_info(data: bytes) -> dict:
    """Rate, channels, bits, and sample count of a 16-bit PCM WAV with a 44-byte header."""
    if len(data) < 44 or data[0:4] != b"RIFF" or data[8:12] != b"WAVE" or data[36:40] != b"data":
        return {"valid": False, "bytes": len(data)}
    le = lambda a, b: int.from_bytes(data[a:b], "little")
    size = le(40, 44)
    return {"valid": size == len(data) - 44, "bytes": len(data), "rate": le(24, 28), "channels": le(22, 24),
            "bits": le(34, 36), "samples": size // 2}


class MockLLMHandler(BaseHTTPRequestHandler):
    """Handles OpenAI-compatible chat completions with SSE streaming."""

    server: "MockLLMHTTPServer"

    def do_GET(self):
        if self.path == "/health":
            self._respond_json(200, {"status": "ok", "queued": len(self.server.response_queue),
                                     "queued_text": len(self.server.text_queue)})
        elif self.path == "/requests":
            records = [
                {"timestamp": r.timestamp, "method": r.method, "path": r.path,
                 "response_index": r.response_index, "response_type": r.response_type,
                 "detail": r.detail, "user_text": r.user_text}
                for r in self.server.request_log
            ]
            self._respond_json(200, {"requests": records, "total": len(records)})
        elif self.path == "/transcriptions":
            self._respond_json(200, {"requests": self.server.transcriptions, "total": len(self.server.transcriptions)})
        else:
            self._respond_json(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/reset":
            self.server.response_queue.clear()
            self.server.text_queue.clear()
            self.server.request_log.clear()
            self.server.transcriptions.clear()
            self.server.transcription = {"text": DEFAULT_TRANSCRIPTION, "status": 200}
            self._respond_json(200, {"status": "reset"})
            return

        if self.path == "/load":
            try:
                script = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
                tool_calls = list(script.get("tool_calls") or [])
                texts = [t if isinstance(t, dict) else {"text": str(t)} for t in (script.get("texts") or [])]
            except (ValueError, AttributeError, TypeError) as e:
                self._respond_json(400, {"error": {"message": f"bad script: {e}", "type": "invalid_request_error"}})
                return
            self.server.response_queue.clear()
            self.server.response_queue.extend(tool_calls)
            self.server.text_queue.clear()
            self.server.text_queue.extend(texts)
            self._respond_json(200, {"status": "loaded", "tool_calls": len(tool_calls), "texts": len(texts)})
            return

        if self.path == "/transcription":
            try:
                script = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
                self.server.transcription = {"text": str(script.get("text", DEFAULT_TRANSCRIPTION)),
                                             "status": int(script.get("status", 200))}
            except (ValueError, AttributeError, TypeError) as e:
                self._respond_json(400, {"error": {"message": f"bad script: {e}"}})
                return
            self._respond_json(200, {"status": "loaded", **self.server.transcription})
            return

        if self.path.endswith("/audio/transcriptions"):
            self._transcribe()
            return

        if not self.path.endswith("/chat/completions"):
            self._respond_json(404, {"error": f"unknown endpoint: {self.path}"})
            return

        content_length = int(self.headers.get("Content-Length", 0))
        body_raw = self.rfile.read(content_length)

        try:
            body = json.loads(body_raw) if body_raw else {}
        except json.JSONDecodeError:
            self._respond_json(400, {"error": {"message": "invalid JSON", "type": "invalid_request_error"}})
            return

        if not body.get("messages"):
            self._respond_json(400, {"error": {"message": "messages required", "type": "invalid_request_error"}})
            return

        if not body.get("stream", False):
            self._reply_text(body)
            return

        response_index = len(self.server.request_log)

        if not self.server.response_queue:
            record = RequestRecord(
                timestamp=time.time(), method="POST", path=self.path,
                body=body, response_index=response_index, response_type="exhausted"
            )
            self.server.request_log.append(record)
            self._respond_json(500, {"error": {"message": "mock response queue exhausted", "type": "server_error"}})
            return

        next_response = self.server.response_queue.popleft()

        if "error" in next_response:
            record = RequestRecord(
                timestamp=time.time(), method="POST", path=self.path,
                body=body, response_index=response_index, response_type="error"
            )
            self.server.request_log.append(record)
            err = next_response["error"]
            status = err.get("status", 500)
            err_body = err.get("body")
            if isinstance(err_body, str):
                err_body = json.loads(err_body)
            elif err_body is None:
                err_body = {"error": {"message": "mock error", "type": "server_error"}}
            self._respond_json(status, err_body)
            return

        tool_call = dict(next_response.get("tool_call", {}))
        response_type, detail = "tool_call", tool_call.get("action_type", "")
        target = next_response.get("target")
        if next_response.get("delay_ms"):
            time.sleep(min(float(next_response["delay_ms"]), 10_000) / 1000.0)
        if target and target.get("number"):
            number = resolve_number(target, last_user_text(body))
            if number is None:
                response_type = "target_missing"
                detail = f"numbered target {json.dumps(target)} not in the element list"
                tool_call = {"thought": f"walk mock: {detail}", "action_type": "fail",
                             "reason": f"walk mock: {detail}"}
            else:
                tool_call["index"] = number
                detail = f"{detail} {json.dumps(target)} -> [{number}]"
        elif target:
            point = resolve_target(target, last_user_text(body))
            if point is None:
                response_type = "target_missing"
                detail = f"target {json.dumps(target)} not in the view tree"
                tool_call = {"thought": f"walk mock: {detail}", "action_type": "fail",
                             "reason": f"walk mock: {detail}"}
            else:
                tool_call["x"], tool_call["y"] = point
                detail = f"{detail} {json.dumps(target)} -> ({point[0]},{point[1]})"

        record = RequestRecord(
            timestamp=time.time(), method="POST", path=self.path,
            body=body, response_index=response_index, response_type=response_type, detail=detail,
            user_text=last_user_text(body)[:4000]
        )
        self.server.request_log.append(record)

        self._stream_tool_call(tool_call)

    def _transcribe(self):
        """The internet recognizer's one request: record it, answer the scripted text."""
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        content_type = self.headers.get("Content-Type", "")
        record = {"timestamp": time.time(), "path": self.path, "content_type": content_type.split(";")[0],
                  "authorization": (self.headers.get("Authorization") or "").startswith("Bearer "),
                  "body_bytes": len(body)}
        if content_type.startswith("multipart/form-data"):
            parts = parse_multipart(content_type, body)
            record["parts"] = sorted(parts)
            if "file" in parts:
                record["file"] = {"filename": parts["file"]["filename"],
                                  "content_type": parts["file"]["content_type"], **wav_info(parts["file"]["data"])}
            for name in ("model", "language"):
                if name in parts:
                    record[name] = parts[name]["data"].decode("utf-8", "replace")
        script = self.server.transcription
        record["status"] = script["status"]
        self.server.transcriptions.append(record)
        if script["status"] != 200:
            self._respond_json(script["status"], {"error": {"message": "scripted failure"}})
        elif "file" not in record:
            self._respond_json(400, {"error": {"message": "file part required"}})
        else:
            self._respond_json(200, {"text": script["text"]})

    def _reply_text(self, body: dict):
        """Non-streamed completion: the next scripted plain-text reply.

        A ``{"triage": [...]}`` entry is only used by a triage request whose data block
        holds a matching notification; other triage requests (real notifications on the
        phone) get ``[]`` and leave the entry queued.
        """
        response_index = len(self.server.request_log)
        prompt = last_user_text(body)
        items = notification_items(prompt)
        entry = self.server.text_queue[0] if self.server.text_queue else None
        if entry is not None and "triage" in entry:
            reply = triage_reply(entry["triage"], items)
            if reply is None:
                text, kind = "[]", "text_unmatched"
            else:
                self.server.text_queue.popleft()
                text, kind = reply, "text"
        elif entry is not None:
            self.server.text_queue.popleft()
            text, kind = fill_keys(str(entry.get("text", "")), [i.get("key", "") for i in items]), "text"
        else:
            self.server.request_log.append(RequestRecord(
                timestamp=time.time(), method="POST", path=self.path, body=body,
                response_index=response_index, response_type="exhausted", detail="text queue empty",
                user_text=prompt[:4000]))
            self._respond_json(500, {"error": {"message": "mock text queue exhausted", "type": "server_error"}})
            return
        self.server.request_log.append(RequestRecord(
            timestamp=time.time(), method="POST", path=self.path, body=body,
            response_index=response_index, response_type=kind, detail=text[:200], user_text=prompt[:4000]))
        self._respond_json(200, {
            "id": "chatcmpl-mock", "object": "chat.completion", "model": body.get("model", "mock"),
            "choices": [{"index": 0, "message": {"role": "assistant", "content": text},
                         "finish_reason": "stop"}],
        })

    def _stream_tool_call(self, tool_call: dict):
        """Stream a tool_call response as SSE matching OpenAI format."""
        arguments = json.dumps(tool_call, ensure_ascii=False)

        chunks = [
            {"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "call_mock", "type": "function", "function": {"name": "phone_action", "arguments": ""}}]}, "finish_reason": None}]},
            {"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": arguments}}]}, "finish_reason": None}]},
            {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
        ]

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        for chunk in chunks:
            line = f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n"
            self.wfile.write(line.encode())
            self.wfile.flush()

        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def _respond_json(self, status: int, body: dict):
        payload = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        pass  # suppress default stderr logging


class MockLLMHTTPServer(HTTPServer):
    """HTTPServer with shared state for mock responses."""
    allow_reuse_address = True
    response_queue: deque
    text_queue: deque
    request_log: list[RequestRecord]
    transcriptions: list[dict]
    transcription: dict

    def __init__(self, port: int, host: str = "0.0.0.0"):
        self.response_queue = deque()
        self.text_queue = deque()
        self.request_log = []
        self.transcriptions = []
        self.transcription = {"text": DEFAULT_TRANSCRIPTION, "status": 200}
        super().__init__((host, port), MockLLMHandler)


class MockLLMServer:
    """High-level wrapper: start/stop mock server in a background thread."""

    def __init__(self, port: int = 9876, host: str = "0.0.0.0"):
        self.port = port
        self.host = host
        self._server: MockLLMHTTPServer | None = None
        self._thread: threading.Thread | None = None

    def load_responses(self, responses: list[dict]):
        """Load scripted responses into the queue."""
        if self._server is None:
            raise RuntimeError("Server not started")
        self._server.response_queue.clear()
        self._server.response_queue.extend(responses)

    def load_text_responses(self, texts: list):
        """Load scripted plain-text replies for non-streamed requests."""
        if self._server is None:
            raise RuntimeError("Server not started")
        self._server.text_queue.clear()
        self._server.text_queue.extend(t if isinstance(t, dict) else {"text": str(t)} for t in texts)

    def start(self):
        """Start server in background thread."""
        self._server = MockLLMHTTPServer(self.port, self.host)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def stop(self):
        """Shutdown server."""
        if self._server:
            self._server.shutdown()
            self._server.server_close()
            self._server = None
        if self._thread:
            self._thread.join(timeout=5)
            self._thread = None

    def get_request_log(self) -> list[dict]:
        """Return request log as serializable dicts."""
        if not self._server:
            return []
        return [
            {"timestamp": r.timestamp, "method": r.method, "path": r.path,
             "response_index": r.response_index, "response_type": r.response_type,
             "detail": r.detail}
            for r in self._server.request_log
        ]

    @property
    def pending_count(self) -> int:
        return len(self._server.response_queue) if self._server else 0


def _main(argv=None):
    parser = argparse.ArgumentParser(description="Mock OpenAI-compatible model server")
    parser.add_argument("--port", type=int, default=9876)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--empty", action="store_true",
                        help="start with empty queues (load them with POST /load)")
    parser.add_argument("--max-seconds", type=int, default=0,
                        help="exit after this many seconds (0 = run until interrupted)")
    args = parser.parse_args(argv)

    print(f"Starting mock LLM server on {args.host}:{args.port}...", flush=True)
    server = MockLLMServer(args.port, args.host)
    server.start()
    if not args.empty:
        server.load_responses([
            {"tool_call": {"thought": "打开设置", "action_type": "open_app", "app_name": "设置"}},
            {"tool_call": {"thought": "完成", "action_type": "finish", "summary": "已打开设置"}},
        ])
        print("Mock server running. Try:")
        print(f"  curl http://localhost:{args.port}/health")
        print(f"  curl -X POST http://localhost:{args.port}/v1/chat/completions \\")
        print('    -H "Content-Type: application/json" \\')
        print('    -d \'{"messages":[{"role":"user","content":"test"}],"stream":true}\'')
    def _terminate(*_):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, _terminate)
    deadline = time.time() + args.max_seconds if args.max_seconds > 0 else None
    try:
        while deadline is None or time.time() < deadline:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        server.stop()
        print("\nStopped.", flush=True)


if __name__ == "__main__":
    _main()
