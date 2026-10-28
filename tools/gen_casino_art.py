#!/usr/bin/env python3
"""
Chips Casino — procedural pixel-art asset generator.

Produces every GUI sprite (light + dark themes) and the block textures for the
casino mod, deterministically, using Pillow only.

Design rules enforced here:
  * all shading goes through mul()/mix(), which snap the result onto the active
    palette — so the set can never drift into off-palette colours and stays
    cohesive by construction;
  * alpha is strictly 0 or 255 everywhere (pixel art, no AA fringes);
  * light comes from the top-left on every bevel (highlight up-left, shadow
    down-right);
  * shapes are sampled at the pixel centre, i.e. exact nearest-neighbour
    behaviour rather than a filter.

Usage:
    python3 gen_casino_art.py              # write both themes + block textures
    python3 gen_casino_art.py --preview    # + contact sheets
    python3 gen_casino_art.py --verify     # + dimension/mode/alpha report
"""

from __future__ import annotations

import argparse
import math
import os
import sys

from PIL import Image

# ==========================================================================
# palette
# ==========================================================================


def hx(s: str):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)


LIGHT = {
    "INK":          hx("#060505"),
    "BG_DEEP":      hx("#0F0C0A"),
    "BG_PANEL":     hx("#191512"),
    "BG_PANEL_HI":  hx("#241D16"),
    "INLAY":        hx("#0A0807"),
    "WOOD_D":       hx("#2A1B10"),
    "WOOD_M":       hx("#3E2917"),
    "WOOD_L":       hx("#553A22"),
    "WOOD_HI":      hx("#6B4A2C"),
    "BRASS_D":      hx("#7A5620"),
    "BRASS":        hx("#B8862C"),
    "BRASS_L":      hx("#D9A93F"),
    "GOLD":         hx("#F0C55A"),
    "GOLD_HI":      hx("#FFE9A8"),
    "GOLD_SH":      hx("#A87A22"),
    "BLACK":        hx("#131110"),
    "WHITE":        hx("#F4EFE2"),
    "CREAM":        hx("#EFE6D2"),
    "CREAM_D":      hx("#C9BFA8"),
    "CREAM_SH":     hx("#A99C82"),
    "RED":          hx("#B32630"),
    "RED_D":        hx("#6E1119"),
    "RED_L":        hx("#D8454E"),
    "RED_HI":       hx("#F2706F"),
    "POCKET_GREEN": hx("#1C8A4A"),
    "GREEN_DOT":    hx("#2FA45C"),
    "GREEN_FELT":   hx("#17603A"),
    "GREEN_FELT_D": hx("#0E4A2C"),
    "GREEN_FELT_L": hx("#2C7A4E"),
    "BLUE_FELT":    hx("#1A3A63"),
    "BLUE_FELT_D":  hx("#12294A"),
    "BLUE_FELT_L":  hx("#2C5788"),
    "STEEL":        hx("#8C939C"),
    "STEEL_L":      hx("#C2C8D0"),
    "STEEL_D":      hx("#4A5058"),
    "STEEL_SH":     hx("#31363C"),
    "LEATHER":      hx("#4A3423"),
    "LEATHER_D":    hx("#33220F"),
    "LEATHER_L":    hx("#66492E"),
    # extra shades used only by shadow profiles (kept in-palette on purpose)
    "GOLD_DIM":     hx("#6E5220"),
    "BRASS_DIM":    hx("#3F2E12"),
}

DARK = {
    "INK":          hx("#030304"),
    "BG_DEEP":      hx("#070708"),
    "BG_PANEL":     hx("#0C0C0E"),
    "BG_PANEL_HI":  hx("#131316"),
    "INLAY":        hx("#050506"),
    "WOOD_D":       hx("#17130F"),
    "WOOD_M":       hx("#221B15"),
    "WOOD_L":       hx("#2E251C"),
    "WOOD_HI":      hx("#3A2E22"),
    "BRASS_D":      hx("#3E3620"),
    "BRASS":        hx("#5C5232"),
    "BRASS_L":      hx("#6E6340"),
    "GOLD":         hx("#8A7C4E"),
    "GOLD_HI":      hx("#A99A6A"),
    "GOLD_SH":      hx("#4C4630"),
    "BLACK":        hx("#0B0B0C"),
    "WHITE":        hx("#BFBFC0"),
    "CREAM":        hx("#C0BDB2"),
    "CREAM_D":      hx("#8E8B82"),
    "CREAM_SH":     hx("#6B6963"),
    "RED":          hx("#5E2A2E"),
    "RED_D":        hx("#37181C"),
    "RED_L":        hx("#7A3A3E"),
    "RED_HI":       hx("#96504F"),
    "POCKET_GREEN": hx("#1E4433"),
    "GREEN_DOT":    hx("#2A5540"),
    "GREEN_FELT":   hx("#12291E"),
    "GREEN_FELT_D": hx("#0B1D15"),
    "GREEN_FELT_L": hx("#1C3A2A"),
    "BLUE_FELT":    hx("#14243A"),
    "BLUE_FELT_D":  hx("#0E1A29"),
    "BLUE_FELT_L":  hx("#1E3450"),
    "STEEL":        hx("#4A4E54"),
    "STEEL_L":      hx("#6E7278"),
    "STEEL_D":      hx("#2A2D31"),
    "STEEL_SH":     hx("#1B1D20"),
    "LEATHER":      hx("#2A1E14"),
    "LEATHER_D":    hx("#1A1109"),
    "LEATHER_L":    hx("#3B2B1C"),
    "GOLD_DIM":     hx("#2F2B1C"),
    "BRASS_DIM":    hx("#1E1B11"),
}

CLEAR = (0, 0, 0, 0)

# Render scale: 2x gives crisp 32x32 block textures and sharper GUI sprites
# while keeping all drawing code at the original 16x16 coordinate space.
SCALE = 2

# active theme, swapped by the build loop
_P = LIGHT
_SNAP: dict = {}
_TABLE: list = []


def set_theme(tab: dict) -> None:
    global _P, _SNAP, _TABLE
    _P = tab
    _SNAP = {}
    _TABLE = list({tuple(v[:3]): v for v in tab.values()}.values())


def snap(c) -> tuple:
    """Quantise a colour onto the active palette (nearest RGB, memoised)."""
    k = (c[0], c[1], c[2])
    hit = _SNAP.get(k)
    if hit is not None:
        return hit
    best, bd = None, 1 << 30
    for p in _TABLE:
        d = (p[0] - k[0]) ** 2 + (p[1] - k[1]) ** 2 + (p[2] - k[2]) ** 2
        if d < bd:
            bd, best = d, p
    _SNAP[k] = best
    return best


def mul(c, f: float):
    """Brightness scale, snapped back into the palette (bevel shading)."""
    return snap((max(0, min(255, int(c[0] * f))),
                 max(0, min(255, int(c[1] * f))),
                 max(0, min(255, int(c[2] * f)))))


def mix(a, b, t: float):
    """Blend two palette colours, snapped back into the palette."""
    t = max(0.0, min(1.0, t))
    return snap((int(a[0] + (b[0] - a[0]) * t),
                 int(a[1] + (b[1] - a[1]) * t),
                 int(a[2] + (b[2] - a[2]) * t)))


# ==========================================================================
# primitives
# ==========================================================================


def new(w: int, h: int) -> Image.Image:
    return Image.new("RGBA", (w, h), CLEAR)


def put(img, x: int, y: int, c) -> None:
    if c is CLEAR:
        if 0 <= x < img.width and 0 <= y < img.height:
            img.putpixel((x, y), CLEAR)
        return
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), (c[0], c[1], c[2], 255))


def hline(img, y, x0, x1, c):
    for x in range(max(0, x0), min(img.width - 1, x1) + 1):
        put(img, x, y, c)


def vline(img, x, y0, y1, c):
    for y in range(max(0, y0), min(img.height - 1, y1) + 1):
        put(img, x, y, c)


def box(img, x0, y0, x1, y1, c):
    for y in range(max(0, y0), min(img.height - 1, y1) + 1):
        hline(img, y, x0, x1, c)


def border(img, x0, y0, x1, y1, c):
    hline(img, y0, x0, x1, c)
    hline(img, y1, x0, x1, c)
    vline(img, x0, y0, y1, c)
    vline(img, x1, y0, y1, c)


def rnd(x: int, y: int, seed: int = 0) -> float:
    """Deterministic 0..1 hash noise."""
    n = (x * 374761393 + y * 668265263 + seed * 1442695041) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFF) / 255.0


def bands(img, x0, y0, x1, y1, rows):
    """Fill with explicit horizontal bands: rows = [(count, colour), ...]."""
    y = y0
    for count, c in rows:
        for _ in range(count):
            if y > y1:
                break
            hline(img, y, x0, x1, c)
            y += 1


def vramp(img, x0, y0, x1, y1, stops):
    """Vertical ramp across `stops`, split into equal flat bands (no dither)."""
    n = len(stops)
    total = y1 - y0 + 1
    if total <= 0:
        return
    for i in range(total):
        idx = min(n - 1, int(i * n / total))
        hline(img, y0 + i, x0, x1, stops[idx])


def cut_rounded(img, r: int):
    """Clear alpha outside a rounded rectangle (binary alpha only)."""
    w, h = img.size
    px = img.load()
    for y in range(h):
        for x in range(w):
            cx = min(x, w - 1 - x) + 0.5
            cy = min(y, h - 1 - y) + 0.5
            if cx < r and cy < r:
                if (r - cx) ** 2 + (r - cy) ** 2 > r * r:
                    px[x, y] = CLEAR


def disc(img, cx: float, cy: float, r: float, c, edge_hi=None, edge_lo=None):
    """Filled disc with an optional bevel ring, lit from the top-left."""
    for y in range(img.height):
        for x in range(img.width):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            if d > r:
                continue
            if r - d < 1.0 and (edge_hi or edge_lo):
                lit = (dx + dy) < 0
                put(img, x, y, edge_hi if lit else edge_lo)
            else:
                put(img, x, y, c)


# ==========================================================================
# casino_frame.png — 256x256, 9-slice, border inset 24
# ==========================================================================

FRAME = 256
INSET = 24


def edge_profile(p) -> list:
    """Vertical structure of one border band, outer edge (0) -> inner (23).

    Deliberately a pure function of the distance from the nearest image edge, so
    every edge strip is uniform along its length and survives 9-slice stretching
    without smearing. Only the four corners carry non-uniform detail.
    """
    return [
        p["INK"],          # 0  outer outline
        p["WOOD_D"],       # 1
        p["BRASS_D"],      # 2
        p["BRASS"],        # 3
        p["GOLD_HI"],      # 4  lit rim (light from top-left)
        p["GOLD"],         # 5
        p["BRASS_L"],      # 6
        p["BRASS"],        # 7
        p["BRASS_D"],      # 8
        p["WOOD_D"],       # 9  decorative channel
        p["WOOD_M"],       # 10
        p["WOOD_D"],       # 11
        p["LEATHER_D"],    # 12 shadowed groove
        p["WOOD_M"],       # 13
        p["BRASS_D"],      # 14
        p["BRASS"],        # 15
        p["GOLD_SH"],      # 16 inner fillet
        p["BRASS_D"],      # 17
        p["WOOD_D"],       # 18
        p["INK"],          # 19 inner outline
        p["BG_PANEL_HI"],  # 20 inner lip
        p["BG_PANEL"],     # 21
        p["BG_PANEL"],     # 22
        p["BG_DEEP"],      # 23 flows into the empty centre
    ]


def shadow_profile(p) -> list:
    """Same structure dimmed: bottom/right edges read as shadowed brass."""
    prof = edge_profile(p)
    return [
        p["INK"] if i in (0, 19) else
        (mix(c, p["INK"], 0.55) if i in (3, 4, 5, 6, 7, 15, 16, 17) else c)
        for i, c in enumerate(prof)
    ]


def ornament(p, size: int = 20) -> Image.Image:
    """20x20 brass corner medallion with a garnet, lit from the top-left.

    Reads as cast brass hardware: thin ink outline, a lit shoulder ring, an
    engraved petal ring, and a faceted garnet. No heavy dark band, which at this
    scale would look like a hole punched in the frame corner.
    """
    img = new(size, size)
    c = size / 2.0
    for y in range(size):
        for x in range(size):
            dx, dy = x + 0.5 - c, y + 0.5 - c
            r = math.hypot(dx, dy)
            lit = (dx + dy) < 0
            if r > 9.6:
                continue                                   # transparent margin
            if r > 8.8:
                put(img, x, y, p["INK"])                   # thin outline
            elif r > 7.6:
                put(img, x, y, p["GOLD"] if lit else p["BRASS_L"])
            elif r > 7.0:
                put(img, x, y, p["BRASS"])                 # shoulder
            elif r > 5.0:
                ang = math.atan2(dy, dx)
                if math.cos(ang * 6.0) > 0.15:             # petal ring
                    put(img, x, y, p["GOLD_HI"] if lit else p["BRASS_L"])
                else:
                    put(img, x, y, p["BRASS_D"])
            elif r > 4.2:
                put(img, x, y, p["INK"])                   # bezel line
            else:
                gem = abs(dx) + abs(dy)
                if gem > 3.4:
                    put(img, x, y, p["BRASS_D"])           # setting
                elif gem > 2.2:
                    put(img, x, y, p["RED_L"] if lit else p["RED_D"])
                elif gem > 0.9:
                    put(img, x, y, p["RED_HI"] if lit else p["RED"])
                else:
                    put(img, x, y, p["RED_HI"])
    return img


def casino_frame(p) -> Image.Image:
    prof, prof_s = edge_profile(p), shadow_profile(p)
    img = new(FRAME, FRAME)
    px = img.load()
    B = INSET
    for y in range(FRAME):
        t = (y, FRAME - 1 - y)
        for x in range(FRAME):
            l = (x, FRAME - 1 - x)
            m = min(t[0], t[1], l[0], l[1])
            if m >= B:
                px[x, y] = p["BG_DEEP"]
                continue
            # mitre: the nearest edge owns the pixel
            if m == t[0]:
                px[x, y] = prof[m]
            elif m == l[0]:
                px[x, y] = prof[m]
            elif m == t[1]:
                px[x, y] = prof_s[m]
            else:
                px[x, y] = prof_s[m]

    # 1px inner shadow that reads as depth under the inner outline
    for i in range(B, FRAME - B):
        px[i, B - 1] = mix(p["BG_DEEP"], p["BG_PANEL"], 0.5)
        px[i, FRAME - B] = mix(p["BG_DEEP"], p["BG_PANEL"], 0.25)

    med = ornament(p)
    for ox, oy in ((2, 2), (FRAME - 22, 2), (2, FRAME - 22), (FRAME - 22, FRAME - 22)):
        img.alpha_composite(med, (ox, oy))
    return img


# ==========================================================================
# header_bar.png — 256x32
# ==========================================================================


def header_bar(p) -> Image.Image:
    """256x32 title bar, stretched to the panel width by the screen code.

    Layout is dictated by BaseCasinoScreen, which draws the title at
    (x+10, y+8) and the balance line at (x+10, y+20) directly on top of this
    sprite. So:
      * rows 8..29 stay a calm dark plate -- no bright pixels, no busy detail --
        so both text lines stay legible;
      * the left gutter detail is confined to x <= 8, left of the x=10 text
        origin, so it can never sit under a glyph;
      * everything horizontal is uniform along x, so stretching to any panel
        width cannot smear it.
    """
    img = new(256, 32)
    w = 256

    # ---- calm dark body the text is drawn onto (rows 8..29)
    vramp(img, 0, 8, w - 1, 29, [p["BG_PANEL_HI"], p["BG_PANEL"], p["BG_PANEL"], p["INLAY"]])

    # ---- gold rail: uniform along x, so it survives stretching
    bands(img, 0, 0, w - 1, 31, [
        (1, p["INK"]),
        (1, p["BRASS_D"]),
        (1, p["GOLD"]),
        (1, p["GOLD_HI"]),
        (1, p["GOLD"]),
        (1, p["BRASS"]),
        (1, p["BRASS_D"]),
        (1, p["INK"]),
    ])
    # single hairline of sheen just below the rail (not under the text)
    hline(img, 8, 0, w - 1, mix(p["BG_PANEL_HI"], p["BRASS_D"], 0.22))

    # ---- bottom trim, kept to the last 2 rows so the balance line is clear
    hline(img, 30, 0, w - 1, p["BRASS_D"])
    hline(img, 31, 0, w - 1, p["INK"])

    # ---- left gutter plate, x = 1..8 (text starts at x = 10)
    for y in range(9, 30):
        for x in range(1, 9):
            put(img, x, y, p["INLAY"])
    vline(img, 0, 9, 29, p["INK"])
    vline(img, 9, 9, 29, p["BRASS_D"])          # divider just left of the text
    hline(img, 9, 1, 8, p["BRASS_D"])
    hline(img, 29, 1, 8, p["BRASS_D"])
    # small engraved diamond, low contrast so it reads as hardware, not a glyph
    for yy in range(-3, 4):
        for xx in range(-3, 4):
            d = abs(xx) + abs(yy)
            if d == 3:
                put(img, 5 + xx, 19 + yy, mix(p["BRASS_D"], p["BRASS"], 0.45))
            elif d == 1:
                put(img, 5 + xx, 19 + yy, mix(p["BRASS_D"], p["GOLD_SH"], 0.35))
    put(img, 5, 19, mix(p["BRASS"], p["GOLD_SH"], 0.5))

    # end caps
    vline(img, 0, 0, 31, p["INK"])
    vline(img, w - 1, 0, 31, p["INK"])
    return img


# ==========================================================================
# button.png / button_hover.png — 64x20
# ==========================================================================


def button(p, hover: bool) -> Image.Image:
    """64x20 sprite button. Total height budget is exactly 20:
       1 outline + 1 highlight + 15 fill + 1 shadow + 1 outline + 1 AA-free edge.
    """
    img = new(64, 20)
    w, h = 64, 20
    if hover:
        fill = [mix(p["BG_PANEL_HI"], p["BRASS_D"], 0.5),
                mix(p["BG_PANEL"], p["BRASS_D"], 0.32),
                mix(p["INLAY"], p["BRASS_D"], 0.18)]
        edge = p["GOLD"]
        top_hi = p["GOLD_HI"]
    else:
        fill = [p["BG_PANEL_HI"], p["BG_PANEL"], p["INLAY"]]
        edge = p["BRASS_D"]
        top_hi = p["GOLD_SH"]

    # flat 3-band fill, 16 rows (y=2..17)
    bands(img, 1, 2, w - 2, 17, [
        (6, fill[0]),
        (5, fill[1]),
        (5, fill[2]),
    ])
    # outline
    border(img, 0, 0, w - 1, h - 1, p["INK"])
    # lit top edge (light from the top-left) + shadowed right/bottom edges
    hline(img, 1, 1, w - 2, top_hi)
    vline(img, 1, 2, 17, edge)
    vline(img, w - 2, 2, 17, mul(edge, 0.6))
    hline(img, 18, 1, w - 2, mix(fill[2], p["INK"], 0.75))
    if hover:
        # inner gold glow so "lit up" is unmistakable at 1:1
        hline(img, 2, 2, w - 3, mix(fill[0], p["GOLD"], 0.45))
        vline(img, 2, 2, 16, mix(fill[1], p["GOLD"], 0.30))
        vline(img, w - 3, 2, 16, mix(fill[1], p["GOLD"], 0.18))
        hline(img, 16, 2, w - 3, mix(fill[2], p["GOLD"], 0.20))
    cut_rounded(img, 2)
    return img


# ==========================================================================
# balance_bar.png — 256x16
# ==========================================================================


def balance_bar(p) -> Image.Image:
    """256x16 money strip, stretched to the panel width by the screen code.

    The balance text is drawn at x+10 of the *panel*, but this sprite starts at
    the panel's left edge, so the coin glyph is kept inside x <= 12 and the
    strip beyond that is a flat, quiet plate that text can sit on.
    """
    img = new(256, 16)
    w, h = 256, 16
    vramp(img, 0, 0, w - 1, 15, [p["BG_PANEL"], p["INLAY"], mul(p["INLAY"], 0.7)])
    hline(img, 0, 0, w - 1, p["BRASS_D"])
    hline(img, 1, 0, w - 1, p["GOLD_SH"])
    hline(img, 2, 0, w - 1, p["BG_PANEL_HI"])
    hline(img, 6, 0, w - 1, mix(p["BG_PANEL_HI"], p["BRASS_D"], 0.20))
    hline(img, h - 2, 0, w - 1, mul(p["INLAY"], 0.5))
    hline(img, h - 1, 0, w - 1, p["INK"])
    vline(img, 0, 0, h - 1, p["INK"])
    vline(img, w - 1, 0, h - 1, p["INK"])

    # coin glyph, left (kept within x <= 11 so the balance text is never hit)
    cx, cy, R = 5.5, 8.0, 4.6
    for y in range(h):
        for x in range(0, 12):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            lit = (dx + dy) < -1.0
            if d > R:
                continue
            if d > R - 1.1:
                put(img, x, y, p["GOLD"] if lit else p["BRASS_D"])
            elif d > R - 2.0:
                put(img, x, y, p["GOLD_HI"] if lit else p["BRASS"])
            else:
                put(img, x, y, p["BRASS_D"] if lit else p["GOLD_SH"])
    # engraved currency bar + cross stroke
    vline(img, 5, 5, 10, p["BRASS_D"])
    hline(img, 7, 4, 6, p["BRASS_D"])
    # specular glint (top-left light)
    put(img, 3, 5, p["WHITE"])
    put(img, 4, 4, p["GOLD_HI"])
    # gutter divider, right of the coin column and clear of the text origin
    vline(img, 12, 2, h - 3, p["BRASS_D"])
    return img


# ==========================================================================
# chip.png — 16x16
# ==========================================================================


def chip(p) -> Image.Image:
    """Top-down poker chip: red body, 4 white edge dashes, gold centre pip."""
    W = H = 16
    cx = cy = 8.0
    R = 7.4
    img = new(W, H)
    for y in range(H):
        for x in range(W):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            r = math.hypot(dx, dy)
            if r > R:
                continue
            ang = math.atan2(dy, dx)
            lit = (dx + dy) < -2.5
            c = None
            if r > 6.7:                                  # outer rim
                c = p["RED_L"] if lit else p["RED_D"]
            elif r > 5.6:                                # 4 white edge dashes
                seg = ((ang + math.pi / 4) / (2 * math.pi) * 4.0) % 1.0
                if seg < 0.5:
                    c = p["WHITE"] if lit else p["CREAM"]
                else:
                    c = p["RED_D"] if not lit else p["RED"]
            elif r > 5.1:                                # dark inlay ring
                c = p["INK"]
            elif r > 3.4:                                # body field
                c = p["RED_L"] if lit else p["RED"]
            elif r > 2.8:                                # recessed ring
                c = p["RED_D"]
            elif r > 1.9:                                # inner hub
                c = p["RED_L"] if lit else p["RED_D"]
            # centre gold pip (drawn over the hub; never leaves a hole)
            if abs(dx) + abs(dy) < 1.2:
                c = p["GOLD_HI"]
            elif abs(dx) + abs(dy) < 2.1:
                c = p["GOLD"]
            put(img, x, y, c if c is not None else p["RED_D"])
    return img


# ==========================================================================
# roulette_wheel.png — 64x64
# ==========================================================================


def roulette_wheel(p) -> Image.Image:
    W = H = 64
    cx = cy = 32.0
    POCKETS = 24
    img = new(W, H)
    for y in range(H):
        for x in range(W):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            r = math.hypot(dx, dy)
            if r > 30.6:
                continue
            ang = math.atan2(dy, dx)
            lit = (dx + dy) < -9.0
            radial = (dx - dy) < 0 if abs(dx) > abs(dy) else (dx + dy) < 0

            # ---- wooden rim
            if r > 26.2:
                if r > 30.1:
                    put(img, x, y, p["INK"]); continue
                if r > 29.2:
                    put(img, x, y, p["WOOD_HI"] if lit else p["WOOD_D"]); continue
                # brass studs at the 4 diagonals
                stud = False
                for k in range(4):
                    a = math.pi / 4 + k * math.pi / 2
                    if math.hypot(dx - math.cos(a) * 28.0, dy - math.sin(a) * 28.0) < 1.7:
                        put(img, x, y, p["GOLD_HI"] if lit else p["BRASS"]); stud = True
                        break
                if stud:
                    continue
                # concentric grain rings (uniform, no noise)
                ring = int(r) % 3
                base = p["WOOD_L"] if lit else p["WOOD_M"]
                put(img, x, y, p["WOOD_M"] if ring == 0 else (p["WOOD_D"] if ring == 1 else base))
                continue
            # ---- brass lip
            if r > 25.2:
                put(img, x, y, p["GOLD"] if lit else p["BRASS"]); continue
            if r > 24.7:
                put(img, x, y, p["INK"]); continue

            # ---- pocket ring
            if r > 16.6:
                a2 = (ang + math.pi / 2) % (2 * math.pi)
                sector_f = a2 / (2 * math.pi / POCKETS)
                sector = int(sector_f) % POCKETS
                frac = sector_f % 1.0
                if frac < 0.10 or frac > 0.95:
                    put(img, x, y, p["BRASS_D"]); continue      # fret
                if sector == 0:
                    put(img, x, y, p["GREEN_DOT"] if lit else p["POCKET_GREEN"])
                elif sector % 2 == 0:
                    put(img, x, y, p["RED_L"] if lit else p["RED"])
                else:
                    put(img, x, y, mul(p["BLACK"], 2.2) if lit else p["BLACK"])
                continue

            # ---- centre turret (clean 8-spoke cone)
            if r > 15.6:
                put(img, x, y, p["BRASS_D"]); continue
            if r > 4.4:
                ang8 = math.atan2(dy, dx)
                spoke = math.cos(ang8 * 8.0) > 0.15
                if r > 11.5:
                    put(img, x, y, (p["BRASS"] if lit else p["BRASS_D"]) if spoke else p["BRASS_D"])
                elif r > 7.5:
                    put(img, x, y, p["BRASS"] if spoke else mul(p["BRASS_D"], 0.72))
                else:
                    put(img, x, y, (p["BRASS_L"] if lit else p["BRASS"]) if spoke
                        else mul(p["BRASS_D"], 0.8))
                continue
            # ---- hub
            if r > 3.2:
                put(img, x, y, p["GOLD"] if lit else p["BRASS"])
            elif abs(dx) + abs(dy) < 1.6:
                put(img, x, y, p["GOLD_HI"])
            else:
                put(img, x, y, p["GOLD_HI"] if lit else p["GOLD"])

    # ---- the ball, resting in a pocket (upper right) ----------------------
    ba = -math.pi / 3.1
    bx, by = cx + math.cos(ba) * 21.0, cy + math.sin(ba) * 21.0
    for y in range(H):
        for x in range(W):
            d = math.hypot(x + 0.5 - bx, y + 0.5 - by)
            if d < 1.4:
                put(img, x, y, p["WHITE"])
            elif d < 1.9:
                put(img, x, y, p["CREAM_D"])
            elif d < 2.4:
                put(img, x, y, p["INK"])
    return img


# ==========================================================================
# pachinko_board.png — 128x128
# ==========================================================================


def pachinko_board(p) -> Image.Image:
    W = H = 128
    img = new(W, H)

    # ---- dark board surface: flat bands + sparse vertical brush streaks
    vramp(img, 0, 0, W - 1, H - 1, [p["BG_PANEL"], p["INLAY"], p["INLAY"]])
    for x in range(6, W - 6, 5):
        for y in range(12, H - 10):
            if rnd(x, y, 21) > 0.86:
                put(img, x, y, mul(p["BG_PANEL"], 0.75))

    # ---- frame rails (outline rings only: the interior must stay visible)
    border(img, 0, 0, W - 1, H - 1, p["INK"])
    border(img, 1, 1, W - 2, H - 2, p["WOOD_D"])
    border(img, 2, 2, W - 3, H - 3, p["WOOD_M"])
    border(img, 3, 3, W - 4, H - 4, p["INK"])
    hline(img, 1, 1, W - 2, p["WOOD_M"])      # lit top-left of the rail
    vline(img, 1, 1, H - 2, p["WOOD_M"])
    hline(img, H - 2, 1, W - 2, p["INK"])     # shadowed bottom-right
    vline(img, W - 2, 1, H - 2, p["INK"])

    # ---- top launcher channel
    box(img, 5, 5, W - 6, 9, p["INLAY"])
    hline(img, 5, 5, W - 6, p["BRASS"])
    hline(img, 6, 5, W - 6, p["BRASS_D"])
    hline(img, 9, 5, W - 6, p["BRASS_D"])
    for x in range(10, W - 10, 18):
        put(img, x, 5, p["GOLD_HI"])
        vline(img, x, 5, 9, p["BRASS_D"])

    # ---- bottom ball gutter
    box(img, 6, H - 9, W - 7, H - 5, p["INLAY"])
    hline(img, H - 9, 6, W - 7, p["BRASS_D"])
    hline(img, H - 8, 6, W - 7, p["WOOD_D"])
    hline(img, H - 7, 6, W - 7, mul(p["WOOD_D"], 0.6))
    hline(img, H - 5, 6, W - 7, p["BRASS"])

    # ---- side brass guide rails
    for yy in range(12, H - 11):
        if yy % 8 < 5:
            put(img, 4, yy, p["BRASS"])
            put(img, W - 5, yy, p["BRASS"])

    # ---- dense pin field -------------------------------------------------
    def pin(x, y, rad, metal=True):
        body = p["STEEL_D"] if metal else p["WOOD_M"]
        hi = p["STEEL_L"] if metal else p["WOOD_HI"]
        lo = p["STEEL_SH"] if metal else p["WOOD_D"]
        for yy in range(int(y - rad) - 1, int(y + rad) + 2):
            for xx in range(int(x - rad) - 1, int(x + rad) + 2):
                if not (0 <= xx < W and 0 <= yy < H):
                    continue
                dx, dy = xx + 0.5 - x, yy + 0.5 - y
                d = math.hypot(dx, dy)
                if d > rad + 0.45:
                    continue
                if d > rad - 0.7:
                    put(img, xx, yy, lo if (dx + dy) > 0 else body)
                elif (dx + dy) < -0.9:
                    put(img, xx, yy, hi)
                else:
                    put(img, xx, yy, body)

    top, bot, step = 13, H - 11, 7
    row = 0
    y = top
    while y < bot:
        off = (step // 2) if (row % 2) else 0
        x = 9 + off
        while x < W - 7:
            big = (row % 5 == 2) and ((x - 9 - off) % 21 == 0)
            pin(x, y, 3.1 if big else 2.0, metal=not big)
            x += step
        y += step
        row += 1
    return img


# ==========================================================================
# cards — 20x28
# ==========================================================================


def card_front(p) -> Image.Image:
    """Blank cream card face.

    Left deliberately clean inside: BaseCasinoScreen draws the rank at (x+3,y+3)
    and the suit at (x+3,y+14), so the face carries only a thin border and the
    softest possible paper tone -- no inner rule, no grain that could read as
    dirt under the drawn pips.
    """
    W, H = 20, 28
    img = new(W, H)
    # paper: 1px lit edge (top-left light), flat field, slight shade at bottom
    box(img, 1, 1, W - 2, H - 2, p["CREAM"])
    hline(img, 1, 1, W - 2, p["WHITE"])
    vline(img, 1, 1, H - 2, p["WHITE"])
    hline(img, H - 3, 1, W - 2, p["CREAM_D"])
    vline(img, W - 3, 1, H - 2, p["CREAM_D"])
    # thin card edge
    border(img, 0, 0, W - 1, H - 1, p["CREAM_SH"])
    cut_rounded(img, 2)
    return img


def card_back(p) -> Image.Image:
    """Deep red card back: a tiled diamond lattice, lit from the top-left.

    Built as an actual diamond field rather than two diagonal line families --
    mod-4 diagonals collapse into stripes on even rows, which reads as a barcode
    instead of a lattice. Here each 4x4 cell carries a diamond whose edge is
    |lx-2| + |ly-2| == 2, so diamonds tile the field and touch at their vertices.
    """
    W, H = 20, 28
    img = new(W, H)
    # frame: card edge -> brass hairline -> deep red field
    box(img, 0, 0, W - 1, H - 1, p["CREAM_SH"])
    box(img, 1, 1, W - 2, H - 2, p["GOLD_SH"])
    box(img, 2, 2, W - 3, H - 3, p["RED_D"])

    # perfectly periodic lattice: colour depends only on the cell, so the
    # repeat can never drift or stripe
    for y in range(2, H - 2):
        for x in range(2, W - 2):
            lx = (x - 2) % 4
            ly = (y - 2) % 4
            dd = abs(lx - 1) + abs(ly - 1)      # 0 cell centre .. 2 diamond edge
            if dd == 2:
                put(img, x, y, p["RED_L"])       # diamond thread
            elif dd == 0:
                put(img, x, y, p["GOLD_SH"])     # pip at each diamond centre
            elif dd == 1:
                put(img, x, y, p["RED"])         # inner fill
            else:
                put(img, x, y, p["RED_D"])       # field between diamonds
    # directional light applied only at the card's own edges, so the repeat
    # stays even: lit top/left, shadowed bottom/right
    hline(img, 2, 2, W - 3, p["RED"])
    vline(img, 2, 2, H - 3, p["RED"])
    hline(img, H - 3, 2, W - 3, p["RED_D"])
    vline(img, W - 3, 2, H - 3, p["RED_D"])
    cut_rounded(img, 2)
    return img


# ==========================================================================
# block textures — 16x16, fully opaque
# ==========================================================================


def _wood_rim(p, w=16, h=16):
    img = new(w, h)
    vramp(img, 0, 0, w - 1, h - 1, [p["WOOD_HI"], p["WOOD_M"], p["WOOD_D"]])
    for y in range(h):
        for x in range(w):
            if rnd(x, y, 41) > 0.84:
                put(img, x, y, p["WOOD_D"])
    border(img, 0, 0, w - 1, h - 1, p["INK"])
    hline(img, 1, 1, w - 2, p["WOOD_HI"])
    vline(img, 1, 1, h - 2, mix(p["WOOD_HI"], p["WOOD_M"], 0.4))
    hline(img, h - 2, 1, w - 2, p["WOOD_D"])
    vline(img, w - 2, 1, h - 2, p["WOOD_D"])
    return img


def _felt_well(img, x0, y0, x1, y1, base, dark, light, seed):
    """Flat felt with a sparse 1px sparkle, then an inset shadow/lift.

    Kept flat on purpose: heavy speckle at 16x16 reads as TV static and buries
    the painted betting lines, so grain is sparse and one-directional (lighten
    only) and the dark tone is reserved for the inset edges.
    """
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(img, x, y, base)
    for y in range(y0 + 1, y1):
        for x in range(x0 + 1, x1):
            if rnd(x, y, seed) > 0.94:
                put(img, x, y, light)
    # inset: shadow on the top/left, lifted edge on the bottom/right
    hline(img, y0, x0, x1, dark)
    vline(img, x0, y0, y1, dark)
    hline(img, y1, x0, x1, light)
    vline(img, x1, y0, y1, light)


def _mini_wheel(img, p, cx, cy, R):
    """Legible 16x16 roulette wheel.

    At this size pocket-by-pocket detail turns to noise, so the wheel is built
    from four concentric bands only: ink edge, brass rim, a two-tone
    red/black field carrying a single green zero, and a gold hub.
    """
    for y in range(img.height):
        for x in range(img.width):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            r = math.hypot(dx, dy)
            if r > R:
                continue
            if r > R - 0.8:                        # ink edge
                put(img, x, y, p["INK"])
            elif r > R - 2.2:                      # brass rim, lit top-left
                put(img, x, y, p["BRASS"] if (dx + dy) < 0 else p["BRASS_D"])
            elif r > 1.35:                         # red / black field
                if (dx + dy) < -1.6:
                    put(img, x, y, p["POCKET_GREEN"])   # green zero patch
                elif (dx - dy) < 0:
                    put(img, x, y, p["RED"])
                else:
                    put(img, x, y, p["BLACK"])
            else:                                   # gold hub
                put(img, x, y, p["GOLD_HI"] if r < 0.75 else p["GOLD"])


def roulette_table(p) -> Image.Image:
    """Green felt top: wooden rim, painted betting marks, central wheel."""
    img = _wood_rim(p)
    _felt_well(img, 2, 2, 13, 13, p["GREEN_FELT"], p["GREEN_FELT_D"], p["GREEN_FELT_L"], 51)
    # painted betting marks in the corners (kept clear of the wheel)
    for (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        put(img, x, y, p["RED"])
    for (x, y) in ((4, 4), (11, 4), (4, 11), (11, 11)):
        put(img, x, y, p["BLACK"])
    _mini_wheel(img, p, 8.0, 8.0, 4.2)
    return img


def blackjack_table(p) -> Image.Image:
    """Green felt blackjack shoe: two upright cards with a visible pip, under a rim.

    The previous version drew two 3x4 outline frames on plain felt, which at 16x16
    read as nothing at all. The fix is to make the cards *solid* -- a cream face, a
    dark pip, and a shadowed edge -- so the silhouette does the work instead of a
    one-pixel outline that the felt texture swallows.
    """
    img = _wood_rim(p)
    _felt_well(img, 2, 2, 13, 13, p["GREEN_FELT"], p["GREEN_FELT_D"], p["GREEN_FELT_L"], 53)

    # Two card faces, side by side and slightly overlapping.
    def card(x0, y0):
        # drop shadow so the card lifts off the felt
        box(img, x0 + 1, y0 + 1, x0 + 5, y0 + 7, p["GREEN_FELT_D"])
        # cream face with a lit left edge and shaded right edge
        box(img, x0, y0, x0 + 4, y0 + 6, p["CREAM"])
        vline(img, x0, y0, y0 + 6, p["CREAM_D"])
        vline(img, x0 + 4, y0, y0 + 6, p["CREAM_SH"])
        hline(img, y0, x0, x0 + 4, p["WHITE"])

    card(4, 4)
    card(8, 5)

    # A black pip on the left card and a red one on the right: instantly reads as
    # "two cards, two suits" rather than two blank rectangles.
    box(img, 5, 6, 7, 8, p["BLACK"])
    put(img, 6, 7, p["CREAM"])
    box(img, 9, 7, 11, 9, p["RED"])
    put(img, 10, 8, p["CREAM_SH"])

    # Thin betting arc along the bottom, kept below the cards.
    for x in range(3, 13):
        a = x + 0.5 - 8.0
        y = int(round(11.2 + (a * a) / 40.0))
        if y <= 12:
            put(img, x, y, p["GREEN_FELT_L"])
    return img


def baccarat_table(p) -> Image.Image:
    """Blue felt baccarat layout: the three-box PLAYER / BANKER / TIE grid.

    The old version was a faint 1px grid that vanished into the felt texture. Baccarat
    is recognisable by its three labelled boxes, so each box is now a filled panel with
    a coloured header bar, and the third box is narrower -- the classic layout.
    """
    img = _wood_rim(p)
    _felt_well(img, 2, 2, 13, 13, p["BLUE_FELT"], p["BLUE_FELT_D"], p["BLUE_FELT_L"], 55)

    def bet_box(x0, y0, x1, y1, header):
        # recessed well with a coloured header strip
        box(img, x0, y0, x1, y1, p["BLUE_FELT_D"])
        hline(img, y0, x0, x1, header)
        hline(img, y0 + 1, x0, x1, header)
        # lit top edge / shadowed bottom edge around the well
        hline(img, y1, x0, x1, mul(p["BLUE_FELT_D"], 0.7))
        put(img, x0, y1, p["BLUE_FELT_L"])

    # PLAYER (cream header) and BANKER (red header) share the top row.
    bet_box(3, 3, 7, 8, p["CREAM_D"])
    bet_box(9, 3, 12, 8, p["RED"])
    # TIE strip across the bottom, gold header.
    bet_box(3, 10, 12, 12, p["GOLD_SH"])

    # Card-like pips inside PLAYER and BANKER so the boxes are not empty.
    put(img, 5, 6, p["CREAM"]); put(img, 6, 6, p["CREAM"])
    put(img, 10, 6, p["RED_HI"]); put(img, 11, 6, p["RED_HI"])
    return img


def poker_machine(p) -> Image.Image:
    """Dark metal cabinet front: bezel + screen slit, coin slot, payout tray."""
    img = new(16, 16)
    vramp(img, 0, 0, 15, 15, [p["STEEL"], p["STEEL_D"], p["STEEL_SH"]])
    border(img, 0, 0, 15, 15, p["INK"])
    hline(img, 1, 1, 14, p["STEEL_L"])
    vline(img, 1, 1, 14, p["STEEL"])
    hline(img, 14, 1, 14, p["STEEL_SH"])
    vline(img, 14, 1, 14, p["STEEL_SH"])
    # bezel + screen slit (rows 2..7)
    border(img, 2, 2, 13, 7, p["BRASS_D"])
    hline(img, 2, 3, 12, p["BRASS"])
    box(img, 3, 3, 12, 6, p["BG_DEEP"])
    for x in range(4, 12, 3):
        vline(img, x, 3, 6, p["GOLD_SH"])
    hline(img, 4, 4, 11, p["POCKET_GREEN"])
    # brass coin slot (rows 9..10)
    hline(img, 8, 2, 13, p["STEEL_SH"])
    hline(img, 9, 5, 10, p["BRASS"])
    box(img, 5, 10, 10, 10, p["INK"])
    put(img, 6, 10, p["GOLD_HI"])
    # payout tray (rows 11..14)
    hline(img, 11, 2, 13, p["INK"])
    box(img, 3, 12, 12, 13, p["BG_DEEP"])
    hline(img, 12, 3, 12, p["STEEL_SH"])
    hline(img, 13, 3, 12, p["BRASS_D"])
    return img


def pachinko_cabinet(p) -> Image.Image:
    """Red/pink arcade cabinet face with a brass porthole."""
    img = new(16, 16)
    vramp(img, 0, 0, 15, 15, [p["RED_L"], p["RED"], p["RED_D"]])
    border(img, 0, 0, 15, 15, p["INK"])
    hline(img, 1, 1, 14, p["RED_HI"])
    vline(img, 1, 1, 14, p["RED_L"])
    hline(img, 14, 1, 14, p["RED_D"])
    vline(img, 14, 1, 14, p["RED_D"])
    # brass porthole, kept fully inside the border (rows 1..11)
    cx, cy, r = 8.0, 5.8, 3.8
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            if r < d <= r + 1.5:
                put(img, x, y, p["BRASS"] if (dx + dy) < 0 else p["BRASS_D"])
            elif r - 0.6 < d <= r:
                put(img, x, y, p["INK"])
            elif d <= r - 0.6:
                put(img, x, y, p["BG_DEEP"])
    # pins seen through the porthole
    for (px_, py_) in ((6, 4), (9, 4), (7, 6), (10, 6), (5, 7), (8, 7)):
        put(img, px_, py_, p["STEEL_L"])
        put(img, px_ + 1, py_ + 1, p["STEEL_SH"])
    # brass name plate
    hline(img, 11, 4, 11, p["BRASS_D"])
    put(img, 5, 11, p["GOLD_SH"]); put(img, 8, 11, p["GOLD_SH"]); put(img, 10, 11, p["GOLD_SH"])
    # control strip: lever + coin tray
    box(img, 3, 12, 6, 14, p["BG_DEEP"])
    box(img, 9, 12, 12, 14, p["BG_DEEP"])
    hline(img, 12, 3, 6, p["INK"])
    hline(img, 12, 9, 12, p["INK"])
    put(img, 4, 13, p["GOLD"])
    vline(img, 10, 12, 14, p["BRASS_D"])
    hline(img, 14, 3, 12, p["RED_D"])
    return img


def texas_holdem_floor(p) -> Image.Image:
    """Poker-table felt: a clean oval in the middle and FIVE community cards.

    Earlier this was felt speckled with a dotted frame and a pair of two-pixel marks,
    which read as generic noise. Poker is identified by the board, so the texture now
    draws the five card slots of the flop/turn/river across the centre -- the one
    feature that says "poker" and nothing else.
    """
    img = new(16, 16)
    vramp(img, 0, 0, 15, 15, [p["LEATHER_L"], p["LEATHER"], p["LEATHER_D"]])
    border(img, 0, 0, 15, 15, p["INK"])
    hline(img, 1, 1, 14, p["LEATHER_L"])
    vline(img, 1, 1, 14, mix(p["LEATHER_L"], p["LEATHER"], 0.5))
    hline(img, 14, 1, 14, p["LEATHER_D"])

    # Rails: a brass stud at each corner of the leather surround.
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        put(img, x, y, p["GOLD_SH"])

    _felt_well(img, 3, 3, 12, 12, p["GREEN_FELT"], p["GREEN_FELT_D"], p["GREEN_FELT_L"], 59)

    # The table oval, drawn as a clean 1px ring.
    for y in range(16):
        for x in range(16):
            ex = (x + 0.5 - 8.0) / 4.6
            ey = (y + 0.5 - 8.0) / 3.4
            d = math.hypot(ex, ey)
            if 0.90 < d < 1.04:
                put(img, x, y, p["GREEN_FELT_L"])

    # Five community cards in a row across the middle of the oval.
    # Each is a 2x3 cream slot with a shaded bottom edge so it reads as a card.
    for i in range(5):
        cx = 4 + i * 2
        box(img, cx, 7, cx + 1, 9, p["CREAM"])
        hline(img, 9, cx, cx + 1, p["CREAM_SH"])
        put(img, cx, 7, p["WHITE"])
    return img


def pinball_machine(p) -> Image.Image:
    """Pinball cabinet: backglass on top, scoring window, and two flippers at the bottom.

    At 16x16 a pinball machine is recognised by exactly two things: a lit backglass at the
    top and the two angled flippers at the bottom. Everything between them is playfield, so
    the middle is left as dark recessed field with a couple of bumper studs rather than
    trying to draw the whole table.
    """
    img = new(16, 16)
    # Cabinet body: deep lacquer with a lit top edge.
    vramp(img, 0, 0, 15, 15, [p["BG_PANEL_HI"], p["BG_PANEL"], p["BG_DEEP"]])
    border(img, 0, 0, 15, 15, p["INK"])
    hline(img, 1, 1, 14, p["WOOD_L"])
    vline(img, 1, 1, 14, p["WOOD_M"])
    hline(img, 14, 1, 14, mul(p["BG_DEEP"], 0.7))
    vline(img, 14, 1, 14, mul(p["BG_DEEP"], 0.7))

    # ---- backglass: a bright lit panel across rows 2..5 --------------------
    box(img, 2, 2, 13, 5, p["BRASS_D"])
    hline(img, 2, 3, 12, p["BRASS"])
    box(img, 3, 3, 12, 4, p["GOLD_SH"])
    # a warm glow inside the glass, brightest top-left
    hline(img, 3, 3, 12, p["GOLD"])
    put(img, 4, 3, p["GOLD_HI"])
    put(img, 5, 3, p["GOLD_HI"])
    # score digits hinted as three dark slots
    for x in (5, 8, 11):
        put(img, x, 4, p["INK"])

    # ---- playfield: recessed dark field with bumper studs ------------------
    box(img, 3, 6, 12, 11, p["INLAY"])
    hline(img, 6, 3, 12, p["BG_DEEP"])
    for (bx, by) in ((6, 8), (9, 8), (7, 10), (10, 10)):
        put(img, bx, by, p["BRASS_L"])
        put(img, bx + 1, by, p["BRASS_D"])

    # ---- flippers: two angled paddles at the bottom ------------------------
    # Left flipper points up-right, right flipper points up-left.
    put(img, 4, 12, p["STEEL_L"]); put(img, 5, 12, p["STEEL"])
    put(img, 6, 11, p["STEEL"]);   put(img, 7, 11, p["STEEL_SH"])
    put(img, 11, 12, p["STEEL_L"]); put(img, 10, 12, p["STEEL"])
    put(img, 9, 11, p["STEEL"]);    put(img, 8, 11, p["STEEL_SH"])

    # Center drain gap between the flippers, and the plunger lane on the right.
    box(img, 7, 13, 8, 13, p["BG_DEEP"])
    vline(img, 13, 7, 13, p["BRASS_D"])
    put(img, 13, 12, p["GOLD"])
    return img


def exchange_block(p) -> Image.Image:
    """The Exchange: the house counter where items become money and back.

    Dark wood cabinet with a brass counter rail, and a bold two-way swap glyph
    painted straight onto the cabinet. An earlier version wrapped the glyph in a
    brass medallion, but at 16x16 the medallion's inner field is only ~4 px wide
    and swallowed the arrows; giving the glyph the full cabinet width is what
    actually reads as "exchange" at a glance.
    """
    img = new(16, 16)
    # cabinet body: vertical planks, lit from the top-left
    vramp(img, 0, 0, 15, 15, [p["WOOD_M"], p["WOOD_D"], mul(p["WOOD_D"], 0.85)])
    for x in (5, 10):
        vline(img, x, 0, 15, p["INK"])
        vline(img, x - 1, 0, 15, p["WOOD_M"])
    for x in (2, 7, 13):
        for y in range(16):
            if rnd(x, y, 83) > 0.55:
                put(img, x, y, p["WOOD_L"])

    # brass counter rail across the top
    box(img, 0, 1, 15, 4, p["INK"])
    hline(img, 2, 0, 15, p["GOLD"])
    hline(img, 3, 0, 15, p["BRASS"])
    hline(img, 4, 0, 15, p["BRASS_D"])
    put(img, 1, 2, p["GOLD_HI"])
    put(img, 13, 2, p["GOLD_HI"])

    # ---- swap glyph, full cabinet width ----------------------------------
    # upper arrow points right (money out), lower points left (items in)
    for x in range(3, 12):
        put(img, x, 7, p["GOLD"])
    put(img, 12, 6, p["GOLD"]); put(img, 12, 8, p["GOLD"])     # right head
    for x in range(4, 13):
        put(img, x, 11, p["BRASS_L"])
    put(img, 3, 10, p["BRASS_L"]); put(img, 3, 12, p["BRASS_L"])  # left head

    # coin between the arrows: brass disc with a lit top-left
    cx, cy, R = 8.0, 9.0, 1.6
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            if d > R:
                continue
            if d > R - 0.8:
                put(img, x, y, p["GOLD"] if (dx + dy) < 0 else p["BRASS_D"])
            else:
                put(img, x, y, p["GOLD_HI"])
    put(img, 7, 8, p["WHITE"])

    border(img, 0, 0, 15, 15, p["INK"])
    hline(img, 1, 1, 14, mul(p["WOOD_L"], 1.05))
    return img


def slot_machine(p) -> Image.Image:
    """One-armed bandit cabinet: brass marquee, three reel windows, spin button.

    Recognised at 16x16 by exactly three things: a lit marquee across the top,
    three narrow vertical reel windows down the centre, and a big spin button at
    the bottom. Everything else is dark steel cabinet body.
    """
    img = new(16, 16)
    # Cabinet body: dark steel, lit top-left.
    vramp(img, 0, 0, 15, 15, [p["STEEL"], p["STEEL_D"], p["STEEL_SH"]])
    border(img, 0, 0, 15, 15, p["INK"])
    hline(img, 1, 1, 14, p["STEEL_L"])
    vline(img, 1, 1, 14, p["STEEL"])
    hline(img, 14, 1, 14, p["STEEL_SH"])
    vline(img, 14, 1, 14, p["STEEL_SH"])

    # ---- marquee: lit sign across the top (rows 1..3) -----------------------
    box(img, 1, 1, 14, 3, p["BRASS_D"])
    hline(img, 1, 2, 13, p["BRASS_L"])
    hline(img, 2, 2, 13, p["GOLD"])
    hline(img, 3, 2, 13, p["BRASS_D"])
    # three bright dots hint "SLOTS" / jackpot lights
    put(img, 4, 2, p["GOLD_HI"]); put(img, 8, 2, p["GOLD_HI"]); put(img, 12, 2, p["GOLD_HI"])

    # ---- three reel windows: narrow vertical strips (rows 4..10) ------------
    # Each reel is 4px wide with a 1px brass bezel; BG_DEEP field inside.
    reel_x = (3, 6, 9)          # left edges of the three reels
    for rx in reel_x:
        # bezel
        vline(img, rx, 4, 10, p["BRASS_D"])
        vline(img, rx + 3, 4, 10, p["BRASS"])
        hline(img, 4, rx, rx + 3, p["BRASS_D"])
        hline(img, 10, rx, rx + 3, p["BRASS"])
        box(img, rx + 1, 5, rx + 2, 9, p["BG_DEEP"])
        # a hint of symbol stripe inside each reel (two bright px)
        put(img, rx + 1, 6, p["GOLD_SH"]); put(img, rx + 2, 7, p["GOLD_SH"])

    # ---- coin slot above the button (row 11) --------------------------------
    hline(img, 11, 5, 10, p["STEEL_SH"])
    box(img, 6, 12, 9, 12, p["INK"])
    put(img, 7, 12, p["GOLD_HI"])

    # ---- spin button: big brass disc at the bottom centre (rows 13..14) -----
    disc(img, 8.0, 13.5, 1.9, p["BRASS"], edge_hi=p["BRASS_L"], edge_lo=p["BRASS_D"])
    put(img, 8, 13, p["GOLD_HI"])

    # ---- payout tray: thin strip along the very bottom (row 15) -------------
    hline(img, 15, 2, 13, p["BRASS_D"])
    box(img, 4, 14, 11, 15, p["BG_DEEP"])
    return img


def slot_machine_side(p) -> Image.Image:
    """Cabinet side: dark steel with a brass band and a hint of the coin mechanism."""
    img = new(16, 16)
    vramp(img, 0, 0, 15, 15, [p["STEEL"], p["STEEL_D"], p["STEEL_SH"]])
    border(img, 0, 0, 15, 15, p["INK"])
    hline(img, 1, 1, 14, p["STEEL_L"])
    vline(img, 1, 1, 14, p["STEEL"])
    hline(img, 14, 1, 14, p["STEEL_SH"])
    vline(img, 14, 1, 14, p["STEEL_SH"])
    # brass trim band around the mid-section
    box(img, 2, 6, 13, 9, p["INK"])
    hline(img, 7, 2, 13, p["BRASS"])
    hline(img, 8, 2, 13, p["BRASS_L"])
    hline(img, 9, 2, 13, p["BRASS_D"])
    # coin slot + mechanism hint
    box(img, 6, 11, 9, 12, p["INK"])
    put(img, 7, 11, p["GOLD_HI"])
    return img


def slot_machine_top(p) -> Image.Image:
    """Cabinet top: brass lacquer with corner studs and a lit top edge."""
    img = new(16, 16)
    for y in range(16):
        for x in range(16):
            n = rnd(x, y, 73)
            put(img, x, y, p["BRASS"] if n > 0.55 else p["BRASS_D"])
    border(img, 0, 0, 15, 15, p["INK"])
    hline(img, 0, 1, 14, p["BRASS_L"])
    vline(img, 0, 1, 14, p["BRASS"])
    hline(img, 15, 1, 14, p["BRASS_D"])
    vline(img, 15, 1, 14, p["BRASS_D"])
    # corner studs
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        put(img, x, y, p["GOLD_SH"])
    # centre keyhole / lock
    box(img, 7, 7, 8, 9, p["INK"])
    put(img, 7, 7, p["GOLD"])
    return img


def casino_body_side(p) -> Image.Image:
    """Dark wood planks with a brass trim band, shared by the cabinets."""
    img = new(16, 16)
    vramp(img, 0, 0, 15, 15, [p["WOOD_M"], p["WOOD_D"], mul(p["WOOD_D"], 0.8)])
    for x in (2, 3, 7, 8, 13):
        for y in range(16):
            if rnd(x, y, 67) > 0.5:
                put(img, x, y, p["WOOD_L"])
    for seam in (5, 10):
        vline(img, seam, 0, 15, p["INK"])
        vline(img, seam - 1, 0, 15, p["WOOD_M"])
        vline(img, seam + 1, 0, 15, mul(p["WOOD_D"], 0.55))
    # brass trim band
    box(img, 0, 6, 15, 9, p["INK"])
    hline(img, 7, 0, 15, p["GOLD"])
    hline(img, 8, 0, 15, p["BRASS"])
    hline(img, 9, 0, 15, p["BRASS_D"])
    for x in range(2, 15, 6):
        put(img, x, 7, p["GOLD_HI"])
    border(img, 0, 0, 15, 15, p["INK"])
    hline(img, 1, 1, 14, p["WOOD_L"])
    return img


def casino_body_top(p) -> Image.Image:
    """Dark lacquered top surface: brass inlay frame + corner studs."""
    img = new(16, 16)
    for y in range(16):
        for x in range(16):
            n = rnd(x, y, 71)
            put(img, x, y, p["BG_PANEL_HI"] if n > 0.90 else p["BG_PANEL"])
    # diagonal lacquer sheen (top-left -> bottom-right, matching the light)
    for y in range(16):
        for x in range(16):
            d = abs((x - y) - 1)
            if d == 0:
                put(img, x, y, mix(p["BG_PANEL"], p["BG_PANEL_HI"], 0.85))
            elif d == 1:
                put(img, x, y, mix(p["BG_PANEL"], p["BG_PANEL_HI"], 0.30))
    border(img, 0, 0, 15, 15, p["INK"])
    border(img, 1, 1, 14, 14, p["WOOD_D"])
    border(img, 2, 2, 13, 13, p["BRASS_D"])
    # lit top-left / shadowed bottom-right of the inlay
    hline(img, 2, 3, 12, p["BRASS"])
    vline(img, 2, 3, 12, p["BRASS"])
    hline(img, 13, 3, 12, mul(p["BRASS_D"], 0.7))
    vline(img, 13, 3, 12, mul(p["BRASS_D"], 0.7))
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        put(img, x, y, p["BRASS_L"])
    return img


def craps_table(p) -> Image.Image:
    """Craps layout: green felt with pass/don't pass lines, field, and dice."""
    img = _wood_rim(p)
    _felt_well(img, 2, 2, 13, 13, p["GREEN_FELT"], p["GREEN_FELT_D"], p["GREEN_FELT_L"], 57)
    # Pass line (bottom) and Don't Pass (top)
    hline(img, 3, 3, 12, p["CREAM"])
    hline(img, 12, 3, 12, p["CREAM"])
    # Field (center strip)
    box(img, 6, 6, 9, 9, p["RED_D"])
    # Two dice near center
    for (dx, dy) in ((5, 5), (10, 10)):
        box(img, dx, dy, dx + 2, dy + 2, p["CREAM"])
        put(img, dx + 1, dy + 1, p["RED"])
        put(img, dx + 2, dy + 2, p["RED"])
    return img


def wheel_of_fortune(p) -> Image.Image:
    """Wheel of Fortune: large spinning wheel with colored segments and pointer."""
    img = new(16, 16)
    # Wheel body
    vramp(img, 0, 0, 15, 15, [p["WOOD_HI"], p["WOOD_M"], p["WOOD_D"]])
    border(img, 0, 0, 15, 15, p["INK"])
    hline(img, 1, 1, 14, p["WOOD_HI"])
    vline(img, 1, 1, 14, mix(p["WOOD_HI"], p["WOOD_M"], 0.4))
    hline(img, 14, 1, 14, p["WOOD_D"])
    vline(img, 14, 1, 14, p["WOOD_D"])
    # Eight segments around center
    cx, cy = 8.0, 8.0
    colors = [p["RED"], p["BLACK"], p["GOLD"], p["BRASS"], p["GREEN_FELT"], p["WOOD_HI"], p["STEEL_L"], p["CREAM"]]
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            r = math.hypot(dx, dy)
            if r > 5.5 or r < 2.5:
                continue
            ang = math.atan2(dy, dx)
            segment = int((ang + math.pi) / (2 * math.pi / 8)) % 8
            lit = (dx + dy) < 0
            put(img, x, y, colors[segment] if not lit else mul(colors[segment], 1.2))
    # Pointer at top
    box(img, 7, 0, 8, 2, p["BRASS"])
    put(img, 7, 0, p["GOLD_HI"])
    put(img, 8, 0, p["GOLD_HI"])
    return img


def keno_board(p) -> Image.Image:
    """Keno board: 8x10 grid of numbers with selected highlights."""
    img = new(16, 16)
    vramp(img, 0, 0, 15, 15, [p["WOOD_M"], p["WOOD_D"], mul(p["WOOD_D"], 0.85)])
    border(img, 0, 0, 15, 15, p["INK"])
    # Draw a 5x4 grid of number squares
    for row in range(4):
        for col in range(5):
            x0 = 2 + col * 2
            y0 = 3 + row * 2
            lit = (col + row) % 3 == 0
            fill_c = p["BRASS"] if lit else p["BG_PANEL"]
            box(img, x0, y0, x0 + 1, y0 + 1, fill_c)
            gfill_c = p["GOLD_HI"] if lit else p["CREAM"]
            put(img, x0, y0, gfill_c)
    # Keno balls dropping
    put(img, 4, 12, p["WHITE"])
    put(img, 11, 12, p["WHITE"])
    return img


# ==========================================================================
# slot_spin_button.png / slot_spin_button_hover.png — 64x20
# ==========================================================================


def slot_spin_button(p) -> Image.Image:
    """64x20 brass spin button. "SPIN" hinted as two bright centre px."""
    img = new(64, 20)
    w, h = 64, 20
    fill = [p["BRASS_D"], p["BRASS"], p["GOLD_SH"]]
    edge = p["GOLD_SH"]
    top_hi = p["GOLD_HI"]
    bands(img, 1, 2, w - 2, 17, [
        (6, fill[0]),
        (5, fill[1]),
        (5, fill[2]),
    ])
    border(img, 0, 0, w - 1, h - 1, p["INK"])
    hline(img, 1, 1, w - 2, top_hi)
    vline(img, 1, 2, 17, edge)
    vline(img, w - 2, 2, 17, mul(edge, 0.6))
    hline(img, 18, 1, w - 2, mix(fill[2], p["INK"], 0.75))
    # "SPIN" hint: two bright pixels at centre
    cx, cy = w // 2, h // 2
    put(img, cx - 1, cy, p["GOLD_HI"])
    put(img, cx + 1, cy, p["WHITE"])
    cut_rounded(img, 2)
    return img


def slot_spin_button_hover(p) -> Image.Image:
    """64x20 brighter brass spin button, lit variant of slot_spin_button."""
    img = new(64, 20)
    w, h = 64, 20
    fill = [p["BRASS"], p["GOLD_SH"], p["GOLD"]]
    edge = p["GOLD"]
    top_hi = p["GOLD_HI"]
    bands(img, 1, 2, w - 2, 17, [
        (6, fill[0]),
        (5, fill[1]),
        (5, fill[2]),
    ])
    border(img, 0, 0, w - 1, h - 1, p["INK"])
    hline(img, 1, 1, w - 2, top_hi)
    vline(img, 1, 2, 17, edge)
    vline(img, w - 2, 2, 17, mul(edge, 0.8))
    hline(img, 18, 1, w - 2, mix(fill[2], p["INK"], 0.6))
    cx, cy = w // 2, h // 2
    put(img, cx - 1, cy, p["GOLD_HI"])
    put(img, cx, cy, p["WHITE"])
    put(img, cx + 1, cy, p["GOLD_HI"])
    cut_rounded(img, 2)
    return img


# ==========================================================================
# slot_reel_strip.png — 16x64
# ==========================================================================


def slot_reel_strip(p) -> Image.Image:
    """16x64 dark reel column: BG_DEEP field, INK border, three symbol slots
    separated by brass divider lines with one hint pixel pair per slot."""
    img = new(16, 64)
    w, h = 16, 64
    box(img, 0, 0, w - 1, h - 1, p["BG_DEEP"])
    border(img, 0, 0, w - 1, h - 1, p["INK"])
    # slot dividers between the three symbol windows
    hline(img, 21, 1, w - 2, p["BRASS"])
    hline(img, 42, 1, w - 2, p["BRASS"])
    # one hint pixel pair per slot, centred
    for sy in (10, 31, 52):
        put(img, w // 2 - 1, sy, p["GOLD_SH"])
        put(img, w // 2 + 1, sy, p["BRASS_L"])
    return img


# ==========================================================================
# registry / driver
# ==========================================================================

GUI_ASSETS = [
    ("casino_frame.png",      lambda p: casino_frame(p)),
    ("header_bar.png",        lambda p: header_bar(p)),
    ("button.png",            lambda p: button(p, False)),
    ("button_hover.png",      lambda p: button(p, True)),
    ("balance_bar.png",       lambda p: balance_bar(p)),
    ("chip.png",              lambda p: chip(p)),
    ("roulette_wheel.png",    lambda p: roulette_wheel(p)),
    ("pachinko_board.png",    lambda p: pachinko_board(p)),
    ("cards/card_front.png",  lambda p: card_front(p)),
    ("cards/card_back.png",   lambda p: card_back(p)),
]


def slot_reel_window(p) -> Image.Image:
    """Dark glass reel backdrop with a brass frame, 96x96."""
    img = new(96, 96)
    box(img, 0, 0, 95, 95, p["BG_DEEP"])
    border(img, 0, 0, 95, 95, p["INK"])
    # inner brass bezel
    box(img, 3, 3, 92, 92, p["BRASS_D"])
    box(img, 5, 5, 90, 90, p["INLAY"])
    # three reel lanes, faint vertical dividers
    for x in (32, 64):
        vline(img, x, 6, 89, p["BG_PANEL"])
    # marquee hint at top
    hline(img, 6, 10, 85, p["BRASS"])
    hline(img, 7, 10, 85, p["BRASS_D"])
    return img


def slot_symbol(p, kind) -> Image.Image:
    """16x16 symbol sprite: cherry / lemon / bell / bar / seven."""
    img = new(16, 16)
    cx = 8.0
    if kind == "cherry":
        # two red cherries on green stems
        disc(img, cx - 2.5, 7.0, 2.2, p["RED"], edge_hi=p["RED_L"], edge_lo=p["RED_D"])
        disc(img, cx + 2.5, 7.0, 2.2, p["RED"], edge_hi=p["RED_L"], edge_lo=p["RED_D"])
        vline(img, int(cx), 2, 6, p["GREEN_DOT"])
        put(img, int(cx), 2, p["GREEN_FELT_L"])
    elif kind == "lemon":
        disc(img, cx, 8.0, 3.0, p["GOLD"], edge_hi=p["GOLD_HI"], edge_lo=p["GOLD_SH"])
        vline(img, int(cx), 4, 5, p["GREEN_DOT"])
        put(img, int(cx), 4, p["GREEN_FELT_L"])
    elif kind == "bell":
        disc(img, cx, 9.0, 2.6, p["GOLD"], edge_hi=p["GOLD_HI"], edge_lo=p["GOLD_SH"])
        box(img, int(cx) - 1, 12, int(cx) + 1, 13, p["GOLD_SH"])
        put(img, int(cx), 13, p["GOLD"])
        hline(img, 11, int(cx) - 1, int(cx) + 1, p["GOLD_SH"])
    elif kind == "bar":
        for yy in (6, 8, 10):
            hline(img, yy, int(cx) - 3, int(cx) + 3, p["BRASS"])
        vline(img, int(cx) - 3, 6, 10, p["BRASS_D"])
        vline(img, int(cx) + 3, 6, 10, p["BRASS_D"])
    elif kind == "seven":
        hline(img, 6, int(cx) - 3, int(cx), p["RED"])
        vline(img, int(cx), 6, 11, p["RED"])
        hline(img, 11, int(cx), int(cx) + 3, p["RED_L"])
        put(img, int(cx) - 1, 6, p["RED_D"])
        put(img, int(cx) + 1, 11, p["RED_L"])
    return img


GUI_ASSETS = [
    ("casino_frame.png",      lambda p: casino_frame(p)),
    ("header_bar.png",        lambda p: header_bar(p)),
    ("button.png",            lambda p: button(p, False)),
    ("button_hover.png",      lambda p: button(p, True)),
    ("balance_bar.png",       lambda p: balance_bar(p)),
    ("chip.png",              lambda p: chip(p)),
    ("roulette_wheel.png",    lambda p: roulette_wheel(p)),
    ("pachinko_board.png",    lambda p: pachinko_board(p)),
    ("cards/card_front.png",  lambda p: card_front(p)),
    ("cards/card_back.png",   lambda p: card_back(p)),
    ("slot_reel_window.png",  lambda p: slot_reel_window(p)),
    ("slot_symbol_cherry.png", lambda p: slot_symbol(p, "cherry")),
    ("slot_symbol_lemon.png",  lambda p: slot_symbol(p, "lemon")),
    ("slot_symbol_bell.png",   lambda p: slot_symbol(p, "bell")),
    ("slot_symbol_bar.png",    lambda p: slot_symbol(p, "bar")),
    ("slot_symbol_seven.png",  lambda p: slot_symbol(p, "seven")),
    ("slot_spin_button.png",       lambda p: slot_spin_button(p)),
    ("slot_spin_button_hover.png", lambda p: slot_spin_button_hover(p)),
    ("slot_reel_strip.png",        lambda p: slot_reel_strip(p)),
]

BLOCK_ASSETS = [
    ("roulette_table.png",    roulette_table),
    ("blackjack_table.png",   blackjack_table),
    ("baccarat_table.png",    baccarat_table),
    ("poker_machine.png",     poker_machine),
    ("pachinko_cabinet.png",  pachinko_cabinet),
    ("texas_holdem_floor.png", texas_holdem_floor),
    ("exchange.png",          exchange_block),
    ("pinball_machine.png",   pinball_machine),
    ("slot_machine.png",      slot_machine),
    ("craps_table.png",       craps_table),
    ("wheel_of_fortune.png",  wheel_of_fortune),
    ("keno_board.png",        keno_board),
    ("slot_machine_side.png", slot_machine_side),
    ("slot_machine_top.png",  slot_machine_top),
    ("casino_body_side.png",  casino_body_side),
    ("casino_body_top.png",   casino_body_top),
]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Every source tree that ships these textures. The five projects are separate Gradle
# builds with their own resource directories, so one generated set has to be written
# into all of them. Previously only mc26.3 was written and the four 1.20.1 trees kept
# whatever art they were first scaffolded with -- which is how they silently drifted
# out of sync (identical filenames, different pixels). Add a tree here, not a copy step.
RESOURCE_ROOTS = [
    f"{ROOT}/mc26.3/src/main/resources",
    f"{ROOT}/mc1.20.1/src/main/resources",
    f"{ROOT}/mc1.20.1-forge/src/main/resources",
    f"{ROOT}/mc1.20.1-neoforge/src/main/resources",
    f"{ROOT}/mc1.20.1-arch/common/src/main/resources",
]

# The mod's resource namespace.
NAMESPACES = ["casino"]

def trees() -> list:
    """(gui_light, gui_dark, block) output triples for every tree x namespace."""
    out = []
    for base in RESOURCE_ROOTS:
        for ns in NAMESPACES:
            out.append((
                f"{base}/assets/{ns}/textures/gui",
                f"{base}/resourcepacks/dark_mode/assets/{ns}/textures/gui",
                f"{base}/assets/{ns}/textures/block",
            ))
    return out


WRITTEN: list = []


def save(img: Image.Image, path: str, theme: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    assert img.mode == "RGBA", f"{path}: mode {img.mode}"
    # Upscale by SCALE using nearest-neighbor so pixel art stays crisp:
    # every source pixel becomes an SCALE x SCALE block of identical colour.
    if SCALE > 1:
        img = img.resize((img.width * SCALE, img.height * SCALE), Image.NEAREST)
    # hard guarantee: binary alpha + every colour on the palette
    allowed = {tuple(v[:3]) + (255,) for v in (LIGHT if theme == "light" else DARK).values()}
    px = img.load()
    bad_alpha = bad_colour = 0
    for y in range(img.height):
        for x in range(img.width):
            c = px[x, y]
            if c[3] not in (0, 255):
                bad_alpha += 1
            elif c[3] == 255 and c not in allowed:
                bad_colour += 1
    if bad_alpha or bad_colour:
        raise AssertionError(
            f"{path}: {bad_alpha} non-binary alpha px, {bad_colour} off-palette px")
    img.save(path, "PNG", optimize=True)
    size = os.path.getsize(path)
    assert size < 64 * 1024, f"{path}: {size}B is too large"
    WRITTEN.append((path, img.width, img.height, size, theme))
    print(f"  {img.width:>3}x{img.height:<3} {size:>6}B  [{theme}]  {path.split('textures/')[-1]}")


def contact_sheet(images, zoom, path, title=""):
    pad = 10
    cols = 4
    cw = max(im.width for _, im in images) * zoom
    ch = max(im.height for _, im in images) * zoom
    rows = (len(images) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * (cw + pad) + pad, rows * (ch + pad) + pad), (26, 28, 32, 255))
    for i, (name, im) in enumerate(images):
        big = im.resize((im.width * zoom, im.height * zoom), Image.NEAREST)
        cx = pad + (i % cols) * (cw + pad)
        cy = pad + (i // cols) * (ch + pad)
        for yy in range(0, big.height, 8):
            for xx in range(0, big.width, 8):
                c = (58, 62, 68, 255) if ((xx // 8 + yy // 8) % 2) else (42, 45, 50, 255)
                for dy in range(min(8, big.height - yy)):
                    for dx in range(min(8, big.width - xx)):
                        sheet.putpixel((cx + xx + dx, cy + yy + dy), c)
        sheet.alpha_composite(big, (cx, cy))
    sheet.save(path)
    print(f"  sheet -> {path}  ({title})")


def verify() -> int:
    """Independent on-disk check: dimensions, mode, binary alpha, palette."""
    # All dimensions scaled by SCALE (2x) for crisp HD pixel art.
    expected_gui = {
        "casino_frame.png": (256 * SCALE, 256 * SCALE),
        "header_bar.png": (256 * SCALE, 32 * SCALE),
        "button.png": (64 * SCALE, 20 * SCALE),
        "button_hover.png": (64 * SCALE, 20 * SCALE),
        "balance_bar.png": (256 * SCALE, 16 * SCALE),
        "chip.png": (16 * SCALE, 16 * SCALE),
        "roulette_wheel.png": (64 * SCALE, 64 * SCALE),
        "pachinko_board.png": (128 * SCALE, 128 * SCALE),
        "cards/card_front.png": (20 * SCALE, 28 * SCALE),
        "cards/card_back.png": (20 * SCALE, 28 * SCALE),
        "slot_reel_window.png": (96 * SCALE, 96 * SCALE),
        "slot_symbol_cherry.png": (16 * SCALE, 16 * SCALE),
        "slot_symbol_lemon.png": (16 * SCALE, 16 * SCALE),
        "slot_symbol_bell.png": (16 * SCALE, 16 * SCALE),
        "slot_symbol_bar.png": (16 * SCALE, 16 * SCALE),
        "slot_symbol_seven.png": (16 * SCALE, 16 * SCALE),
        "slot_spin_button.png": (64 * SCALE, 20 * SCALE),
        "slot_spin_button_hover.png": (64 * SCALE, 20 * SCALE),
        "slot_reel_strip.png": (16 * SCALE, 64 * SCALE),
    }
    expected_block = {n: (16 * SCALE, 16 * SCALE) for n, _ in BLOCK_ASSETS}
    problems = []
    checks = []
    for gui_light, gui_dark, block_dir in trees():
        checks += [(gui_light, expected_gui, "light"),
                   (gui_dark, expected_gui, "dark"),
                   (block_dir, expected_block, "light")]
    for root, manifest, theme in checks:
        for name, (ew, eh) in manifest.items():
            p = os.path.join(root, name)
            if not os.path.exists(p):
                problems.append(f"MISSING {p}")
                continue
            im = Image.open(p)
            if im.mode != "RGBA":
                problems.append(f"{p}: mode {im.mode}")
            if im.size != (ew, eh):
                problems.append(f"{p}: size {im.size} != {(ew, eh)}")
            a = im.getchannel("A")
            lo, hi = a.getextrema()
            if not (lo == 0 or lo == 255) or hi != 255:
                problems.append(f"{p}: alpha range {lo}..{hi}")
            # every opaque pixel must be on the theme palette
            allowed = {tuple(v[:3]) + (255,) for v in (LIGHT if theme == "light" else DARK).values()}
            off = 0
            for c in im.convert("RGBA").getcolors(maxcolors=1 << 20) or []:
                cnt, col = c
                if col[3] == 255 and col not in allowed:
                    off += cnt
            if off:
                problems.append(f"{p}: {off} off-palette px")
            sz = os.path.getsize(p)
            if sz >= 64 * 1024:
                problems.append(f"{p}: {sz}B >= 64KB")
    if problems:
        print("VERIFY FAILED")
        for x in problems:
            print("  -", x)
        return 1
    total = (len(expected_gui) * 2 + len(expected_block)) * len(NAMESPACES)
    print(f"VERIFY OK — {total} files across {len(NAMESPACES)} namespaces "
          f"({', '.join(NAMESPACES)}): correct size, RGBA, binary alpha, "
          f"on-palette, <64KB")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true")
    ap.add_argument("--verify", action="store_true")
    args = ap.parse_args()

    built = {"light": [], "dark": []}

    for gui_light, gui_dark, block_dir in trees():
        ns = gui_light.split("/assets/")[1].split("/")[0]
        print(f"\n=== namespace: {ns}")

        print("  GUI — light theme")
        set_theme(LIGHT)
        for name, fn in GUI_ASSETS:
            im = fn(LIGHT)
            save(im, os.path.join(gui_light, name), "light")
            if not built["light"]:
                built["light"].append((name, im))

        print("  GUI — dark theme (separate dark palette, same geometry)")
        set_theme(DARK)
        for name, fn in GUI_ASSETS:
            im = fn(DARK)
            save(im, os.path.join(gui_dark, name), "dark")
            if len(built["dark"]) < len(GUI_ASSETS):
                built["dark"].append((name, im))

        print("  block textures")
        set_theme(LIGHT)
        blocks = []
        for name, fn in BLOCK_ASSETS:
            im = fn(LIGHT)
            save(im, os.path.join(block_dir, name), "light")
            blocks.append((name, im))

    if args.preview:
        prev = os.path.join(ROOT, "build-art-preview")
        os.makedirs(prev, exist_ok=True)
        print("previews")
        contact_sheet(built["light"], 3, os.path.join(prev, "sheet_light.png"), "light gui")
        contact_sheet(built["dark"], 3, os.path.join(prev, "sheet_dark.png"), "dark gui")
        contact_sheet(blocks, 10, os.path.join(prev, "sheet_blocks.png"), "blocks 10x")

    print(f"\n{len(WRITTEN)} files written")
    if args.verify:
        return verify()
    return 0


if __name__ == "__main__":
    sys.exit(main())
