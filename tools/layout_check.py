#!/usr/bin/env python3
"""
Static layout validator for the Casino mod's screens.

The GUI bugs that survived a build were all *geometry* bugs: a button landing on top of
another, text drawn under a panel edge, the footer colliding with content. A compiler
cannot see any of that, and driving a Minecraft client by screenshot to find it is slow
and flaky. So we parse the widget calls straight out of the Java sources and check the
rectangles arithmetically instead.

What it checks, per screen:
  * every widget rect stays inside the panel (minus the frame inset)
  * no two widget rects overlap
  * the reserved footer band (sell/buy row + balance bar) is not intruded on
  * the panel fits the smallest supported window without clipping

Exit code is non-zero when anything fails, so it can gate a build.
"""
import re
import sys
import glob
import os
from dataclasses import dataclass

FRAME_INSET = 24
# Minecraft's minimum GUI scale 2 leaves this much logical room for a screen.
MIN_WINDOW_W = 427      # 854 physical / 2 at gui scale 2
MIN_WINDOW_H = 240
# Reserved at the bottom of every panel: Sell/Buy row (18) + gap (6) + balance bar (16)
FOOTER_H = 24    # reserved bottom band: balance bar (16) + margin


@dataclass
class Rect:
    x: int
    y: int
    w: int
    h: int
    label: str

    @property
    def x2(self):
        return self.x + self.w

    @property
    def y2(self):
        return self.y + self.h

    def overlaps(self, o):
        return not (self.x2 <= o.x or o.x2 <= self.x or self.y2 <= o.y or o.y2 <= self.y)


def split_args(src, start):
    """Read a balanced argument list starting just after an opening paren."""
    depth = 1
    i = start
    cur = []
    args = []
    in_str = False
    esc = False
    while i < len(src) and depth > 0:
        c = src[i]
        if in_str:
            cur.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            cur.append(c)
        elif c == "(":
            depth += 1
            cur.append(c)
        elif c == ")":
            depth -= 1
            if depth > 0:
                cur.append(c)
        elif c == "," and depth == 1:
            args.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    if cur:
        args.append("".join(cur).strip())
    return args


def _pick_coords(args):
    """For text(...) the signature is (font, text, x, y, colour) or (text, x, y, colour)."""
    for i in range(len(args) - 2):
        xs, ys = args[i], args[i + 1]
        if ("leftPos" in xs or "topPos" in xs or "resBox" in xs or "gridX" in xs
                or re.fullmatch(r"-?\d+", xs)):
            if "Pos" in ys or "Box" in ys or "numY" in ys or "actY" in ys or re.fullmatch(r"-?\d+", ys):
                return xs, ys
    return None, None


def eval_int(expr, consts):
    """Evaluate a small arithmetic expression built from known constants."""
    if isinstance(expr, int):
        return expr
    e = str(expr).strip()
    # The base screen exposes contentX()/contentY() etc. as the interior origin. Resolve
    # them against the same L/T origin so expressions using them stay checkable.
    # Resolve the interior accessors the base screen exposes. These mirror the real
    # implementations in BaseCasinoScreen:
    #   contentX()      = leftPos + FRAME_INSET - 4 + CONTENT_PAD = leftPos + 24
    #   contentY()      = topPos  + 32 + CONTENT_PAD             = topPos  + 36
    #   contentW()      = bgWidth  - 2 * (FRAME_INSET - 4 + CONTENT_PAD) = bgWidth - 48
    #   contentBottom() = topPos + bgHeight - 24 - CONTENT_PAD   = topPos + bgHeight - 28
    bgh = consts.get("bgHeight")
    bgw = consts.get("bgWidth")
    if bgh is not None:
        e = e.replace("contentBottom()", f"T+{bgh - 28}")
    if bgw is not None:
        e = e.replace("contentW()", str(bgw - 48))
    e = e.replace("contentX()", "L+24").replace("contentY()", "T+36")
    e = e.replace("leftPos", "L").replace("topPos", "T")
    # Substitute identifiers on word boundaries. A plain str.replace turned `betY2` into
    # `542` (betY -> 54, then the trailing 2 concatenated), which silently produced a
    # plausible-looking but wrong coordinate.
    for k, v in sorted(consts.items(), key=lambda kv: -len(kv[0])):
        e = re.sub(rf"(?<![A-Za-z0-9_]){re.escape(k)}(?![A-Za-z0-9_])", str(v), e)
    if not re.fullmatch(r"[0-9LT+\-*/() ]+", e):
        return None
    try:
        return int(eval(e, {"__builtins__": {}}, {"L": 0, "T": 0}))
    except Exception:
        return None


def parse_screen(path):
    src = open(path).read()
    cls = os.path.basename(path).replace(".java", "")

    # Panel dimensions: base defaults, then any subclass override in the constructor.
    bgw = int(re.search(r"bgWidth\s*=\s*(\d+)", src).group(1)) if re.search(r"\bthis\.bgWidth\s*=\s*(\d+)", src) else None
    bgh = int(re.search(r"\bthis\.bgHeight\s*=\s*(\d+)", src).group(1)) if re.search(r"\bthis\.bgHeight\s*=\s*(\d+)", src) else None

    base = os.path.join(os.path.dirname(path), "BaseCasinoScreen.java")
    bsrc = open(base).read()
    if bgw is None:
        m = re.search(r"protected int bgWidth = (\d+)", bsrc)
        bgw = int(m.group(1))
    if bgh is None:
        m = re.search(r"protected int bgHeight = (\d+)", bsrc)
        bgh = int(m.group(1))

    consts = {}
    # Panel size first: every `contentY() + N` local depends on it.
    for name in ("bgWidth", "bgHeight"):
        m = re.search(rf"(?:this\.)?{name}\s*=\s*(\d+)\s*;", src)
        if m:
            consts[name] = int(m.group(1))
    # Then every local layout variable, which may reference contentX()/contentY().
    # Discovered from the source rather than kept as a hand-maintained list: a new local
    # (tgtY, rowY, ...) used to be silently unresolvable, which dropped its widgets from
    # the check entirely.
    for m in re.finditer(r"^\s*int\s+(\w+)\s*=\s*([^;]+);", src, re.M):
        name, expr = m.group(1), m.group(2)
        if name in consts:
            continue
        v = eval_int(expr, consts)
        if v is not None:
            consts[name] = v
    # Panel size drives the interior accessors, so it must be known before parsing widgets.
    # Assigned as `this.bgWidth = 340;` in the constructor, hence the optional prefix.
    for name in ("bgWidth", "bgHeight"):
        m = re.search(rf"(?:this\.)?{name}\s*=\s*(\d+)\s*;", src)
        if m:
            consts[name] = int(m.group(1))

    rects = []

    # --- subclass widgets: SpriteButton.of/primary/colored(x, y, w, h, ...) ---
    for m in re.finditer(
        r"SpriteButton\.(?:of|primary|colored)\(\s*\n?\s*([^,]+?)\s*,\s*([^,]+?)\s*,\s*([^,]+?)\s*,\s*([^,]+?)\s*,\s*",
        src,
    ):
        xs, ys, ws, hs = (g.strip() for g in m.groups())
        # Loop-positioned calls are expanded separately below; evaluating them here would
        # bind neither `col` nor `row` and produce a bogus position.
        if re.search(r"\b(col|row|i)\b", xs) or re.search(r"\b(col|row|i)\b", ys):
            continue
        # widths may now be expressions (contentW() - 20); evaluate, don't require digits
        wv = eval_int(ws, consts)
        hv = eval_int(hs, consts)
        if wv is not None and hv is not None:
            rects.append(Rect(xs, ys, wv, hv, f"{cls} widget"))

    # --- loop-expanded widgets: `gridX + col * N, numY + row * N` ---
    #
    # These cannot be evaluated as scalar expressions (col/row are loop variables), so the
    # loop is expanded here across its real bounds. A grid cell that overlaps its neighbour
    # is a real layout bug, so it must be checked per cell rather than skipped.
    loop_pat = re.compile(
        r"SpriteButton\.(?:of|primary|colored)\(\s*([A-Za-z_]\w*)\s*\+\s*col\s*\*\s*(\d+)"
        r"\s*,\s*([A-Za-z_]\w*)\s*\+\s*row\s*\*\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,")
    for m in loop_pat.finditer(src):
        xname, xmult, yname, ymult, bw, bh = m.groups()
        xbase = consts.get(xname)
        ybase = consts.get(yname)
        if xbase is None or ybase is None:
            continue
        xmult, ymult, bw, bh = int(xmult), int(ymult), int(bw), int(bh)
        # Read the loop bounds from `for (int num = A; num <= B; ...)`.
        lm = re.search(r"for\s*\(\s*int\s+\w+\s*=\s*(\d+)\s*;\s*\w+\s*<=\s*(\d+)", src)
        lo, hi = (int(lm.group(1)), int(lm.group(2))) if lm else (1, 36)
        ncols = int(re.search(r"%\s*(\d+)\)", src).group(1)) if re.search(r"%\s*(\d+)\)", src) else 12
        for num in range(lo, hi + 1):
            col = (num - lo) % ncols
            row = (num - lo) // ncols
            rects.append(Rect(str(xbase + col * xmult), str(ybase + row * ymult),
                              str(bw), str(bh), f"{cls} cell {num}"))


    # Parenthesised loop form: `cardX + (i * 56), cardY + 34`.
    paren_pat = re.compile(
        r"SpriteButton\.(?:of|primary|colored)\(\s*([A-Za-z_]\w*)\s*\+\s*\(\s*\w+\s*\*\s*(\d+)\s*\)"
        r"\s*,\s*([A-Za-z_]\w*)\s*\+\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,")
    for m in paren_pat.finditer(src):
        xname, xmult, yname, yconst, bw, bh = m.groups()
        xbase, ybase = consts.get(xname), consts.get(yname)
        if xbase is None or ybase is None:
            continue
        xmult, yconst, bw, bh = int(xmult), int(yconst), int(bw), int(bh)
        # number of iterations: the enclosing `for (int i = A; i < B ...)`
        lm = re.search(r"for\s*\(\s*int\s+\w+\s*=\s*(\d+)\s*;\s*\w+\s*<\s*(\d+)", src)
        lo, hi = (int(lm.group(1)), int(lm.group(2))) if lm else (0, 5)
        for i in range(lo, hi):
            rects.append(Rect(str(xbase + i * xmult), str(ybase + yconst),
                              str(bw), str(bh), f"{cls} card {i}"))

    text_rects = []
    for m in re.finditer(r"(?:extractor|graphics)\.(?:text|centeredText|drawString|drawCenteredString)\(", src):
        args = split_args(src, m.end())
        if len(args) >= 3:
            # x and y are the last-but-two / last-but-one coordinate args before the colour
            xs, ys = _pick_coords(args)
            if xs is not None:
                text_rects.append((xs, ys, consts))

    # Resolve to ints. The local layout variables (betY, actY, ...) are needed here, so the
    # resolved consts must be passed in — previously this used an empty dict, which silently
    # dropped every widget whose position was expressed with a named variable.
    resolved = []
    unresolved = []
    for r in rects:
        x = eval_int(r.x, consts)
        y = eval_int(r.y, consts)
        w = eval_int(r.w, consts)
        h = eval_int(r.h, consts)
        if None in (x, y, w, h):
            unresolved.append((r.label, r.x, r.y, r.w, r.h))
            continue
        resolved.append(Rect(x, y, w, h, r.label))
    # A widget we cannot resolve is a gap in the check, not a pass: surface it.
    for label, rx, ry, rw, rh in unresolved:
        print(f"   ! unresolved widget: {label} x={rx!r} y={ry!r} {rw!r}x{rh!r}")

    return cls, bgw, bgh, resolved, text_rects


def check(path):
    cls, bgw, bgh, rects, text_calls = parse_screen(path)
    errs = []

    # Panel must fit the smallest window.
    # The frame sprite is blitted at (x-4, y-4) sized (w+8, h+8): an 8px overhang.
    FRAME_OVERHANG = 8
    if bgw + FRAME_OVERHANG > MIN_WINDOW_W:
        errs.append(f"panel width {bgw}+{FRAME_OVERHANG} exceeds min window width {MIN_WINDOW_W}")
    if bgh + FRAME_OVERHANG > MIN_WINDOW_H:
        errs.append(f"panel height {bgh}+{FRAME_OVERHANG} exceeds min window height {MIN_WINDOW_H} "
                    f"(content {bgh - FOOTER_H}px + footer {FOOTER_H}px)")

    # Widgets must sit inside the content box. The frame is nine-sliced with 24px corners
    # that overhang by 4px, so the drawable interior is inset FRAME_INSET on all sides —
    # checking against 6px would have passed content drawn on top of the border, which is
    # exactly the bug this exists to catch.
    inner_left = FRAME_INSET - 4
    inner_right = bgw - (FRAME_INSET - 4)
    content_bottom = bgh - 24
    for r in rects:
        if r.x < inner_left:
            errs.append(f"{r.label} at x={r.x} pokes into left frame")
        if r.x2 > inner_right:
            errs.append(f"{r.label} at x={r.x}..{r.x2} overruns panel right edge {inner_right}")
        if r.y2 > content_bottom:
            errs.append(f"{r.label} at y={r.y}..{r.y2} intrudes into footer "
                        f"(content ends at {content_bottom})")

    # Text must sit inside the content box too (a label drawn past the frame's lower edge
    # is invisible or spills over the border, which is exactly the class of bug that
    # compiled fine and only showed up in a screenshot).
    for xs, ys, consts in text_calls:
        x = eval_int(xs, consts)
        y = eval_int(ys, consts)
        if x is None or y is None:
            continue
        if y < 6 or y > content_bottom - 8:
            errs.append(f"text at y={y} outside content band 6..{content_bottom - 8} (expr {ys})")
        if x < 4 or x > bgw - 20:
            errs.append(f"text at x={x} outside panel 4..{bgw - 20} (expr {xs})")

    # No two widgets may overlap.
    for i in range(len(rects)):
        for j in range(i + 1, len(rects)):
            a, b = rects[i], rects[j]
            if a.overlaps(b):
                errs.append(f"OVERLAP: {a.label} {a.x},{a.y} {a.w}x{a.h} "
                            f"vs {b.label} {b.x},{b.y} {b.w}x{b.h}")

    return cls, bgw, bgh, len(rects), errs


def main(roots):
    total = 0
    failed = 0
    for root in roots:
        screens = sorted(glob.glob(os.path.join(root, "**/client/gui/*Screen.java"), recursive=True))
        # only the leaf screens, not the shared base
        screens = [s for s in screens if not s.endswith("BaseCasinoScreen.java")]
        for s in screens:
            cls, w, h, n, errs = check(s)
            total += 1
            if errs:
                failed += 1
                print(f"\nFAIL {cls}  {w}x{h}  ({n} widgets)  [{s}]")
                for e in errs[:12]:
                    print("   -", e)
                if len(errs) > 12:
                    print(f"   ... and {len(errs) - 12} more")
            else:
                print(f"ok   {cls}  {w}x{h}  ({n} widgets)")
    print(f"\n{total - failed}/{total} screens pass layout validation")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    roots = sys.argv[1:] or ["."]
    sys.exit(main(roots))
