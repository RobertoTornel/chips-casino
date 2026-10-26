#!/usr/bin/env python3
"""Generate the machine block models.

Every machine used to be `minecraft:block/cube_all` — a 16x16 texture repeated on all six
faces of a solid cube. That is why the items looked wrong: a blackjack table rendered as a
featureless box you could not tell from a stone block, and the "texture" was a top-down
view smeared onto the sides.

These are real element models instead. Three silhouettes, because the machines are three
different pieces of furniture:

  table  — a felt top on four legs with a raised rim   (roulette, blackjack, baccarat,
                                                         texas hold'em, exchange)
  cabinet— a tall body with a lit marquee and a screen  (poker machine, pachinko, pinball)

Boxes are emitted from a small table of coordinates so the shapes stay consistent and a
change to the leg size fixes every table at once.

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

# Textures every machine shares, plus one distinctive top/face per machine.
WOOD = "casino:block/casino_body_side"
TRIM = "casino:block/casino_body_top"


def box(name, frm, to, tex, faces=None):
    """One cuboid. `tex` applies to every face unless `faces` overrides by name."""
    if faces is None:
        faces = {f: {"uv": [0, 0, to[0] - frm[0], to[2] - frm[2]], "texture": tex}
                 for f in ("up", "down", "north", "south", "east", "west")}
    return {"name": name, "from": list(frm), "to": list(to), "faces": faces}


def face(uv, tex):
    return {"uv": list(uv), "texture": tex}


def table(top_tex, seat_tex=None, tall=False):
    """A felt table: top, raised rim, four legs. `tall` lifts it to counter height."""
    top_y = 14 if tall else 12
    leg_h = top_y
    el = []

    # felt surface — the only face that shows the distinctive machine texture
    el.append({
        "name": "felt",
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

    # raised rim, so the top reads as a table and not a slab
    ry = top_y + 2
    el.append(box("rim_n", [0, ry, 0], [16, ry + 2, 2], WOOD))
    el.append(box("rim_s", [0, ry, 14], [16, ry + 2, 16], WOOD))
    el.append(box("rim_w", [0, ry, 2], [2, ry + 2, 14], WOOD))
    el.append(box("rim_e", [14, ry, 2], [16, ry + 2, 14], WOOD))

    # legs
    for nm, x, z in (("nw", 1, 1), ("ne", 13, 1), ("sw", 1, 13), ("se", 13, 13)):
        el.append(box(f"leg_{nm}", [x, 0, z], [x + 2, leg_h, z + 2], WOOD))

    return {"textures": {"particle": top_tex, "felt": top_tex, "wood": WOOD, "trim": TRIM},
            "elements": el}


def cabinet(face_tex, screen_tex=None):
    """An upright cabinet: body, lit marquee on top, screen recessed into the front."""
    screen = screen_tex or face_tex
    el = [
        # main body, inset a little so the marquee overhangs it
        {
            "name": "body",
            "from": [1, 0, 1],
            "to": [15, 13, 15],
            "faces": {
                "up": face((0, 0, 14, 14), TRIM),
                "down": face((0, 0, 14, 14), WOOD),
                "north": face((0, 0, 14, 13), face_tex),
                "south": face((0, 0, 14, 13), WOOD),
                "east": face((0, 0, 14, 13), WOOD),
                "west": face((0, 0, 14, 13), WOOD),
            },
        },
        # lit marquee: the glowing sign that makes a cabinet recognisable at a distance
        {
            "name": "marquee",
            "from": [0, 13, 0],
            "to": [16, 16, 16],
            "faces": {
                "up": face((0, 0, 16, 16), TRIM),
                "north": face((0, 0, 16, 3), TRIM),
                "south": face((0, 0, 16, 3), TRIM),
                "east": face((0, 0, 16, 3), TRIM),
                "west": face((0, 0, 16, 3), TRIM),
            },
        },
        # screen panel proud of the body's front face
        {
            "name": "screen",
            "from": [3, 4, -0.01],
            "to": [13, 11, 1.01],
            "faces": {
                "north": face((0, 0, 10, 7), screen),
                "south": face((0, 0, 10, 7), screen),
                "up": face((0, 0, 10, 1), TRIM),
                "down": face((0, 0, 10, 1), TRIM),
                "east": face((0, 0, 1, 7), TRIM),
                "west": face((0, 0, 1, 7), TRIM),
            },
        },
        # control ledge at the bottom front
        {
            "name": "controls",
            "from": [2, 1, -1.5],
            "to": [14, 3, 1],
            "faces": {
                "up": face((0, 0, 12, 2), TRIM),
                "down": face((0, 0, 12, 2), WOOD),
                "north": face((0, 0, 12, 2), face_tex),
                "east": face((0, 0, 2, 2), WOOD),
                "west": face((0, 0, 2, 2), WOOD),
            },
        },
    ]
    return {"textures": {"particle": face_tex, "face": face_tex, "screen": screen,
                         "wood": WOOD, "trim": TRIM},
            "elements": el}


def floor_room(top_tex):
    """A floor-covering table: low felt surface inlaid into a wooden border."""
    el = [
        # wooden surround
        box("border", [0, 0, 0], [16, 2, 16], WOOD),
        # inlaid felt, slightly proud so the edge reads
        {
            "name": "felt",
            "from": [1, 2, 1],
            "to": [15, 3, 15],
            "faces": {
                "up": face((0, 0, 14, 14), top_tex),
                "down": face((0, 0, 14, 14), WOOD),
                "north": face((0, 0, 14, 1), TRIM),
                "south": face((0, 0, 14, 1), TRIM),
                "east": face((0, 0, 14, 1), TRIM),
                "west": face((0, 0, 14, 1), TRIM),
            },
        },
    ]
    return {"textures": {"particle": top_tex, "felt": top_tex, "wood": WOOD, "trim": TRIM},
            "elements": el}


# name -> builder(top_texture)
MODELS = {
    "blackjack_table":    table("casino:block/blackjack_table"),
    "baccarat_table":     table("casino:block/baccarat_table"),
    "roulette_table":     table("casino:block/roulette_table"),
    "exchange":           table("casino:block/exchange", tall=True),
    "poker_machine":      cabinet("casino:block/poker_machine"),
    "pachinko_cabinet":   cabinet("casino:block/pachinko_cabinet"),
    "pinball_machine":    cabinet("casino:block/pinball_machine"),
    "texas_holdem_floor": floor_room("casino:block/texas_holdem_floor"),
}


def main():
    written = 0
    for tree in TREES:
        out_dir = os.path.join(ROOT, tree, "src/main/resources/assets/casino/models/block")
        if not os.path.isdir(os.path.dirname(out_dir)):
            print(f"  skip {tree} (no resources)")
            continue
        os.makedirs(out_dir, exist_ok=True)
        for name, model in MODELS.items():
            path = os.path.join(out_dir, f"{name}.json")
            with open(path, "w", encoding="utf-8") as f:
                json.dump(model, f, indent=2)
                f.write("\n")
            written += 1
        print(f"  {tree}: {len(MODELS)} models")

    # Regenerate the inventory models too: an item model must not just point at the block
    # model, or it inherits the block's flat front-on projection. Wrapping the block model
    # in a fixed rotation gives the held/inventory icon its 3D read.
    for tree in TREES:
        item_dir = os.path.join(ROOT, tree, "src/main/resources/assets/casino/models/item")
        if not os.path.isdir(os.path.dirname(item_dir)):
            continue
        os.makedirs(item_dir, exist_ok=True)
        for name in MODELS:
            path = os.path.join(item_dir, f"{name}.json")
            with open(path, "w", encoding="utf-8") as f:
                json.dump({
                    "parent": f"casino:block/{name}",
                    "display": {
                        # Drops the item at the same jaunty angle vanilla blocks use, so a
                        # machine in the hotbar reads as furniture rather than a flat tile.
                        "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0],
                                "scale": [0.625, 0.625, 0.625]},
                        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0],
                                  "scale": [1, 1, 1]},
                        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0],
                                   "scale": [0.25, 0.25, 0.25]},
                        "firstperson_righthand": {"rotation": [0, 45, 0],
                                                  "translation": [0, 0, 0],
                                                  "scale": [0.4, 0.4, 0.4]},
                        "firstperson_lefthand": {"rotation": [0, 225, 0],
                                                 "translation": [0, 0, 0],
                                                 "scale": [0.4, 0.4, 0.4]},
                        "thirdperson_righthand": {"rotation": [75, 45, 0],
                                                  "translation": [0, 2.5, 0],
                                                  "scale": [0.375, 0.375, 0.375]},
                        "thirdperson_lefthand": {"rotation": [75, 45, 0],
                                                 "translation": [0, 2.5, 0],
                                                 "scale": [0.375, 0.375, 0.375]},
                    },
                }, f, indent=2)
                f.write("\n")
            written += 1
        print(f"  {tree}: {len(MODELS)} item models")

    print(f"\n{written} model files written")


if __name__ == "__main__":
    main()
