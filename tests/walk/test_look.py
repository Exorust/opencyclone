"""Offline tests for look.py: the PNG reader, dp conversion, adb output parsing, screen detection, and one
passing and one failing case for every rule kind.

Standard library only:  python3 -m unittest discover -s tests/walk

The fixture dumps in fixtures/look/ are drawn at density 160 (1 px = 1 dp) on a 412 x 892 screen. Each node may
carry a `paint` attribute ("bg=<token|#hex> fg=<token|#hex>") that only these tests read: the painter fills the
node with bg and draws a solid bar in fg where the text would be. Real uiautomator dumps have no such attribute.
"""

from __future__ import annotations

import contextlib
import io
import json
import random
import re
import shutil
import struct
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import look  # noqa: E402

FIX = HERE / "fixtures" / "look"
DOC = look.load_rules()
TOKENS = DOC["tokens"]
W, H = 412, 892
HOME = "ai.opencyclone.standard/ai.opencyclone.ui.ControlPanelActivity"


def colour_of(value: str) -> tuple:
    return look.hex_rgb(value if value.startswith("#") else TOKENS[value])


def scale_xml(xml_text: str, factor: float) -> str:
    return re.sub(r"\[(-?\d+),(-?\d+)\]", lambda m: "[%d,%d]" % (round(int(m.group(1)) * factor),
                                                                 round(int(m.group(2)) * factor)), xml_text)


def paint(xml_text: str, width: int = W, height: int = H, flat: bool = False, wash: str = "wash-warm") -> look.Picture:
    """A white page with the lavender top wash (fading to white at 40% of the height), then each node's paint."""
    rgb = bytearray(b"\xff" * (width * height * 3))

    def fill(l, t, r, b, c):
        l, t, r, b = max(0, int(l)), max(0, int(t)), min(width, int(r)), min(height, int(b))
        if r <= l:
            return
        row = bytes(c) * (r - l)
        for y in range(t, b):
            rgb[(y * width + l) * 3:(y * width + r) * 3] = row

    if not flat:
        top = colour_of(wash)
        end = int(0.4 * height)
        for y in range(end):
            f = y / end
            fill(0, y, width, y + 1, tuple(round(c + (255 - c) * f) for c in top))
    for el in ET.fromstring(xml_text.strip()).iter("node"):
        spec = dict(part.split("=", 1) for part in el.get("paint", "").split())
        if not spec:
            continue
        l, t, r, b = (int(v) for v in look.BOUNDS.match(el.get("bounds")).groups())
        if "bg" in spec:
            fill(l, t, r, b, colour_of(spec["bg"]))
        if "fg" in spec:
            w, h = r - l, b - t
            fill(l + 0.1 * w, t + 0.3 * h, l + 0.8 * w, t + 0.7 * h, colour_of(spec["fg"]))
    return look.Picture(width, height, rgb)


def device(activity: str = HOME, density: int = 160, factor: float = 1.0) -> dict:
    return {"density": density, "activity": activity,
            "status_bar_px": round(24 * factor), "nav_bar_px": round(24 * factor)}


def shot_of(name: str, activity: str = HOME, flat: bool = False, wash: str = "wash-warm") -> look.Shot:
    xml_text = (FIX / name).read_text()
    return look.Shot(paint(xml_text, flat=flat, wash=wash), look.parse_dump(xml_text), 160, activity,
                     device=device(activity), defaults=DOC["defaults"])


def results_by_id(shot: look.Shot, screen: str, mode: str = "screen") -> dict:
    return {r["id"]: r for r in look.evaluate(shot, DOC, screen, mode)}


class PngTest(unittest.TestCase):
    def test_round_trip_every_filter(self):
        rnd = random.Random(7)
        w, h = 13, 7
        rgb = bytes(rnd.randrange(256) for _ in range(w * h * 3))
        for ft in range(5):
            pic = look.read_png(look.write_png(w, h, rgb, filter_type=ft))
            self.assertEqual((pic.width, pic.height), (w, h))
            self.assertEqual(bytes(pic.rgb), rgb, f"filter {ft}")

    def test_png_made_by_sips(self):
        # Made once on macOS: a 7x5 BMP of colour(x, y) converted with `sips -s format png`.
        pic = look.read_png((FIX / "sips_pattern.png").read_bytes())
        self.assertEqual((pic.width, pic.height), (7, 5))
        for y in range(5):
            for x in range(7):
                self.assertEqual(pic.px(x, y), ((x * 37) % 256, (y * 61) % 256, ((x + y) * 23) % 256))

    def _png(self, ctype: int, depth: int, raw_rows: list, w: int, h: int, extra: bytes = b"") -> bytes:
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
        raw = b"".join(b"\x00" + row for row in raw_rows)
        return (look.PNG_SIG + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, depth, ctype, 0, 0, 0)) + extra
                + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))

    def test_rgba_as_screencap_writes_it(self):
        data = self._png(6, 8, [bytes([10, 20, 30, 255, 40, 50, 60, 255])], 2, 1)
        pic = look.read_png(data)
        self.assertEqual([pic.px(0, 0), pic.px(1, 0)], [(10, 20, 30), (40, 50, 60)])

    def test_grey_palette_and_16_bit(self):
        self.assertEqual(look.read_png(self._png(0, 8, [bytes([7, 200])], 2, 1)).px(1, 0), (200, 200, 200))
        plte = struct.pack(">I", 6) + b"PLTE" + bytes([1, 2, 3, 250, 251, 252])
        plte += struct.pack(">I", zlib.crc32(b"PLTE" + bytes([1, 2, 3, 250, 251, 252])))
        pal = look.read_png(self._png(3, 1, [bytes([0b01000000])], 2, 1, extra=plte))
        self.assertEqual([pal.px(0, 0), pal.px(1, 0)], [(1, 2, 3), (250, 251, 252)])
        deep = look.read_png(self._png(2, 16, [bytes([0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC])], 1, 1))
        self.assertEqual(deep.px(0, 0), (0x12, 0x56, 0x9A))

    def test_rejects_non_png_and_interlaced(self):
        with self.assertRaises(ValueError):
            look.read_png(b"GIF89a")
        bad = bytearray(look.write_png(1, 1, b"\0\0\0"))
        bad[28] = 1  # interlace byte of IHDR (CRC not checked by the reader)
        with self.assertRaises(ValueError):
            look.read_png(bytes(bad))

    def test_pure_reader_is_used_without_pillow(self):
        data = look.write_png(2, 2, bytes(range(12)))
        self.assertEqual(bytes(look.load_picture(data, use_pil=False).rgb), bytes(range(12)))
        try:
            import PIL  # noqa: F401
        except ImportError:
            with self.assertRaises(ImportError):
                look.load_picture(data, use_pil=True)
            self.assertEqual(bytes(look.load_picture(data).rgb), bytes(range(12)))
        else:
            self.assertEqual(bytes(look.load_picture(data, use_pil=True).rgb), bytes(range(12)))


class UnitsAndParsingTest(unittest.TestCase):
    def test_dp_conversion(self):
        shot = look.Shot(look.Picture(1, 1, b"\xff\xff\xff"), [], 420)
        self.assertAlmostEqual(shot.dp(24), 63.0)        # px = dp * density / 160
        self.assertAlmostEqual(shot.to_dp(63), 24.0)
        self.assertAlmostEqual(look.Shot(look.Picture(1, 1, b"\0\0\0"), [], 160).dp(48), 48.0)
        self.assertEqual(look.guess_density(1080), 419)  # 412 dp wide

    def test_wm_output(self):
        self.assertEqual(look.parse_density("Physical density: 420\n"), 420)
        self.assertEqual(look.parse_density("Physical density: 420\nOverride density: 480\n"), 480)
        self.assertIsNone(look.parse_density("error"))
        self.assertEqual(look.parse_size("Physical size: 1080x2400\n"), (1080, 2400))
        self.assertEqual(look.parse_size("Physical size: 1080x2400\nOverride size: 720x1600"), (720, 1600))

    def test_resumed_activity(self):
        text = (FIX / "dumpsys_activities.txt").read_text()
        self.assertEqual(look.parse_resumed_activity(text), HOME)
        short = "  topResumedActivity=ActivityRecord{1 u0 com.example/.ui.Main t3}"
        self.assertEqual(look.parse_resumed_activity(short), "com.example/com.example.ui.Main")
        self.assertIsNone(look.parse_resumed_activity("nothing here"))

    def test_insets(self):
        text = (FIX / "dumpsys_window.txt").read_text()
        self.assertEqual(look.parse_insets(text, 2400), {"status_bar_px": 136, "nav_bar_px": 63})
        self.assertEqual(look.parse_insets("", 2400), {})

    def test_parse_dump_and_visibility(self):
        nodes = look.parse_dump((FIX / "home_good.xml").read_text())
        status = [n for n in nodes if n.id_name == "header_status"][0]
        self.assertEqual(status.bounds, (24, 162, 200, 182))
        self.assertIn("home_header", [a.id_name for a in status.ancestors()])
        shot = shot_of("home_good.xml")
        self.assertFalse(any(n.pkg == look.SYSTEMUI for n in shot.app_nodes))
        off = look.Node({"bounds": "[500,0][900,40]", "package": "ai.opencyclone.standard"}, None)
        self.assertFalse(shot.visible(off))

    def test_bars_sources(self):
        xml_text = (FIX / "home_good.xml").read_text()
        pic = paint(xml_text)
        nodes = look.parse_dump(xml_text)
        self.assertEqual(look.Shot(pic, nodes, 160, device={"status_bar_px": 30, "nav_bar_px": 40}).bars["source"],
                         "dumpsys window")
        from_view = look.Shot(pic, nodes, 160).bars
        self.assertEqual((from_view["status"], from_view["source"]), (24, "view listing"))
        fixed = look.Shot(pic, [n for n in nodes if n.pkg != look.SYSTEMUI], 320, defaults={"status_bar_dp": 24}).bars
        self.assertEqual((fixed["status"], fixed["source"]), (48, "fixed dp"))


class DetectTest(unittest.TestCase):
    def test_home_states(self):
        self.assertEqual(look.detect(shot_of("home_good.xml"), DOC)[0], "home_idle")
        self.assertEqual(look.detect(shot_of("home_approval.xml"), DOC)[0], "home_approval")
        self.assertEqual(look.detect(shot_of("home_running.xml"), DOC)[0], "home_running")

    def test_activity_and_marker(self):
        listening = "ai.opencyclone.standard/ai.opencyclone.ui.voice.ListeningActivity"
        self.assertEqual(look.detect(shot_of("listening.xml", activity=listening), DOC)[0], "listening")
        record = "ai.opencyclone.standard/ai.opencyclone.ui.history.TaskRecordActivity"
        self.assertEqual(look.detect(shot_of("listening.xml", activity=record), DOC)[0], "task_record")

    def test_unknown_screen(self):
        other = "com.android.settings/com.android.settings.Settings"
        self.assertIsNone(look.detect(shot_of("home_good.xml", activity=other), DOC)[0])


class GoodScreensTest(unittest.TestCase):
    def test_home_idle_passes_every_rule(self):
        results = results_by_id(shot_of("home_good.xml"), "home_idle")
        failed = {k: v for k, v in results.items() if v["status"] != "PASS"}
        self.assertEqual(failed, {})
        self.assertEqual(set(look.KINDS) - {"not_flat", "colour_at"} - {r["kind"] for r in results.values()}, set())

    def test_home_approval_and_running_pass(self):
        for name, screen, wash in (("home_approval.xml", "home_approval", "wash-cool"),
                                   ("home_running.xml", "home_running", "wash-cool")):
            results = results_by_id(shot_of(name, wash=wash), screen)
            failed = {k: (v["measured"], v["reason"]) for k, v in results.items() if v["status"] == "FAIL"}
            self.assertEqual(failed, {}, name)
        approval = results_by_id(shot_of("home_approval.xml"), "home_approval")
        self.assertEqual(approval["appr_one_primary"]["measured"]["count"], 1)
        self.assertEqual(approval["appr_no_primary_unreadable"]["status"], "SKIP")

    def test_same_page_at_density_320(self):
        xml_text = scale_xml((FIX / "home_good.xml").read_text(), 2)
        shot = look.Shot(paint(xml_text, W * 2, H * 2), look.parse_dump(xml_text), 320, HOME,
                         device=device(density=320, factor=2), defaults=DOC["defaults"])
        failed = [r["id"] for r in look.evaluate(shot, DOC, "home_idle") if r["status"] != "PASS"]
        self.assertEqual(failed, [])


# (fixture, screen, rule id that must fail, kind of that rule, keyword arguments for shot_of)
FAILING = [
    ("home_good.xml", "home_idle", "home_activity", "activity",
     {"activity": "ai.opencyclone.standard/ai.opencyclone.ui.voice.ListeningActivity"}),
    ("bad_text_present.xml", "home_idle", "home_greeting", "text_present", {}),
    ("bad_text_absent.xml", "home_idle", "idle_no_status_words", "text_absent", {}),
    ("bad_no_class.xml", "home_idle", "home_no_text_box", "no_class", {}),
    ("bad_count_primary.xml", "home_idle", "idle_no_primary", "count_primary", {}),
    ("bad_left_edge.xml", "home_idle", "home_left_edge", "left_edge", {}),
    ("bad_colour_at.xml", "home_approval", "appr_pale_colour", "colour_at", {}),
    ("bad_text_colours.xml", "home_idle", "g_text_colours", "text_colours", {}),
    ("home_good.xml", "home_idle", "home_wash", "wash_top", {"flat": True}),
    ("bad_emoji_or_bang.xml", "home_idle", "g_no_emoji_bang", "no_emoji_or_bang", {}),
    ("bad_sentence_rule.xml", "home_idle", "home_status_sentence", "sentence_rule", {}),
    ("bad_touch_target.xml", "home_idle", "g_touch_target", "touch_target", {}),
    ("bad_no_overlap_systembars.xml", "home_idle", "g_no_overlap_bars", "no_overlap_systembars", {}),
    ("bad_order.xml", "home_idle", "home_header_order", "order", {}),
    ("bad_footer.xml", "home_idle", "home_footer", "footer", {}),
    ("bad_no_lines.xml", "home_idle", "g_no_lines", "no_lines", {}),
]


class FailingCasesTest(unittest.TestCase):
    def test_every_kind_has_a_failing_case(self):
        self.assertEqual(set(look.KINDS) - {"not_flat"}, {case[3] for case in FAILING})

    def test_each_failing_case_fails_its_rule(self):
        for fixture, screen, rule_id, kind, kwargs in FAILING:
            with self.subTest(rule=rule_id):
                result = results_by_id(shot_of(fixture, **kwargs), screen)[rule_id]
                self.assertEqual(result["kind"], kind)
                self.assertEqual(result["status"], "FAIL", result)

    def test_failing_details(self):
        res = results_by_id(shot_of("bad_count_primary.xml"), "home_idle")["idle_no_primary"]
        self.assertEqual(res["measured"], {"count": 1, "nodes": ["Send"]})
        res = results_by_id(shot_of("bad_left_edge.xml"), "home_idle")["home_left_edge"]
        self.assertEqual(res["measured"], ["'All caught up.'@40.0dp"])
        res = results_by_id(shot_of("bad_emoji_or_bang.xml"), "home_idle")["g_no_emoji_bang"]
        self.assertEqual(len(res["measured"]), 2)
        res = results_by_id(shot_of("bad_text_colours.xml"), "home_idle")["g_text_colours"]
        self.assertIn("#D3306B", res["measured"][0])
        res = results_by_id(shot_of("bad_sentence_rule.xml"), "home_idle")["home_status_sentence"]
        self.assertTrue(res["measured"][0].startswith("66 chars"))


class RuleKindsTest(unittest.TestCase):
    """Edges of single rule kinds that the screen fixtures do not reach."""

    def setUp(self):
        self.shot = shot_of("home_good.xml")
        self.ctx = look.Ctx(TOKENS, DOC["defaults"])

    def test_home_alias_is_accepted_for_home_and_history(self):
        self.shot.activity = "ai.opencyclone.standard/ai.opencyclone.ui.HomeAlias"
        self.assertEqual(results_by_id(self.shot, "home_idle")["home_activity"]["status"], "PASS")
        self.assertEqual(results_by_id(self.shot, "history")["hist_activity"]["status"], "PASS")
        self.shot.activity = "ai.opencyclone.ui.OtherActivity"
        self.assertEqual(results_by_id(self.shot, "home_idle")["home_activity"]["status"], "FAIL")

    def test_app_tile_in_primary_colour_is_not_a_page_button(self):
        shot = shot_of("bad_count_primary.xml")
        button = next(n for n in shot.app_nodes if n.text == "Send")
        button.desc = "Open Lingo"
        results = results_by_id(shot, "home_idle")
        self.assertEqual(results["idle_no_primary"]["measured"]["count"], 0)
        self.assertEqual(results["g_one_primary"]["measured"]["count"], 0)
        button.desc = "Send"
        self.assertEqual(results_by_id(shot, "home_idle")["idle_no_primary"]["status"], "FAIL")

    def test_full_width_history_heading_does_not_imply_zero_text_padding(self):
        heading = look.Node({"text": "Today", "bounds": "[0,200][412,240]"}, None)
        self.shot.app_nodes.append(heading)
        rule = {"only_ids": ["header_greeting", ""]}
        self.assertTrue(look.k_left_edge(self.shot, rule, self.ctx)[0])
        heading.bounds = (0, 200, 150, 240)
        self.assertFalse(look.k_left_edge(self.shot, rule, self.ctx)[0])

    def test_clipped_last_list_row_is_not_a_short_target(self):
        parent = look.Node({"class": "androidx.recyclerview.widget.RecyclerView",
                            "bounds": "[0,200][412,800]"}, None)
        row = look.Node({"clickable": "true", "bounds": "[0,774][412,800]"}, parent)
        self.shot.app_nodes = [parent, row]
        self.assertTrue(look.k_touch_target(self.shot, {}, self.ctx)[0])
        row.bounds = (0, 700, 412, 726)
        self.assertFalse(look.k_touch_target(self.shot, {}, self.ctx)[0])

    def test_horizontal_app_strip_does_not_hide_short_touch_targets(self):
        parent = look.Node({"class": "android.widget.HorizontalScrollView", "scrollable": "true",
                            "bounds": "[0,200][412,244]"}, None)
        tile = look.Node({"clickable": "true", "content-desc": "Open Lingo",
                          "bounds": "[24,200][68,244]"}, parent)
        self.shot.app_nodes = [parent, tile]
        self.assertFalse(look.k_touch_target(self.shot, {}, self.ctx)[0])

    def test_right_clipped_horizontal_tile_is_not_a_short_target(self):
        parent = look.Node({"class": "android.widget.HorizontalScrollView", "scrollable": "true",
                            "bounds": "[24,200][412,269]"}, None)
        tile = look.Node({"clickable": "true", "content-desc": "Open Lingo",
                          "bounds": "[409,200][412,269]"}, parent)
        self.shot.app_nodes = [parent, tile]
        self.assertTrue(look.k_touch_target(self.shot, {}, self.ctx)[0])
        tile.bounds = (405, 200, 408, 269)
        self.assertFalse(look.k_touch_target(self.shot, {}, self.ctx)[0])
        parent.bounds = (24, 200, 388, 269)
        tile.bounds = (385, 200, 388, 269)
        self.assertTrue(look.k_touch_target(self.shot, {}, self.ctx)[0])

    def test_text_rules_in_region_and_by_id(self):
        ok, _, _ = look.k_text_present(self.shot, {"rid": "header_status", "text": "All caught up."}, self.ctx)
        self.assertTrue(ok)
        ok, _, _ = look.k_text_present(self.shot, {"text": "All caught up.", "region": "lower_half"}, self.ctx)
        self.assertFalse(ok)
        ok, hits, _ = look.k_text_absent(self.shot, {"pattern": "^type$", "region": "bottom_band"}, self.ctx)
        self.assertEqual((ok, hits), (False, ["type"]))

    def test_no_class_matches_short_name(self):
        ok, _, _ = look.k_no_class(self.shot, {"class": "TextView"}, self.ctx)
        self.assertFalse(ok)

    def test_colour_at_list_of_tokens(self):
        ok, measured, _ = look.k_colour_at(self.shot, {"region": {"id": "owner_text"}, "tokens": ["ink", "primary"]},
                                           self.ctx)
        self.assertEqual((ok, measured), (True, TOKENS["primary"]))
        ok, _, reason = look.k_colour_at(self.shot, {"region": {"id": "missing"}, "token": "page"}, self.ctx)
        self.assertFalse(ok)
        self.assertIn("not found", reason)

    def test_text_colour_estimate_on_primary(self):
        owner = [n for n in self.shot.app_nodes if n.id_name == "owner_text"][0]
        colour, background, light = look.estimate_text_colour(self.shot.picture, owner.bounds)
        self.assertEqual((colour, background, light), ((255, 255, 255), colour_of("primary"), True))

    def test_order_horizontal(self):
        shot = shot_of("home_approval.xml")
        ok, _, _ = look.k_order(shot, {"axis": "x", "items": [{"id": "approval_not_now"}, {"id": "approval_primary"}]},
                                self.ctx)
        self.assertFalse(ok)

    def test_emoji_set_spares_typographic_marks(self):
        for text in ("1 running · Clock ›", "Live 0:34", "“Open it”", "Done ✓"):
            self.assertIsNone(look.EMOJI.search(text), text)
        for text in ("Nice 🎉", "☀ sunny", "✅ done"):
            self.assertIsNotNone(look.EMOJI.search(text), text)


class NeedsStateTest(unittest.TestCase):
    def doc(self, rules):
        return {"tokens": TOKENS, "defaults": DOC["defaults"], "screens": {"s": {"rules": rules, "global": False}}}

    def test_when_and_mode(self):
        rules = [
            {"id": "a", "kind": "text_present", "text": "Good afternoon."},
            {"id": "b", "kind": "text_present", "text": "Send", "needs_state": "approval", "when": {"id": "approval_primary"}},
            {"id": "c", "kind": "text_present", "text": "Good afternoon.", "needs_state": "manual"},
            {"id": "d", "kind": "text_present", "text": "Good afternoon.", "needs_state": "no approval",
             "when_absent": {"id": "approval_primary"}},
        ]
        shot = shot_of("home_good.xml")
        named = {r["id"]: r["status"] for r in look.evaluate(shot, self.doc(rules), "s")}
        self.assertEqual(named, {"a": "PASS", "b": "SKIP", "c": "PASS", "d": "PASS"})
        auto = {r["id"]: r["status"] for r in look.evaluate(shot, self.doc(rules), "s", mode="all")}
        self.assertEqual(auto, {"a": "PASS", "b": "SKIP", "c": "SKIP", "d": "PASS"})

    def test_bad_rule_is_a_failure_not_a_crash(self):
        rules = [{"id": "x", "kind": "colour_at", "region": "nowhere", "token": "page"}]
        result = look.evaluate(shot_of("home_good.xml"), self.doc(rules), "s")[0]
        self.assertEqual(result["status"], "FAIL")
        self.assertIn("rule error", result["reason"])


class RulesFileTest(unittest.TestCase):
    REQUIRED = {"home_idle", "home_approval", "home_running", "home_panel", "listening", "speaking", "history",
                "task_record", "widgets_empty", "start_welcome", "start_connect", "start_model",
                "start_permissions", "start_voice", "start_try", "setup", "privacy", "rules", "integrations",
                "settings", "model", "voice", "menu", "received", "built_app"}

    def test_screens_and_rules_are_well_formed(self):
        self.assertEqual(self.REQUIRED - set(DOC["screens"]), set())
        for name, screen in DOC["screens"].items():
            self.assertIn("detect", screen, name)
            rules = look.screen_rules(DOC, name)
            ids = [r["id"] for r in rules]
            self.assertEqual(len(ids), len(set(ids)), f"{name}: duplicate rule ids")
            for rule in rules:
                self.assertIn(rule["kind"], look.KINDS, rule["id"])
                self.assertTrue(rule.get("why"), f"{name}.{rule['id']} has no why")
                for key in ("token",) + (("tokens",) if rule["kind"] != "text_colours" else ()):
                    for t in ([rule[key]] if isinstance(rule.get(key), str) else rule.get(key, [])):
                        look.token_colour(TOKENS, t)

    def test_section_11_palette(self):
        self.assertEqual({k: TOKENS[k] for k in ("page", "ink", "ink-2", "ink-3", "primary", "btn-pale", "surface-2")},
                         {"page": "#FFFFFF", "ink": "#15173A", "ink-2": "#5B5E7A", "ink-3": "#8A8DA8",
                          "primary": "#4733D6", "btn-pale": "#ECEBFB", "surface-2": "#F4F4FB"})


class CommandLineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="look-test-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def evidence(self, fixture: str, activity: str = HOME, with_device: bool = True, wash: str = "wash-warm") -> Path:
        folder = self.tmp / fixture.replace(".xml", "")
        folder.mkdir()
        xml_text = (FIX / fixture).read_text()
        pic = paint(xml_text, wash=wash)
        (folder / "screen.png").write_bytes(look.write_png(pic.width, pic.height, pic.rgb))
        (folder / "window.xml").write_text(xml_text)
        if with_device:
            (folder / "device.json").write_text(json.dumps(device(activity)))
        return folder

    def run_main(self, *argv) -> tuple:
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = look.main(list(argv))
        return code, out.getvalue()

    def test_from_good_evidence(self):
        folder = self.evidence("home_good.xml")
        code, text = self.run_main("--from", str(folder), "home_idle")
        self.assertEqual(code, 0, text)
        self.assertIn("PASS home_wash:", text)
        result = json.loads((folder / "look.json").read_text())
        self.assertTrue(result["passed"])
        self.assertEqual(result["counts"]["FAIL"], 0)

    def test_from_failing_evidence_exits_1(self):
        folder = self.evidence("bad_order.xml")
        code, text = self.run_main("--from", str(folder), "home_idle", "--out", str(self.tmp / "out"))
        self.assertEqual(code, 1)
        self.assertIn("FAIL home_header_order:", text)
        self.assertTrue((self.tmp / "out" / "look.json").exists())

    def test_all_detects_the_screen(self):
        folder = self.evidence("home_approval.xml", wash="wash-cool")
        code, text = self.run_main("--from", str(folder), "all")
        self.assertEqual(code, 0, text)
        self.assertIn("detected screen: home_approval", text)

    def test_all_without_a_known_screen(self):
        folder = self.evidence("home_good.xml", activity="com.android.settings/com.android.settings.Settings")
        code, text = self.run_main("--from", str(folder), "all")
        self.assertEqual(code, 2)
        self.assertIn("cannot tell which screen", text)

    def test_density_guess_without_device_json(self):
        folder = self.evidence("home_good.xml", with_device=False)
        code, text = self.run_main("--from", str(folder), "home_idle", "--density", "160")
        self.assertIn("density 160", text)
        self.assertIn("activity unknown", text)
        self.assertEqual(code, 1)  # the activity rule cannot pass without the resumed activity

    def test_list_and_unknown(self):
        code, text = self.run_main("--list")
        self.assertEqual(code, 0)
        self.assertIn("home_idle", text)
        self.assertEqual(self.run_main("nonsense")[0], 2)


if __name__ == "__main__":
    unittest.main()
