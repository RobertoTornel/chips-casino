#!/usr/bin/env python3
"""
Port a Minecraft 26.3 screen source to the 1.20.1 API.

The two eras differ in exactly four render primitives and one method name; everything
else (layout maths, state parsing, button wiring) is identical. Rather than keep two
hand-maintained copies of every screen and let them drift — which is how the sell rate
once ended up different on the Exchange footer and the block footer — screens are
authored once for 26.3 and mechanically rewritten for 1.20.1 here.

Transformations
---------------
    GuiGraphicsExtractor            -> GuiGraphics
    extractRenderState(             -> render(
    extractor.text(f, s, x, y, c)   -> graphics.drawString(f, s, x, y, c, false)
    extractor.centeredText(f,s,x,y,c) -> graphics.drawCenteredString(f, s, x, y, c)
    extractor.fill(...)             -> graphics.fill(...)
    extractor.blit(SPRITE, x, y, w, h, 0f, 0f, 1f, 1f)
                                    -> blitTexture(graphics, SPRITE, x, y, w, h,
                                                   CasinoSprites.SPRITE_W, .SPRITE_H)

Usage:
    tools/port_screen.py <src-26.3.java> <dst-1.20.1.java> [--check]
        --check   report the transformations without writing the file
"""
import re
import sys
from pathlib import Path

# 26.3 sprite constant -> (width constant, height constant) in the 1.20.1 CasinoSprites
SPRITE_SIZES = {
    "CASINO_FRAME": ("CASINO_FRAME_W", "CASINO_FRAME_H"),
    "HEADER_BAR": ("HEADER_BAR_W", "HEADER_BAR_H"),
    "BALANCE_BAR": ("BALANCE_BAR_W", "BALANCE_BAR_H"),
    "BUTTON": ("BUTTON_W", "BUTTON_H"),
    "BUTTON_HOVER": ("BUTTON_W", "BUTTON_H"),
    "CARD_FRONT": ("CARD_W", "CARD_H"),
    "CARD_BACK": ("CARD_W", "CARD_H"),
    "ROULETTE_WHEEL": ("ROULETTE_WHEEL_W", "ROULETTE_WHEEL_H"),
    "CHIP": ("CHIP_W", "CHIP_H"),
    "PACHINKO_BOARD": ("PACHINKO_BOARD_W", "PACHINKO_BOARD_H"),
    "SLOT_REEL_WINDOW": ("SLOT_REEL_WINDOW_W", "SLOT_REEL_WINDOW_H"),
    "SYMBOL_CHERRY": ("SYMBOL_W", "SYMBOL_H"),
    "SYMBOL_LEMON": ("SYMBOL_W", "SYMBOL_H"),
    "SYMBOL_BELL": ("SYMBOL_W", "SYMBOL_H"),
    "SYMBOL_BAR": ("SYMBOL_W", "SYMBOL_H"),
    "SYMBOL_SEVEN": ("SYMBOL_W", "SYMBOL_H"),
}

# CasinoSprites.NAME -> bare NAME, so the blit rewrite can look the size up
SPRITE_REF = re.compile(r"CasinoSprites\.([A-Z_]+)")


def split_args(text, start):
    """Split a balanced argument list; `start` indexes the char just after '('."""
    depth = 1
    i = start
    cur = []
    args = []
    in_str = False
    esc = False
    while i < len(text):
        ch = text[i]
        if in_str:
            cur.append(ch)
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
            cur.append(ch)
        elif ch in "([{":
            depth += 1
            cur.append(ch)
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                args.append("".join(cur).strip())
                return args, i + 1
            cur.append(ch)
        elif ch == "," and depth == 1:
            args.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
        i += 1
    raise ValueError("unbalanced argument list")


def rewrite_calls(src, method, builder):
    """Replace every `extractor.<method>(...)` using `builder(args) -> replacement`."""
    needle = f"extractor.{method}("
    out = []
    i = 0
    count = 0
    while True:
        j = src.find(needle, i)
        if j < 0:
            out.append(src[i:])
            break
        out.append(src[i:j])
        args, after = split_args(src, j + len(needle))
        out.append(builder(args))
        i = after
        count += 1
    return "".join(out), count


def blit_builder(recv, args):
    if len(args) != 9:
        raise ValueError(f"blit expects 9 args, got {len(args)}: {args}")
    sprite, x, y, w, h = args[0], args[1], args[2], args[3], args[4]
    m = SPRITE_REF.fullmatch(sprite)
    if not m or m.group(1) not in SPRITE_SIZES:
        raise ValueError(f"blit: unknown sprite {sprite!r} — add it to SPRITE_SIZES")
    name = m.group(1)
    wc, hc = SPRITE_SIZES[name]
    return (f"blitTexture({recv}, {sprite}, {x}, {y}, {w}, {h}, "
            f"CasinoSprites.{wc}, CasinoSprites.{hc})")


def rewrite_pose(src, alt):
    """Strip the `.pose()` wrapper from 26.3 renderer calls.

    1.20.1's GuiGraphics exposes pose-stack methods directly
    (pushPose/popPose/translate/mulPose/...), so `e.pose().pushMatrix()` becomes
    `e.pushPose()`. `pushMatrix`/`popMatrix` are renamed; everything else passes
    through with `.pose()` removed.
    """
    pattern = re.compile(r"\b(" + alt + r")\.pose\(\)\.([A-Za-z]+)\(")
    out = []
    i = 0
    count = 0
    while True:
        m = pattern.search(src, i)
        if not m:
            out.append(src[i:])
            break
        out.append(src[i:m.start()])
        recv, method = m.group(1), m.group(2)
        mapped = {"pushMatrix": "pushPose", "popMatrix": "popPose"}.get(method, method)
        args, after = split_args(src, m.end())
        out.append(f"{recv}.{mapped}(" + ", ".join(args) + ")")
        count += 1
        i = after
    return "".join(out), count


def port(src):
    """Rewrite a 26.3 screen source into its 1.20.1 equivalent."""
    stats = {}

    # Every identifier that holds a renderer: method parameters and locals. Screens name
    # this `extractor`, `e`, `g`... so the rewrite has to key off the declared type, not
    # off a fixed variable name.
    receivers = sorted(set(re.findall(r"GuiGraphicsExtractor\s+(\w+)", src)))
    if not receivers:
        raise ValueError("no GuiGraphicsExtractor parameter found — is this a screen?")

    alt = "|".join(re.escape(r) for r in receivers)

    def rewrite(method, builder):
        pattern = re.compile(r"\b(" + alt + r")\.(" + method + r")\(")
        out = []
        i = 0
        n = 0
        while True:
            m = pattern.search(src, i)
            if not m:
                out.append(src[i:])
                return "".join(out), n
            out.append(src[i:m.start()])
            recv = m.group(1)
            args, after = split_args(src, m.end())
            out.append(builder(recv, args))
            n += 1
            i = after

    src, stats["text"] = rewrite("text", lambda r, a: f"{r}.drawString(" + ", ".join(a) + ", false)")
    src, stats["centeredText"] = rewrite("centeredText", lambda r, a: f"{r}.drawCenteredString(" + ", ".join(a) + ")")
    src, stats["fill"] = rewrite("fill", lambda r, a: f"{r}.fill(" + ", ".join(a) + ")")
    src, stats["blit"] = rewrite("blit", blit_builder)
    src, stats["pose"] = rewrite_pose(src, alt)

    # Anything left is a renderer call with no 1.20.1 counterpart.
    leftovers = sorted(set(re.findall(r"\b(?:" + alt + r")\.([A-Za-z]+)", src)))
    good = {"fill", "drawString", "drawCenteredString",
            "pushPose", "popPose", "translate", "rotate", "mulPose", "scale"}
    bad = [x for x in leftovers if x not in good]
    if bad:
        raise ValueError(f"unported renderer calls remain: {bad}")

    src = src.replace("import net.minecraft.client.gui.GuiGraphicsExtractor;",
                      "import net.minecraft.client.gui.GuiGraphics;")
    src = src.replace("GuiGraphicsExtractor", "GuiGraphics")
    src = re.sub(r"\bextractRenderState\s*\(", "render(", src)

    stats["receivers"] = len(receivers)
    return src, stats


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    src_path = Path(argv[1])
    dst_path = Path(argv[2])
    check = "--check" in argv
    text, stats = port(src_path.read_text())
    summary = ", ".join(f"{k}={v}" for k, v in stats.items())
    if check:
        print(f"would port {src_path.name}: {summary}")
        return 0
    dst_path.write_text(text)
    print(f"ported {src_path.name} -> {dst_path}  ({summary})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
