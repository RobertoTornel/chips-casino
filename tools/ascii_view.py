#!/usr/bin/env python3
"""Render a PNG as palette-mapped ASCII so its readability can be judged in text."""
import os
import sys
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
from gen_casino_art import LIGHT, DARK

# stable glyph per palette key (visual density roughly matches brightness)
GLYPH = {
 "INK":"@","BG_DEEP":"`","BG_PANEL":";","BG_PANEL_HI":":","INLAY":",",
 "WOOD_D":"W","WOOD_M":"w","WOOD_L":"v","WOOD_HI":"u",
 "BRASS_D":"b","BRASS":"B","BRASS_L":"P","GOLD":"G","GOLD_HI":"&","GOLD_SH":"p",
 "BLACK":"#","WHITE":"8","CREAM":"7","CREAM_D":"6","CREAM_SH":"5",
 "RED":"R","RED_D":"r","RED_L":"r","RED_HI":"r",
 "POCKET_GREEN":"g","GREEN_DOT":"g",
 "GREEN_FELT":"F","GREEN_FELT_D":"f","GREEN_FELT_L":"E",
 "BLUE_FELT":"L","BLUE_FELT_D":"l","BLUE_FELT_L":"e",
 "STEEL":"S","STEEL_L":"s","STEEL_D":"z","STEEL_SH":"Z",
 "LEATHER":"h","LEATHER_D":"H","LEATHER_L":"n",
}

def build(tab):
    d = {}
    for k, v in tab.items():
        d[tuple(v[:3]) + (255,)] = GLYPH.get(k, "?")
    return d

def dump(path, tab, scale=1, box=None):
    im = Image.open(path).convert("RGBA")
    if box:
        im = im.crop(box)
    m = build(tab)
    print(f"== {path.split('/')[-1]} {im.width}x{im.height} (scale {scale})")
    for y in range(0, im.height, scale):
        row = []
        for x in range(0, im.width, scale):
            c = im.getpixel((x, y))
            if c[3] == 0:
                row.append(".")
            else:
                row.append(m.get(c, "?"))
        print("".join(row))

if __name__ == "__main__":
    B = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                 "mc26.3", "src", "main", "resources", "assets", "casino", "textures")
    tab = DARK if "--dark" in sys.argv else LIGHT
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    scale = 1
    for a in sys.argv[1:]:
        if a.startswith("--scale="):
            scale = int(a.split("=")[1])
    for p in args:
        path = p if p.startswith("/") else f"{B}/{p}"
        dump(path, tab, scale)
