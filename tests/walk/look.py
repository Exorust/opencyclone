#!/usr/bin/env python3
"""Look check: objective checks of one screen of the app against UI.md, from a screenshot and a view listing.

Called by walk.sh; standard library only (Python 3.9+). Pillow is used for decoding when it is importable,
otherwise a small pure-Python PNG reader (zlib + struct) is used.

    python3 tests/walk/look.py <screen> [--out DIR]       capture from the phone over adb, then check
    python3 tests/walk/look.py all [--out DIR]            detect the screen in front, run its no-navigation rules
    python3 tests/walk/look.py --list                     list the screens and their rule counts
    python3 tests/walk/look.py --from DIR <screen|all>    check saved evidence (screen.png + window.xml [+ device.json])

Options: --density N (override; else device.json, else guessed from the width as 412 dp), --rules PATH.
Exit code: 0 every rule passed (skipped rules do not fail), 1 a rule failed, 2 usage or capture error.

Units: rules are written in dp; px = dp * density / 160. Colours are compared by the largest channel
difference (0 to 255). The package comes from walk.PACKAGE (environment WALK_PACKAGE).
"""

from __future__ import annotations

import argparse
import json
import math
import re
import struct
import sys
import time
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path
from typing import Optional

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import walk  # noqa: E402  (shares PACKAGE and the adb helpers)

RULES = HERE / "look_rules.json"
PACKAGE = walk.PACKAGE
PNG_SIG = b"\x89PNG\r\n\x1a\n"
SYSTEMUI = "com.android.systemui"


# ── PNG ──────────────────────────────────────────────────────────────────────

class Picture:
    """An RGB picture: width, height, and 3 bytes for each pixel, row by row."""

    def __init__(self, width: int, height: int, rgb):
        if len(rgb) != width * height * 3:
            raise ValueError(f"picture data is {len(rgb)} bytes, expected {width * height * 3}")
        self.width, self.height, self.rgb = width, height, rgb

    def px(self, x: float, y: float) -> tuple:
        x = min(max(int(x), 0), self.width - 1)
        y = min(max(int(y), 0), self.height - 1)
        i = (y * self.width + x) * 3
        return self.rgb[i], self.rgb[i + 1], self.rgb[i + 2]


def write_png(width: int, height: int, rgb, filter_type: int = 0) -> bytes:
    """An 8-bit RGB PNG of the given pixels. filter_type 0 to 4 applies that filter to every row (for tests)."""
    stride = width * 3
    raw = bytearray()
    prev = bytes(stride)
    for y in range(height):
        line = bytes(rgb[y * stride:(y + 1) * stride])
        raw.append(filter_type)
        if filter_type == 0:
            raw += line
        else:
            out = bytearray(stride)
            for i in range(stride):
                a = line[i - 3] if i >= 3 else 0
                b = prev[i]
                c = prev[i - 3] if i >= 3 else 0
                if filter_type == 1:
                    p = a
                elif filter_type == 2:
                    p = b
                elif filter_type == 3:
                    p = (a + b) >> 1
                else:
                    p = _paeth(a, b, c)
                out[i] = (line[i] - p) & 255
            raw += out
        prev = line

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (PNG_SIG + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def read_png(data: bytes) -> Picture:
    """Pure-Python PNG decoder: grey, RGB, palette, grey+alpha, RGBA; 1 to 16 bits; not interlaced.
    Alpha is dropped (screencap is opaque)."""
    if not data.startswith(PNG_SIG):
        raise ValueError("not a PNG file")
    pos, idat, palette, header = 8, bytearray(), None, None
    while pos + 8 <= len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"PLTE":
            palette = body
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
    if header is None:
        raise ValueError("PNG without IHDR")
    width, height, depth, ctype, _comp, _filt, interlace = header
    if interlace:
        raise ValueError("interlaced PNG is not supported")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(ctype)
    if channels is None:
        raise ValueError(f"PNG colour type {ctype} is not supported")
    bits = channels * depth
    bpp = max(1, bits // 8)
    stride = (width * bits + 7) // 8
    flat = _unfilter(zlib.decompress(bytes(idat)), height, stride, bpp)

    if depth == 16:
        flat = flat[0::2]  # the high byte of each sample
        stride //= 2
    elif depth < 8:
        flat = _unpack_bits(flat, height, stride, width, depth, ctype == 3)
        stride = width
    n = width * height
    rgb = bytearray(n * 3)
    if ctype == 2:
        rgb[:] = flat
    elif ctype == 6:
        rgb[0::3], rgb[1::3], rgb[2::3] = flat[0::4], flat[1::4], flat[2::4]
    elif ctype == 0:
        rgb[0::3] = rgb[1::3] = rgb[2::3] = flat
    elif ctype == 4:
        grey = flat[0::2]
        rgb[0::3] = rgb[1::3] = rgb[2::3] = grey
    else:  # palette
        if palette is None:
            raise ValueError("palette PNG without PLTE")
        table = [palette[i * 3:i * 3 + 3] for i in range(len(palette) // 3)]
        for i, index in enumerate(flat[:n]):
            rgb[i * 3:i * 3 + 3] = table[index]
    return Picture(width, height, rgb)


def _unfilter(raw: bytes, height: int, stride: int, bpp: int) -> bytearray:
    out = bytearray(height * stride)
    prev = bytearray(stride)
    pos = 0
    for y in range(height):
        ft = raw[pos]
        line = bytearray(raw[pos + 1:pos + 1 + stride])
        pos += 1 + stride
        if ft == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 255
        elif ft == 2:
            line = bytearray((a + b) & 255 for a, b in zip(line, prev))
        elif ft == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 255
        elif ft == 4:
            for i in range(stride):
                if i >= bpp:
                    a, c = line[i - bpp], prev[i - bpp]
                else:
                    a = c = 0
                b = prev[i]
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                if pa <= pb and pa <= pc:
                    pr = a
                elif pb <= pc:
                    pr = b
                else:
                    pr = c
                line[i] = (line[i] + pr) & 255
        elif ft != 0:
            raise ValueError(f"bad PNG filter type {ft} in row {y}")
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return out


def _unpack_bits(flat: bytes, height: int, stride: int, width: int, depth: int, indexed: bool) -> bytearray:
    out = bytearray(width * height)
    scale = 1 if indexed else 255 // ((1 << depth) - 1)
    mask = (1 << depth) - 1
    for y in range(height):
        row = flat[y * stride:(y + 1) * stride]
        for x in range(width):
            bit = x * depth
            value = (row[bit // 8] >> (8 - depth - bit % 8)) & mask
            out[y * width + x] = value * scale
    return out


def load_picture(data: bytes, use_pil: Optional[bool] = None) -> Picture:
    """Decodes PNG bytes with Pillow when importable (unless use_pil is False), else with read_png."""
    if use_pil is not False:
        try:
            from PIL import Image  # type: ignore
            import io
            image = Image.open(io.BytesIO(data)).convert("RGB")
            return Picture(image.width, image.height, bytearray(image.tobytes()))
        except ImportError:
            if use_pil:
                raise
    return read_png(data)


# ── view listing ─────────────────────────────────────────────────────────────

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


class Node:
    def __init__(self, attrs: dict, parent: Optional["Node"]):
        self.scrollable = attrs.get("scrollable") == "true"
        self.text = attrs.get("text", "") or ""
        self.rid = attrs.get("resource-id", "") or ""
        self.cls = attrs.get("class", "") or ""
        self.pkg = attrs.get("package", "") or ""
        self.desc = attrs.get("content-desc", "") or ""
        self.clickable = attrs.get("clickable") == "true" or attrs.get("long-clickable") == "true"
        m = BOUNDS.match(attrs.get("bounds", "") or "")
        self.bounds = tuple(int(v) for v in m.groups()) if m else (0, 0, 0, 0)
        self.parent = parent

    @property
    def id_name(self) -> str:
        return self.rid.split(":id/", 1)[-1] if self.rid else ""

    def ancestors(self):
        node = self.parent
        while node is not None:
            yield node
            node = node.parent

    def label(self) -> str:
        return self.text or self.desc or self.id_name or self.cls.rsplit(".", 1)[-1]

    def __repr__(self):
        return f"Node({self.label()!r} {self.bounds})"


def parse_dump(xml_text: str) -> list:
    """Every node of a uiautomator dump, parents first."""
    nodes = []

    def walk_tree(element, parent):
        for child in element:
            if child.tag != "node":
                continue
            node = Node(child.attrib, parent)
            nodes.append(node)
            walk_tree(child, node)

    walk_tree(ET.fromstring(xml_text.strip()), None)
    return nodes


# ── device facts from adb output ─────────────────────────────────────────────

def parse_density(text: str) -> Optional[int]:
    """`wm density`: the override density when set, else the physical one."""
    override = re.search(r"Override density:\s*(\d+)", text)
    physical = re.search(r"Physical density:\s*(\d+)", text)
    m = override or physical
    return int(m.group(1)) if m else None


def parse_size(text: str) -> Optional[tuple]:
    override = re.search(r"Override size:\s*(\d+)x(\d+)", text)
    physical = re.search(r"Physical size:\s*(\d+)x(\d+)", text)
    m = override or physical
    return (int(m.group(1)), int(m.group(2))) if m else None


RESUMED = re.compile(r"(?:mResumedActivity|topResumedActivity|ResumedActivity)\s*[:=]\s*"
                     r"ActivityRecord\{\S+\s+u\d+\s+([\w.]+)/([\w.$]+)")


def parse_resumed_activity(text: str) -> Optional[str]:
    """`dumpsys activity activities`: "package/full.class.Name" of the resumed activity."""
    m = RESUMED.search(text)
    if not m:
        return None
    pkg, cls = m.groups()
    return f"{pkg}/{pkg + cls if cls.startswith('.') else cls}"


def parse_insets(text: str, height: int) -> dict:
    """Status and navigation bar heights in px from `dumpsys window` InsetsSource lines; missing keys when absent."""
    found = {}
    for key, names in (("status_bar_px", "statusBars|ITYPE_STATUS_BAR"),
                       ("nav_bar_px", "navigationBars|ITYPE_NAVIGATION_BAR")):
        for m in re.finditer(r"type=(?:%s)\s+frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]" % names, text):
            left, top, right, bottom = (int(v) for v in m.groups())
            if bottom <= top or right <= left:
                continue
            if key == "status_bar_px" and top == 0:
                found[key] = bottom
            elif key == "nav_bar_px" and bottom >= height - 1 and top > height // 2:
                found[key] = height - top
            if key in found:
                break
    return found


# ── the shot under test ──────────────────────────────────────────────────────

class Shot:
    """One screen: picture, nodes, density, resumed activity, and the system bar heights."""

    def __init__(self, picture: Picture, nodes: list, density: float, activity: Optional[str] = None,
                 device: Optional[dict] = None, package: str = PACKAGE, defaults: Optional[dict] = None):
        self.picture, self.nodes, self.density, self.activity = picture, nodes, float(density), activity
        self.width, self.height = picture.width, picture.height
        self.device = device or {}
        self.defaults = defaults or {}
        own = [n for n in nodes if n.pkg == package]
        pool = own if own else [n for n in nodes if n.pkg != SYSTEMUI]
        self.app_nodes = [n for n in pool if self.visible(n)]
        self.bars = self._bars()

    def dp(self, value: float) -> float:
        """dp to px."""
        return value * self.density / 160.0

    def to_dp(self, px: float) -> float:
        return px * 160.0 / self.density

    def visible(self, node: Node) -> bool:
        l, t, r, b = node.bounds
        return r > l and b > t and r > 0 and b > 0 and l < self.width and t < self.height

    def _bars(self) -> dict:
        if "status_bar_px" in self.device and "nav_bar_px" in self.device:
            return {"status": self.device["status_bar_px"], "nav": self.device["nav_bar_px"], "source": "dumpsys window"}
        status = nav = None
        for n in self.nodes:
            if n.pkg == SYSTEMUI and "status_bar" in n.rid and n.bounds[1] <= 0:
                status = max(status or 0, n.bounds[3])
            if n.pkg == SYSTEMUI and "navigation_bar" in n.rid and n.bounds[3] >= self.height - 1:
                nav = max(nav or 0, self.height - n.bounds[1])
        if status is not None or nav is not None:
            return {"status": status or round(self.dp(self.defaults.get("status_bar_dp", 24))),
                    "nav": nav or round(self.dp(self.defaults.get("nav_bar_dp", 24))), "source": "view listing"}
        return {"status": round(self.dp(self.defaults.get("status_bar_dp", 24))),
                "nav": round(self.dp(self.defaults.get("nav_bar_dp", 24))), "source": "fixed dp"}


def guess_density(width_px: int) -> int:
    """Density that makes the screen 412 dp wide (UI.md section 1), for evidence without device.json."""
    return round(width_px * 160 / 412)


# ── selectors, regions, colours ──────────────────────────────────────────────

def matches(node: Node, sel: dict) -> bool:
    if "id" in sel and node.id_name != sel["id"] and node.rid != sel["id"]:
        return False
    if "text" in sel and node.text != sel["text"]:
        return False
    if "pattern" in sel and not re.search(sel["pattern"], node.text):
        return False
    if "desc" in sel and node.desc != sel["desc"]:
        return False
    if "class" in sel and not class_is(node, sel["class"]):
        return False
    if "clickable" in sel and node.clickable != sel["clickable"]:
        return False
    return True


def class_is(node: Node, name: str) -> bool:
    return node.cls == name or node.cls.endswith("." + name)


def find(shot: Shot, sel: dict, nodes=None) -> list:
    return [n for n in (shot.app_nodes if nodes is None else nodes) if matches(n, sel)]


def region_box(shot: Shot, region) -> Optional[tuple]:
    """A region in px: a name, {"frac": [l, t, r, b]}, {"dp": [l, t, r, b]}, or a node selector."""
    w, h = shot.width, shot.height
    margin = shot.dp(shot.defaults.get("margin_dp", 24))
    named = {
        None: (0, 0, w, h), "full": (0, 0, w, h),
        "top_band": (0, 0, w, 0.3 * h), "lower_half": (0, 0.5 * h, w, h),
        "page_middle": (margin, 0.4 * h, w - margin, 0.6 * h),
        "content": (0, shot.bars["status"], w, h - shot.bars["nav"]),
        "bottom_band": (0, 0.75 * h, w, h),
        "left_margin": (0, 0.25 * h, shot.dp(12), h - shot.bars["nav"]),
    }
    if region is None or isinstance(region, str):
        if region not in named:
            raise ValueError(f"unknown region {region!r}; one of {', '.join(k for k in named if k)}")
        return named[region]
    if "frac" in region:
        l, t, r, b = region["frac"]
        return (l * w, t * h, r * w, b * h)
    if "dp" in region:
        return tuple(shot.dp(v) for v in region["dp"])
    found = find(shot, region)
    return found[0].bounds if found else None


def inside(node: Node, box: tuple) -> bool:
    l, t, r, b = node.bounds
    cx, cy = (l + r) / 2, (t + b) / 2
    return box[0] <= cx <= box[2] and box[1] <= cy <= box[3]


def hex_rgb(value: str) -> tuple:
    v = value.lstrip("#")
    return int(v[0:2], 16), int(v[2:4], 16), int(v[4:6], 16)


def rgb_hex(c) -> str:
    return "#%02X%02X%02X" % tuple(int(round(v)) for v in c)


def dist(a, b) -> int:
    return max(abs(int(a[i]) - int(b[i])) for i in range(3))


def lum(c) -> float:
    return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]


def median_colour(colours: list) -> tuple:
    if not colours:
        return (0, 0, 0)
    out = []
    for i in range(3):
        values = sorted(c[i] for c in colours)
        out.append(values[len(values) // 2])
    return tuple(out)


def sample_grid(pic: Picture, box: tuple, n: int = 9) -> list:
    l, t, r, b = box
    return [pic.px(l + (r - l) * (i + 0.5) / n, t + (b - t) * (j + 0.5) / n) for j in range(n) for i in range(n)]


def sample_node5(pic: Picture, box: tuple) -> list:
    """Five points in the inner area of a node, away from a centred label: top, bottom, left, right, centre."""
    l, t, r, b = box
    w, h = r - l, b - t
    points = [(0.5, 0.15), (0.5, 0.85), (0.08, 0.5), (0.92, 0.5), (0.5, 0.5)]
    return [pic.px(l + w * fx, t + h * fy) for fx, fy in points]


def token_colour(doc_tokens: dict, name: str) -> tuple:
    if name.startswith("#"):
        return hex_rgb(name)
    if name not in doc_tokens:
        raise ValueError(f"unknown colour token {name!r}")
    return hex_rgb(doc_tokens[name])


# ── rule kinds ───────────────────────────────────────────────────────────────
# Each returns (ok, measured, reason). ok is True or False; a rule kind never skips by itself.

class Ctx:
    def __init__(self, tokens: dict, defaults: dict):
        self.tokens, self.defaults = tokens, defaults

    def tol(self, rule: dict, key: str = "colour_tol") -> float:
        return rule.get("tol", self.defaults.get(key, 12))


def scoped(shot: Shot, rule: dict, text_only: bool = True) -> list:
    """App nodes inside the rule's region (by centre), with the rule's resource id ("rid"), without its exceptions.
    (A rule's own name is "id"; the resource id it is limited to is "rid".)"""
    box = region_box(shot, rule.get("region"))
    if box is None:  # a node region that is not on screen
        return []
    nodes = [n for n in shot.app_nodes if inside(n, box)]
    if text_only:
        nodes = [n for n in nodes if n.text.strip()]
    if "rid" in rule:
        nodes = [n for n in nodes if n.id_name == rule["rid"]]
    if "only_ids" in rule:
        nodes = [n for n in nodes if n.id_name in rule["only_ids"]]
    return [n for n in nodes if not excepted(n, rule)]


def excepted(node: Node, rule: dict) -> bool:
    if node.text in rule.get("except_text", []) or node.desc in rule.get("except_text", []):
        return True
    if node.id_name in rule.get("except_ids", []):
        return True
    if any(re.search(p, node.text) for p in rule.get("except_patterns", [])):
        return True
    containers = rule.get("except_inside_ids", [])
    return bool(containers) and any(a.id_name in containers for a in node.ancestors())


def text_hit(node: Node, rule: dict) -> bool:
    if "text" in rule:
        return node.text == rule["text"] or (rule.get("desc") and node.desc == rule["text"])
    if "pattern" in rule:
        return bool(re.search(rule["pattern"], node.text) or (rule.get("desc") and re.search(rule["pattern"], node.desc)))
    return True


def k_activity(shot, rule, ctx):
    act = shot.activity or ""
    name = rule["name"]
    names = name if isinstance(name, list) else [name]
    ok = bool(act) and any(act.endswith("." + n) or act.endswith("/" + n) or act == n for n in names)
    return ok, act or "unknown", f"resumed activity ends with {name}"


def k_text_present(shot, rule, ctx):
    hits = [n.text for n in scoped(shot, rule, text_only=False) if text_hit(n, rule)]
    want = rule.get("text", rule.get("pattern", "any text"))
    where = f" {rule['rid']}" if "rid" in rule else ""
    return bool(hits), hits[:5], f"a node{where} matches {want!r}"


def k_text_absent(shot, rule, ctx):
    hits = [n.text for n in scoped(shot, rule, text_only=False) if (n.text or rule.get("desc")) and text_hit(n, rule)]
    want = rule.get("text", rule.get("pattern"))
    return not hits, hits[:5], f"no node matches {want!r}"


def k_no_class(shot, rule, ctx):
    names = rule.get("classes") or [rule["class"]]
    hits = [f"{n.cls}@{n.label()}" for n in scoped(shot, rule, text_only=False)
            if any(class_is(n, c) for c in names)]
    return not hits, hits[:5] or 0, f"no node of class {', '.join(names)}"


def k_count_primary(shot, rule, ctx):
    primary = token_colour(ctx.tokens, rule.get("token", "primary"))
    tol = ctx.tol(rule, "primary_tol")
    seen, found = set(), []
    for n in scoped(shot, rule, text_only=False):
        if not n.clickable or n.bounds in seen or n.desc.startswith("Open "):
            continue
        seen.add(n.bounds)
        colour = median_colour(sample_node5(shot.picture, n.bounds))
        if dist(colour, primary) <= tol:
            found.append(n.label())
    lo, hi = rule.get("min", 0), rule.get("max", 1)
    return lo <= len(found) <= hi, {"count": len(found), "nodes": found}, \
        f"{lo} to {hi} clickable nodes with a {rule.get('token', 'primary')} background"


def k_left_edge(shot, rule, ctx):
    margin = rule.get("margin_dp", ctx.defaults.get("margin_dp", 24))
    tol = rule.get("tol_dp", 3)
    right_margin = shot.width - shot.dp(margin)
    nodes = []
    for n in scoped(shot, rule):
        # Bounds of a full-width label include unknown internal padding.
        if n.bounds[0] <= 0 and n.bounds[2] >= shot.width:
            continue
        if rule.get("skip_right_aligned", True) and n.bounds[0] > shot.width / 2 \
                and abs(n.bounds[2] - right_margin) <= shot.dp(tol):
            continue
        nodes.append(n)
    if not nodes:
        return False, [], "no text nodes to check in the region"
    off = [f"{n.label()[:30]!r}@{shot.to_dp(n.bounds[0]):.1f}dp" for n in nodes
           if abs(shot.to_dp(n.bounds[0]) - margin) > tol]
    return not off, off[:6] or f"{len(nodes)} nodes at {margin}dp", f"text starts at {margin} dp ±{tol}"


def k_colour_at(shot, rule, ctx):
    region = rule.get("region")
    box = region_box(shot, region)
    if box is None:
        return False, None, f"region {region} not found"
    node_region = isinstance(region, dict) and "frac" not in region and "dp" not in region
    samples = sample_node5(shot.picture, box) if rule.get("sample", "node" if node_region else "grid") == "node" \
        else sample_grid(shot.picture, box, rule.get("grid", 9))
    colour = median_colour(samples)
    names = rule.get("tokens") or [rule["token"]]
    best = min(names, key=lambda t: dist(colour, token_colour(ctx.tokens, t)))
    d = dist(colour, token_colour(ctx.tokens, best))
    tol = ctx.tol(rule)
    return d <= tol, rgb_hex(colour), f"median colour within {tol} of {' or '.join(names)} (nearest {best}, off by {d})"


def estimate_text_colour(pic: Picture, box: tuple, max_samples: int = 3000):
    """(text colour, background colour, light_on_dark) for the pixels of a text node, or None when no glyph
    pixels differ from the background. The text colour is the median of the darkest 5% of the box (the
    lightest 5% when the background is dark or coloured), taken among pixels that differ from the background."""
    l, t, r, b = (int(v) for v in box)
    l, t = max(l, 0), max(t, 0)
    r, b = min(r, pic.width), min(b, pic.height)
    if r <= l or b <= t:
        return None
    area = (r - l) * (b - t)
    step = max(1, int(math.sqrt(area / max_samples)))
    pixels = [pic.px(x, y) for y in range(t, b, step) for x in range(l, r, step)]
    background = median_colour(sample_grid(pic, (l, t, r, b), 9))
    light_on_dark = lum(background) < 140
    glyph = [p for p in pixels if dist(p, background) > 30]
    if not glyph:
        return None
    glyph.sort(key=lum, reverse=light_on_dark)
    k = max(1, min(len(glyph) // 3 or 1, math.ceil(0.05 * len(pixels))))
    return median_colour(glyph[:k]), background, light_on_dark


def k_text_colours(shot, rule, ctx):
    allowed = rule.get("tokens", ["ink", "ink-2", "ink-3", "primary"])
    light = rule.get("light_tokens", ["on-primary"])
    tol = ctx.tol(rule, "text_tol")
    offenders, checked = [], 0
    for n in scoped(shot, rule):
        est = estimate_text_colour(shot.picture, n.bounds)
        if est is None:
            continue
        colour, background, light_on_dark = est
        checked += 1
        names = light if light_on_dark else allowed
        d, best = min((dist(colour, token_colour(ctx.tokens, t)), t) for t in names)
        if d > tol:
            offenders.append((d, f"{n.label()[:30]!r} {rgb_hex(colour)} on {rgb_hex(background)} (nearest {best}, off by {d})"))
    offenders.sort(reverse=True)
    if not checked:
        return False, [], "no text pixels found to check"
    return not offenders, [o[1] for o in offenders[:3]] or f"{checked} text nodes", \
        f"text colour within {tol} of {', '.join(allowed)} (light text: {', '.join(light)})"


def k_wash_top(shot, rule, ctx):
    top_frac = rule.get("top_frac", 0.3)
    top = median_colour(sample_grid(shot.picture, (0, shot.bars["status"], shot.width, top_frac * shot.height), 15))
    lower = median_colour(sample_grid(shot.picture, (0, 0.5 * shot.height, shot.width, shot.height - shot.bars["nav"]), 15))
    page = token_colour(ctx.tokens, rule.get("page", "page"))
    tint = dist(top, lower)
    lower_off = dist(lower, page)
    lavender = top[2] >= top[0] and top[2] >= top[1]
    ok = tint >= rule.get("min_delta", 3) and lower_off <= rule.get("lower_tol", 6) and (lavender or not rule.get("lavender", True))
    measured = f"top {rgb_hex(top)} lower {rgb_hex(lower)} (tint {tint}, lower off page by {lower_off})"
    return ok, measured, "top 30% tinted (blue highest) and the lower half near the page colour"


EMOJI = re.compile("[\U0001F000-\U0001FAFF\U00002600-\U00002712\U00002714-\U000027BF\U0000FE0F\U00002B50\U00002B55"
                   "\U0000231A\U0000231B\U000023E9-\U000023FA]")


def k_no_emoji_or_bang(shot, rule, ctx):
    words = [w.lower() for w in rule.get("banned_words", [])]
    hits = []
    for n in scoped(shot, rule):
        texts = [n.text] + ([n.desc] if rule.get("desc") else [])
        for t in texts:
            if "!" in t or EMOJI.search(t) or any(re.search(r"\b%s\b" % re.escape(w), t.lower()) for w in words):
                hits.append(t[:60])
    return not hits, hits[:5], "no emoji, no exclamation mark" + (f", none of {', '.join(words)}" if words else "")


def k_sentence_rule(shot, rule, ctx):
    nodes = [n for n in find(shot, rule["node"]) if n.text.strip()]
    if not nodes:
        return False, None, f"node {rule['node']} not found"
    limit = rule.get("max", 60)
    bad = [n.text for n in nodes if len(n.text) > limit or (rule.get("full_stop", True) and not n.text.rstrip().endswith("."))]
    return not bad, [f"{len(t)} chars: {t!r}" for t in bad] or [n.text for n in nodes][:3], \
        f"at most {limit} characters, ends with a full stop"


def k_touch_target(shot, rule, ctx):
    min_dp = rule.get("min_dp", 48)
    small = []
    for n in scoped(shot, rule, text_only=False):
        if not n.clickable:
            continue
        l, t, r, b = n.bounds
        horizontal = [a for a in n.ancestors() if a.cls.endswith("HorizontalScrollView")]
        if horizontal and r >= min(shot.width, *(a.bounds[2] for a in horizontal)):
            continue
        lists = [a for a in n.ancestors()
                 if not a.cls.endswith("HorizontalScrollView") and
                 (a.scrollable or a.cls.endswith(("RecyclerView", "ListView", "ScrollView")))]
        if lists and b >= min(shot.height - shot.bars["nav"], *(a.bounds[3] for a in lists)):
            continue
        size = shot.to_dp(min(r - l, b - t))
        if size < min_dp - 0.5:
            small.append(f"{n.label()[:30]!r} {shot.to_dp(r - l):.0f}x{shot.to_dp(b - t):.0f}dp")
    return not small, small[:6], f"every clickable node at least {min_dp} dp in its smaller side"


def k_no_overlap_systembars(shot, rule, ctx):
    status, nav = shot.bars["status"], shot.height - shot.bars["nav"]
    hits = [f"{n.label()[:30]!r} {n.bounds}" for n in scoped(shot, rule)
            if n.bounds[1] < status or n.bounds[3] > nav]
    return not hits, hits[:5] or f"bars {shot.to_dp(status):.0f}dp / {shot.to_dp(shot.bars['nav']):.0f}dp ({shot.bars['source']})", \
        "no text under the status bar or the navigation bar"


def k_order(shot, rule, ctx):
    axis = 0 if rule.get("axis") == "x" else 1
    positions = []
    for sel in rule["items"]:
        found = find(shot, sel)
        if not found:
            return False, positions, f"{sel} not found"
        positions.append(("/".join(str(v) for v in sel.values()), round(shot.to_dp(found[0].bounds[axis]), 1)))
    ok = all(positions[i][1] < positions[i + 1][1] for i in range(len(positions) - 1))
    return ok, positions, "items appear " + ("left to right" if axis == 0 else "top to bottom") + " in the given order"


def k_footer(shot, rule, ctx):
    within = rule.get("within_dp", 72)
    margin = rule.get("margin_dp", ctx.defaults.get("margin_dp", 24))
    tol = rule.get("tol_dp", 3)
    left, right = find(shot, rule["left"]), find(shot, rule["right"])
    if not left or not right:
        return False, None, f"footer items not found (left {bool(left)}, right {bool(right)})"
    l, r = left[0].bounds, right[0].bounds
    gap_l, gap_r = shot.to_dp(shot.height - l[3]), shot.to_dp(shot.height - r[3])
    left_x, right_x = shot.to_dp(l[0]), shot.to_dp(shot.width - r[2])
    ok = gap_l <= within and gap_r <= within and abs(left_x - margin) <= tol and abs(right_x - margin) <= tol
    measured = {"left_bottom_gap_dp": round(gap_l, 1), "right_bottom_gap_dp": round(gap_r, 1),
                "left_x_dp": round(left_x, 1), "right_margin_dp": round(right_x, 1)}
    return ok, measured, f"both within {within} dp of the bottom, at the left and right margins ({margin} dp ±{tol})"


def k_no_lines(shot, rule, ctx):
    """No divider: no node 0.5 to 2.5 dp high wider than half the screen, and no pixel row inside the content
    where most columns differ by more than 20 from the rows a little above and below."""
    hits = []
    for n in scoped(shot, rule, text_only=False):
        l, t, r, b = n.bounds
        if 0 < shot.to_dp(b - t) <= 2.5 and (r - l) > shot.width / 2:
            hits.append(f"node {n.label()[:30]!r} {shot.to_dp(b - t):.1f}dp high")
    pic = shot.picture
    gap = max(2, int(round(shot.dp(2))) + 1)
    top, bottom = shot.bars["status"] + gap, shot.height - shot.bars["nav"] - gap
    columns = range(0, pic.width, max(1, pic.width // 200))
    need = rule.get("row_share", 0.6) * len(columns)
    for y in range(top, bottom):
        count = 0
        for x in columns:
            c = pic.px(x, y)
            if dist(c, pic.px(x, y - gap)) > 20 and dist(c, pic.px(x, y + gap)) > 20:
                count += 1
        if count > need:
            hits.append(f"pixel row at {shot.to_dp(y):.0f}dp")
            break
    return not hits, hits[:5], "no divider lines"


KINDS = {
    "activity": k_activity, "text_present": k_text_present, "text_absent": k_text_absent,
    "no_class": k_no_class, "count_primary": k_count_primary, "left_edge": k_left_edge,
    "colour_at": k_colour_at, "text_colours": k_text_colours, "wash_top": k_wash_top, "not_flat": k_wash_top,
    "no_emoji_or_bang": k_no_emoji_or_bang, "sentence_rule": k_sentence_rule, "touch_target": k_touch_target,
    "no_overlap_systembars": k_no_overlap_systembars, "order": k_order, "footer": k_footer, "no_lines": k_no_lines,
}


# ── rules file ───────────────────────────────────────────────────────────────

def load_rules(path: Path = RULES) -> dict:
    with open(path) as f:
        return json.load(f)


def screen_rules(doc: dict, name: str) -> list:
    """The rules of a screen with groups expanded and the global rules added (unless the screen opts out)."""
    screen = doc["screens"][name]
    out = []

    def expand(rules):
        for rule in rules:
            if "include" in rule:
                expand(doc["groups"][rule["include"]])
            else:
                out.append(rule)

    expand(screen.get("rules", []))
    if screen.get("global", True):
        skip = set(screen.get("skip_global", []))
        expand([r for r in doc.get("global", []) if r["id"] not in skip])
    return out


def state_holds(shot: Shot, rule: dict) -> Optional[bool]:
    """True/False when the rule names a machine check of its state ("when" / "when_absent"), else None."""
    if "when" not in rule and "when_absent" not in rule:
        return None
    ok = True
    if "when" in rule:
        ok = ok and bool(find(shot, rule["when"]))
    if "when_absent" in rule:
        ok = ok and not find(shot, rule["when_absent"])
    return ok


def evaluate(shot: Shot, doc: dict, name: str, mode: str = "screen") -> list:
    """One result dict for each rule: id, kind, status PASS/FAIL/SKIP, measured, reason, why."""
    ctx = Ctx(doc["tokens"], doc.get("defaults", {}))
    results = []
    for rule in screen_rules(doc, name):
        entry = {"id": rule["id"], "kind": rule["kind"], "why": rule.get("why", "")}
        if rule.get("needs_state"):
            holds = state_holds(shot, rule)
            if holds is False or (holds is None and mode == "all"):
                entry.update(status="SKIP", measured=None,
                             reason=f"needs state: {rule['needs_state']}" + (" (not present)" if holds is False else ""))
                results.append(entry)
                continue
        try:
            ok, measured, reason = KINDS[rule["kind"]](shot, rule, ctx)
        except (KeyError, ValueError, re.error) as e:
            ok, measured, reason = False, None, f"rule error: {e!r}"
        entry.update(status="PASS" if ok else "FAIL", measured=measured, reason=reason)
        results.append(entry)
    return results


def detect(shot: Shot, doc: dict) -> tuple:
    """(screen name or None, [(score, name), ...]) from the resumed activity and the marker nodes."""
    scores = []
    for name, screen in doc["screens"].items():
        d = screen.get("detect")
        if not d:
            continue
        score = 0.0
        if d.get("activity"):
            if shot.activity:
                if not k_activity(shot, {"name": d["activity"]}, None)[0]:
                    continue
                score += 1
        if not all(find(shot, m) for m in d.get("markers", [])):
            continue
        if any(find(shot, m) for m in d.get("absent", [])):
            continue
        score += len(d.get("markers", [])) + 0.5 * len(d.get("absent", []))
        scores.append((score, name))
    scores.sort(key=lambda s: -s[0])
    if not scores or (len(scores) > 1 and scores[0][0] == scores[1][0]):
        return None, scores
    return scores[0][1], scores


# ── capture and command line ─────────────────────────────────────────────────

class CaptureError(Exception):
    pass


def capture(out_dir: Path) -> None:
    """Saves screen.png, window.xml, device.json (density, size, activity, bars) from the phone into out_dir."""
    out_dir.mkdir(parents=True, exist_ok=True)
    code, png = walk.adb(["exec-out", "screencap", "-p"], timeout=20, binary=True)
    if code != 0 or not png.startswith(PNG_SIG):
        raise CaptureError(f"screencap failed (adb exit {code})")
    (out_dir / "screen.png").write_bytes(png)
    remote = "/sdcard/look_dump.xml"
    code, out = walk.shell(f"uiautomator dump {remote}", timeout=30)
    if code != 0:
        raise CaptureError(f"uiautomator dump failed: {out.strip()[:200]}")
    code, xml_text = walk.adb(["exec-out", "cat", remote], timeout=20)
    walk.shell(f"rm -f {remote}")
    if code != 0 or "<hierarchy" not in xml_text:
        raise CaptureError("could not read the view listing")
    (out_dir / "window.xml").write_text(xml_text)
    _, density = walk.shell("wm density")
    _, size = walk.shell("wm size")
    _, activities = walk.shell("dumpsys activity activities", timeout=30)
    _, window = walk.shell("dumpsys window", timeout=30)
    height = struct.unpack(">I", png[20:24])[0]
    device = {"density": parse_density(density), "size": parse_size(size),
              "activity": parse_resumed_activity(activities), "captured": time.strftime("%Y-%m-%d %H:%M:%S"),
              **parse_insets(window, height)}
    (out_dir / "device.json").write_text(json.dumps(device, indent=2))


def load_shot(folder: Path, density: Optional[float], doc: dict) -> Shot:
    png, xml_path = folder / "screen.png", folder / "window.xml"
    if not png.exists() or not xml_path.exists():
        raise CaptureError(f"{folder} needs screen.png and window.xml")
    device = {}
    if (folder / "device.json").exists():
        device = json.loads((folder / "device.json").read_text())
    picture = load_picture(png.read_bytes())
    nodes = parse_dump(xml_path.read_text())
    dens = density or device.get("density") or guess_density(picture.width)
    return Shot(picture, nodes, dens, device.get("activity"), device=device, defaults=doc.get("defaults", {}))


def report(shot: Shot, doc: dict, name: str, results: list, out_dir: Path, mode: str) -> int:
    failed = [r for r in results if r["status"] == "FAIL"]
    print(f"== look {name}  ({shot.width}x{shot.height}px, density {shot.density:g}, activity {shot.activity or 'unknown'})")
    for r in results:
        measured = r["measured"] if isinstance(r["measured"], str) else json.dumps(r["measured"], ensure_ascii=False)
        print(f"  {r['status']} {r['id']}: {r['reason']}; measured {measured}" + (f"  [{r['why']}]" if r["why"] else ""))
    passed = not failed
    summary = {"screen": name, "mode": mode, "passed": passed, "density": shot.density, "activity": shot.activity,
               "size": [shot.width, shot.height], "bars": shot.bars, "results": results,
               "counts": {s: sum(1 for r in results if r["status"] == s) for s in ("PASS", "FAIL", "SKIP")}}
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "look.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False))
    c = summary["counts"]
    print(f"{'PASS' if passed else 'FAIL'} look {name}: {c['PASS']} passed, {c['FAIL']} failed, {c['SKIP']} skipped"
          f"  (evidence: {out_dir})")
    return 0 if passed else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("screen", nargs="?")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--from", dest="source", default=None)
    parser.add_argument("--out", default=None)
    parser.add_argument("--density", type=float, default=None)
    parser.add_argument("--rules", default=str(RULES))
    args = parser.parse_args(argv)
    doc = load_rules(Path(args.rules))

    if args.list:
        for name, screen in doc["screens"].items():
            print(f"{name:22} {len(screen_rules(doc, name)):3} rules  {screen.get('description', '')}")
        return 0
    if not args.screen:
        parser.print_usage()
        return 2
    if args.screen != "all" and args.screen not in doc["screens"]:
        print(f"unknown screen {args.screen!r}; walk.sh look --list shows them")
        return 2

    if args.source:
        folder = Path(args.source)
        out_dir = Path(args.out) if args.out else folder
    else:
        folder = out_dir = Path(args.out) if args.out else \
            HERE / "evidence" / f"{time.strftime('%Y%m%d-%H%M%S')}-look-{args.screen}"
        try:
            capture(folder)
        except CaptureError as e:
            print(f"FAIL capture: {e}")
            return 2
    try:
        shot = load_shot(folder, args.density, doc)
    except (CaptureError, ValueError, ET.ParseError) as e:
        print(f"FAIL {e}")
        return 2

    name, mode = args.screen, "screen"
    if name == "all":
        mode = "all"
        name, scores = detect(shot, doc)
        if name is None:
            print(f"FAIL cannot tell which screen is in front (activity {shot.activity}; candidates "
                  f"{[n for _, n in scores] or 'none'}). Name the screen: walk.sh look <screen>")
            return 2
        print(f"detected screen: {name}")
    return report(shot, doc, name, evaluate(shot, doc, name, mode), out_dir, mode)


if __name__ == "__main__":
    sys.exit(main())
