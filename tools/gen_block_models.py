#!/usr/bin/env python3
"""Generate the machine block models — FLAIR pass.

Every machine used to be `minecraft:block/cube_all`. Then it was a basic felt table /
upright cabinet. This pass makes them genuinely detailed furniture:

  table  — felt top, wood apron/skirt, four tapered legs with trim feet
           (roulette, blackjack, baccarat, exchange tall counter)
  cabinet— base plinth, body, overhanging top cap, a marquee box that rises ABOVE the
           block (lit panel inside), a bezelled screen, and a control ledge
           (poker machine, pachinko cabinet, pinball machine)
  floor  — low wood stage with an inlaid felt table and a dealer position marker
           (texas hold'em)

Every element stays within block-space [-16, 32] and references only declared textures
(casino_body_side, casino_body_top, and each machine's own felt/face texture), so no new
texture art is required — the coolness comes from geometry.

Run:
    python3 tools/gen_block_models.py
"""

import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

TREES = [
    "mc26.3",
    "mc1.20.1",
    "mc1.20.1-forge",
    "mc1.20.1-neoforge",
    "mc1.20.1-arch/common",
]

WOOD = "casino:block/casino_body_side"
TRIM = "casino:block/casino_body_top"


def face(uv, tex):
    return {"uv": list(uv), "texture": tex}


def box(name, frm, to, tex, faces=None):
    """One cuboid. `tex` on every face unless `faces` overrides by name."""
    if faces is None:
        faces = {f: face([0, 0, to[0] - frm[0], to[2] - frm[2]], tex)
                 for f in ("up", "down", "north", "south", "east", "west")}
    return {"name": name, "from": [float(v) for v in frm], "to": [float(v) for v in to],
            "faces": faces}


def table(top_tex, tall=False):
    """Casino table: thick felt top, apron skirt, four legs with trim feet.

    `tall` raises the top to counter height (exchange) with shorter legs.
    """
    top_y = 14 if tall else 10          # felt surface sits at top_y..top_y+2
    leg_top = top_y                      # apron hangs from leg_top down to leg_top-2
    apron_bottom = top_y - 2
    leg_h = leg_top                      # legs run floor..leg_top
    el = []

    # --- felt top (the distinctive machine texture) ---
    el.append({
        "name": "felt_top",
        "from": [0, top_y, 0],
        "to": [16, top_y + 2, 16],
        "faces": {
            "up": face((0, 0, 16, 16), top_tex),
            "down": face((0, 0, 16, 16), WOOD),
            "north": face((0, 0, 16, 2), TRIM),
            "south": face((0, 0, 16, 2), TRIM),
            "east": face((0, 0, 16, 2), TRIM),
            "west": face((0, 0, 16, 2), TRIM),
        },
    })

    # --- apron / side skirt, hangs below the felt ---
    ry = apron_bottom
    el.append(box("apron_n", [0, ry, 0], [16, ry + 2, 2], WOOD,
                  {"up": face((0, 0, 16, 2), TRIM)}))
    el.append(box("apron_s", [0, ry, 14], [16, ry + 2, 16], WOOD,
                  {"up": face((0, 0, 16, 2), TRIM)}))
    el.append(box("apron_w", [0, ry, 2], [2, ry + 2, 14], WOOD,
                  {"up": face((0, 0, 2, 12), TRIM)}))
    el.append(box("apron_e", [14, ry, 2], [16, ry + 2, 14], WOOD,
                  {"up": face((0, 0, 2, 12), TRIM)}))

    # --- four legs, floor to apron, with trim feet ---
    for nm, x, z in (("nw", 1, 1), ("ne", 13, 1), ("sw", 1, 13), ("se", 13, 13)):
        el.append(box(f"leg_{nm}", [x, 0, z], [x + 2, leg_h, z + 2], WOOD))
        el.append(box(f"foot_{nm}", [x, 0, z], [x + 2, 1, z + 2], TRIM))

    return {"textures": {"particle": top_tex, "felt": top_tex, "wood": WOOD,
                         "trim": TRIM},
            "elements": el}


def cabinet(face_tex, screen_tex=None):
    """Upright cabinet: plinth, body, overhanging cap, lit marquee (rises ABOVE the block
    so the machine is taller than one block), bezelled screen, control ledge."""
    screen = screen_tex or face_tex
    MARQUEE_TOP = 18  # marquee box rises from y=16 to y=18, above the block
    el = []

    # --- base plinth, sits on the floor ---
    el.append(box("plinth", [0, 0, 0], [16, 2, 16], WOOD,
                  {"up": face((0, 0, 16, 16), TRIM)}))

    # --- body ---
    el.append({
        "name": "body",
        "from": [1, 2, 1],
        "to": [15, 14, 15],
        "faces": {
            "up": face((0, 0, 14, 14), TRIM),
            "down": face((0, 0, 14, 14), WOOD),
            "north": face((0, 0, 14, 12), face_tex),
            "south": face((0, 0, 14, 12), WOOD),
            "east": face((0, 0, 14, 12), WOOD),
            "west": face((0, 0, 14, 12), WOOD),
        },
    })

    # --- top cap, overhangs the body ---
    el.append(box("cap", [0, 14, 0], [16, 16, 16], TRIM))

    # --- marquee box (the lit sign) ---
    el.append(box("marquee_box", [1, 16, 1], [15, MARQUEE_TOP, 15], WOOD,
                  {"up": face((0, 0, 14, 14), TRIM)}))
    # --- marquee light panel (the "glow", bright trim reads as lit neon) ---
    el.append({
        "name": "marquee_light",
        "from": [2, 16, 2],
        "to": [14, MARQUEE_TOP, 14],
        "faces": {
            "up": face((0, 0, 12, 12), TRIM),
            "down": face((0, 0, 12, 12), TRIM),
            "north": face((0, 0, 12, MARQUEE_TOP - 16), TRIM),
            "south": face((0, 0, 12, MARQUEE_TOP - 16), TRIM),
            "east": face((0, 0, 12, MARQUEE_TOP - 16), TRIM),
            "west": face((0, 0, 12, MARQUEE_TOP - 16), TRIM),
        },
    })

    # --- screen bezel, proud of the body front ---
    el.append({
        "name": "screen_bezel",
        "from": [2, 5, -0.5],
        "to": [14, 12, 1.5],
        "faces": {
            "north": face((0, 0, 12, 7), TRIM),
            "south": face((0, 0, 12, 7), TRIM),
            "up": face((0, 0, 12, 2), TRIM),
            "down": face((0, 0, 12, 2), TRIM),
            "east": face((0, 0, 2, 7), TRIM),
            "west": face((0, 0, 2, 7), TRIM),
        },
    })
    # --- screen glass, recessed inside the bezel ---
    el.append({
        "name": "screen",
        "from": [3, 6, 0.5],
        "to": [13, 11, 0.51],
        "faces": {
            "north": face((0, 0, 10, 5), screen),
            "south": face((0, 0, 10, 5), screen),
        },
    })

    # --- control ledge at the bottom front ---
    el.append({
        "name": "controls",
        "from": [2, 2, -1],
        "to": [14, 4, 1],
        "faces": {
            "up": face((0, 0, 12, 2), TRIM),
            "down": face((0, 0, 12, 2), WOOD),
            "north": face((0, 0, 12, 2), face_tex),
            "east": face((0, 0, 2, 2), WOOD),
            "west": face((0, 0, 2, 2), WOOD),
        },
    })

    return {"textures": {"particle": face_tex, "face": face_tex, "screen": screen,
                         "wood": WOOD, "trim": TRIM},
            "elements": el}


def floor_stage(top_tex):
    """Low wood stage with an inlaid felt table and a dealer position marker."""
    el = [
        # stage platform
        box("stage", [0, 0, 0], [16, 3, 16], WOOD),
        # stage trim edge
        box("stage_trim", [0, 3, 0], [16, 4, 16], TRIM,
            {"down": face((0, 0, 16, 16), WOOD)}),
        # inlaid felt table surface
        {
            "name": "felt",
            "from": [1, 4, 1],
            "to": [15, 5, 15],
            "faces": {
                "up": face((0, 0, 14, 14), top_tex),
                "down": face((0, 0, 14, 14), WOOD),
                "north": face((0, 0, 14, 1), TRIM),
                "south": face((0, 0, 14, 1), TRIM),
                "east": face((0, 0, 14, 1), TRIM),
                "west": face((0, 0, 14, 1), TRIM),
            },
        },
        # dealer position marker at the near edge
        box("dealer_spot", [7, 5, 0], [9, 6, 2], TRIM,
            {"up": face((0, 0, 2, 2), top_tex)}),
    ]
    return {"textures": {"particle": top_tex, "felt": top_tex, "wood": WOOD,
                         "trim": TRIM},
            "elements": el}


def slot_machine():
    """One-armed bandit cabinet: steel body, brass marquee that rises above the
    block, a proud reel-bezel, a control ledge and a protruding spin button.

    Front face uses the machine's own 16x16 art (marquee + reel windows +
    spin button already painted on it); sides read as wood, marquee as brass.
    """
    el = [
        # main cabinet body, front = machine face art
        {
            "name": "body",
            "from": [1, 0, 1],
            "to": [15, 13, 15],
            "faces": {
                "up": face((0, 0, 14, 14), TRIM),
                "down": face((0, 0, 14, 14), WOOD),
                "north": face((0, 0, 14, 13), "casino:block/slot_machine"),
                "south": face((0, 0, 14, 13), WOOD),
                "east": face((0, 0, 14, 13), WOOD),
                "west": face((0, 0, 14, 13), WOOD),
            },
        },
        # brass marquee overhanging the body, taller than one block
        box("marquee", [0, 13, 0], [16, 18, 16], TRIM,
            {"up": face((0, 0, 16, 16), TRIM)}),
        # lit marquee tube (the "glow")
        {
            "name": "marquee_light",
            "from": [2, 14, 1],
            "to": [14, 17, 15],
            "faces": {
                "up": face((0, 0, 12, 14), TRIM),
                "down": face((0, 0, 12, 14), TRIM),
                "north": face((0, 0, 12, 3), TRIM),
                "south": face((0, 0, 12, 3), TRIM),
                "east": face((0, 0, 14, 3), TRIM),
                "west": face((0, 0, 14, 3), TRIM),
            },
        },
        # proud bezel around the reel window
        {
            "name": "reel_bezel",
            "from": [2, 3, -0.5],
            "to": [14, 11, 0.5],
            "faces": {
                "north": face((0, 0, 12, 8), WOOD),
                "south": face((0, 0, 12, 8), WOOD),
                "up": face((0, 0, 12, 1), WOOD),
                "down": face((0, 0, 12, 1), WOOD),
                "east": face((0, 0, 1, 8), WOOD),
                "west": face((0, 0, 1, 8), WOOD),
            },
        },
        # control ledge at the bottom front
        {
            "name": "controls",
            "from": [2, 1, -1],
            "to": [14, 3, 1],
            "faces": {
                "up": face((0, 0, 12, 2), TRIM),
                "down": face((0, 0, 12, 2), WOOD),
                "north": face((0, 0, 12, 2), "casino:block/slot_machine"),
                "east": face((0, 0, 2, 2), WOOD),
                "west": face((0, 0, 2, 2), WOOD),
            },
        },
        # protruding spin button
        {
            "name": "spin_button",
            "from": [7, 1.5, 1],
            "to": [9, 2.5, 2],
            "faces": {
                "north": face((0, 0, 2, 1), TRIM),
                "south": face((0, 0, 2, 1), WOOD),
                "up": face((0, 0, 2, 1), TRIM),
                "down": face((0, 0, 2, 1), WOOD),
                "east": face((0, 0, 1, 1), WOOD),
                "west": face((0, 0, 1, 1), WOOD),
            },
        },
    ]
    return {"textures": {"particle": "casino:block/slot_machine",
                         "face": "casino:block/slot_machine",
                         "wood": WOOD, "trim": TRIM},
            "elements": el}


# name -> builder
MODELS = {
    "blackjack_table":    table("casino:block/blackjack_table"),
    "baccarat_table":     table("casino:block/baccarat_table"),
    "roulette_table":     table("casino:block/roulette_table"),
    "exchange":           table("casino:block/exchange"),
    "poker_machine":      cabinet("casino:block/poker_machine"),
    "pachinko_cabinet":   cabinet("casino:block/pachinko_cabinet"),
    "pinball_machine":    cabinet("casino:block/pinball_machine"),
    "texas_holdem_floor": floor_stage("casino:block/texas_holdem_floor"),
    "slot_machine":       slot_machine(),
}


def item_display():
    return {
        "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0],
                                  "scale": [0.35, 0.35, 0.35]},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0],
                                 "scale": [0.35, 0.35, 0.35]},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0],
                                  "scale": [0.325, 0.325, 0.325]},
        "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0],
                                 "scale": [0.325, 0.325, 0.325]},
    }


def main():
    written = 0
    for tree in TREES:
        block_dir = os.path.join(ROOT, tree, "src/main/resources/assets/casino/models/block")
        item_dir = os.path.join(ROOT, tree, "src/main/resources/assets/casino/models/item")
        if not os.path.isdir(os.path.dirname(block_dir)):
            print(f"  skip {tree} (no resources)")
            continue
        os.makedirs(block_dir, exist_ok=True)
        os.makedirs(item_dir, exist_ok=True)
        for name, model in MODELS.items():
            with open(os.path.join(block_dir, f"{name}.json"), "w", encoding="utf-8") as f:
                json.dump(model, f, indent=2)
                f.write("\n")
            with open(os.path.join(item_dir, f"{name}.json"), "w", encoding="utf-8") as f:
                json.dump({"parent": f"casino:block/{name}", "display": item_display()}, f,
                          indent=2)
                f.write("\n")
            written += 2
        print(f"  {tree}: {len(MODELS)} block models + {len(MODELS)} item models")

    print(f"\n{written} model files written")


if __name__ == "__main__":
    main()
