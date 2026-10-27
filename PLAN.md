# PLAN.md — Casino "way fuckin cooler" visual flair pass

**Project:** /home/mana/Documents/mcmod/emc-casino — five trees:
`mc26.3` (Fabric, Java 25), `mc1.20.1` (Fabric, Java 17), `mc1.20.1-forge`,
`mc1.20.1-neoforge`, `mc1.20.1-arch/common` (Architectury, Java 17).
**Goal:** major visual flair pass, verifiable statically only — no client
testing. Java compiles via `mc_gradle` (one task per projectDir), JSON valid,
all three gate scripts stay green: `tools/layout_check.py`,
`tools/asset_sync_check.py`, `tools/verify_jars.py`.
**Baseline:** `layout_check.py` = 40/40 screens pass; `asset_sync_check.py` =
all textures identical across the five trees.

---

## Constraints (fixed)

- No EMC references anywhere (price table is "just numbers", currency is
  Chips ◈). No `emc` strings, no `thermal`/`integrateddynamics` hooks.
- New Java files: compile on all five trees. Renderer code additive and
  defensive. Prefer data-driven (JSON model) changes over Java renderer code.
- No new block-entity renderers (Fabric/Forge/NeoForge/Architectury renderer
  APIs diverge; skip unless trivially portable — not attempted here).
- No new textures unless trivial. All glow/bevel reads from reused textures
  only (`casino_body_top` brass lacquer is the brightest existing texture).
- Element bounds: every `from`/`to` component in [-16, 32]. Every texture
  referenced in a model's `textures` block must exist as a shipped file.
- Blockstates, model names, and texture names stay identical →
  `verify_jars.py` expectations unchanged. No new files under
  `textures/` → `asset_sync_check.py` unaffected.

---

## Change 1 — Block models (richer furniture silhouettes)

Touched in all five trees:
`src/main/resources/assets/casino/models/block/{name}.json` for
`roulette_table, blackjack_table, baccarat_table, exchange, poker_machine,
pachinko_cabinet, pinball_machine`. (`texas_holdem_floor` already has a
border + inlaid felt — unchanged.)

Every model declares exactly:
```json
"textures": {
  "particle": "casino:block/<machine>",
  "face":     "casino:block/<machine>",
  "side":     "casino:block/casino_body_side",
  "top":      "casino:block/casino_body_top"
}
```
All four textures already exist in every tree. `<machine>` = the block's own
16x16 art (felt for tables, cabinet face for cabinets).

### 1a — Tables (roulette_table, blackjack_table, baccarat_table, exchange)

`top_y = 12` for the three game tables; `top_y = 14` for `exchange` (tall
counter). All six faces need not be listed — only visible faces are declared
(culled faces omitted), matching the style of the current models.

| Element | from | to | Faces (uv → texture) |
|---|---|---|---|
| `felt` (inset surface) | [1, top_y, 1] | [15, top_y+2, 15] | up [0,0,14,14]→face; down [0,0,14,14]→side; N/S/E/W [0,0,14,2]→top |
| `apron` (side apron skirt under felt) | [0, top_y+2, 0] | [16, top_y+3, 16] | N/S/E/W [0,0,16,1]→side |
| `leg_nw` | [1, 0, 1] | [3, top_y+1, 3] | all six → side [0,0,2,top_y+1] |
| `leg_ne` | [13, 0, 1] | [15, top_y+1, 3] | all six → side [0,0,2,top_y+1] |
| `leg_sw` | [1, 0, 13] | [3, top_y+1, 15] | all six → side [0,0,2,top_y+1] |
| `leg_se` | [13, 0, 13] | [15, top_y+1, 15] | all six → side [0,0,2,top_y+1] |
| `foot_nw` (leg detail: 1-px bright foot cap) | [1, 0, 1] | [3, 1, 3] | up→top[0,0,2,2]; down,N,S,E,W→side[0,0,2,2] |
| `foot_ne` | [13, 0, 1] | [15, 1, 3] | same as foot_nw |
| `foot_sw` | [1, 0, 13] | [3, 1, 15] | same as foot_nw |
| `foot_se` | [13, 0, 13] | [15, 1, 15] | same as foot_nw |
| `rim_n` (raised rim cap on apron) | [0, top_y+3, 0] | [16, top_y+5, 2] | up→side[0,0,16,2]; N [0,0,16,2]→side; down,E,W→side |
| `rim_s` | [0, top_y+3, 14] | [16, top_y+5, 16] | same shape as rim_n |
| `rim_w` | [0, top_y+3, 2] | [2, top_y+5, 14] | same shape as rim_n |
| `rim_e` | [14, top_y+3, 2] | [16, top_y+5, 14] | same shape as rim_n |

Stack reads bottom-up: legs 0→top_y+1, felt top_y→top_y+2, apron skirt
top_y+2→top_y+3, rim cap top_y+3→top_y+5. Max coordinate: exchange
`top_y+5 = 19` ≤ 32 ✓; min 0 ✓. Felt inset (x/z 1→15) leaves the apron
and legs visible around the felt → "inset felt + leg details + side apron".
Felt surfaces use the existing machine felt textures (solid casino felt
colour by construction of gen_casino_art.py).

### 1b — Cabinets (poker_machine, pachinko_cabinet, pinball_machine)

| Element | from | to | Faces (uv → texture) |
|---|---|---|---|
| `body` | [1, 0, 1] | [15, 13, 15] | up→top[0,0,14,14]; down→side[0,0,14,14]; north→face[0,0,14,13]; S/E/W→side[0,0,14,13] |
| `marquee` (lit sign overhanging body) | [0, 13, 0] | [16, 16, 16] | up→top[0,0,16,16]; N/S/E/W→top[0,0,16,3] |
| `marquee_light` (thin bright tube = glow) | [2, 14, 1] | [14, 15, 15] | up→top[0,0,12,14]; N/S→top[0,0,12,1]; E/W→top[0,0,14,1]; down omitted |
| `screen_bezel` (proud bezel frame) | [2, 3, -1] | [14, 12, 0] | north→face[0,0,12,9]; E/W→side[0,0,1,9]; up/down→top[0,0,12,1]; south omitted |
| `screen` (recessed glass inside bezel) | [3, 4, -0.5] | [13, 11, 0] | north/south→face[0,0,10,7]; up/down→top[0,0,10,1]; E/W→top[0,0,1,7] |
| `controls` (control panel ledge) | [2, 1, -1.5] | [14, 3, 1] | up→top[0,0,12,2]; down→side[0,0,12,2]; north→face[0,0,12,2]; E/W→side[0,0,2,2] |
| `button_a` (panel button) | [4, 1.5, 1] | [6, 2.5, 2] | north→top[0,0,2,1]; up/down→top[0,0,2,1]; E/W→side[0,0,1,1]; south→side[0,0,2,1] |
| `button_b` | [7, 1.5, 1] | [9, 2.5, 2] | same as button_a |
| `button_c` | [10, 1.5, 1] | [12, 2.5, 2] | same as button_a |

Marquee overhangs body (0→16 vs 1→15) and the `marquee_light` tube (1 unit
tall, brass `top` texture) reads as a lit neon strip — the "glow" feel with
no custom renderer. Screen bezel proud in front of body, screen recessed
inside → furniture silhouette with bezel + control panel buttons.
Max coordinate 16 ✓, min -1.5 ✓ (within [-16, 32]).

### 1c — texas_holdem_floor
Unchanged (already border + inlaid felt, all textures declared).

---

## Change 2 — Item models (display transforms)

Touched in all five trees:
`src/main/resources/assets/casino/models/item/{name}.json` for the same eight
blocks. Keep `"parent": "casino:block/<name>"`; only re-tune the `display`
block. Fixed/gui/ground/firstperson/thirdperson categories kept; angles and
scales tuned per silhouette so held/inventory reads as furniture.

Shared transforms (all machines):
```json
"fixed":      { rotation: [0,0,0], translation: [0,0,0], scale: [1,1,1] }
"ground":     { rotation: [0,0,0], translation: [0,3,0], scale: [0.3,0.3,0.3] }
"firstperson_righthand": { rotation: [0,45,0], translation: [0,0,0], scale: [0.35,0.35,0.35] }
"firstperson_lefthand":  { rotation: [0,225,0], translation: [0,0,0], scale: [0.35,0.35,0.35] }
"thirdperson_righthand": { rotation: [75,45,0], translation: [0,2.5,0], scale: [0.375,0.375,0.375] }
"thirdperson_lefthand":  { rotation: [75,45,0], translation: [0,2.5,0], scale: [0.375,0.375,0.375] }
```

Per-silhouette `gui` (inventory/hotbar read):
- Tables (roulette, blackjack, baccarat, exchange, texas_holdem_floor):
  rotation [45, 225, 0], translation [0, 0, 0], scale [0.5, 0.5, 0.5]
  → felt top visible at a jaunty angle.
- Cabinets (poker_machine, pachinko_cabinet, pinball_machine):
  rotation [35, 220, 0], translation [0, 0, 0], scale [0.55, 0.55, 0.55]
  → marquee + screen face toward the player.

---

## Change 3 — Screen polish (Java, additive)

Neon-accent text colours already exist (`COLOR_TEXT_GOLD/W/GREEN/R`). Add one
subtle marquee strip in the base screen header, drawn after the balance text,
entirely inside the frame interior:

- Constant (both APIs): `public static int COLOR_MARQUEE = 0xFF00E5FF;` (neon
  cyan, opaque — safe for `fill` without blend state).
- Rail: fill a 2-px-tall bar from `(contentX(), topPos + 30)` to
  `(contentX() + contentW(), topPos + 32)` — the bottom 2 rows of the header
  bar, spanning the content interior width.
- Label: `text("CHIPS ◈", contentX() + 2, topPos + 31, COLOR_TEXT_WHITE)`.

Why this stays green in `layout_check.py`:
- `BaseCasinoScreen.java` is excluded from the leaf-screen check (only leaf
  `*Screen.java` files are audited), so the new `text()` call in the base is
  not even parsed.
- Even if parsed: `contentX()` → `L+24`, so x = 26 ∈ [4, bgw−20]; y = 31 ∈
  [6, content_bottom − 8] (content_bottom = 212 for bgHeight 240). No new
  `SpriteButton` widgets → no overlap risk.

Tree-specific render code:
- `mc26.3/.../client/gui/BaseCasinoScreen.java` — `GuiGraphicsExtractor`:
  `extractor.fill(contentX(), topPos + 30, contentX() + contentW(), topPos + 32, COLOR_MARQUEE);`
  `extractor.text(this.font, "CHIPS \u25C8", contentX() + 2, topPos + 31, COLOR_TEXT_WHITE);`
  `Identifier` + normalised UVs already used elsewhere in this file; no new
  imports needed.
- `mc1.20.1/.../client/gui/BaseCasinoScreen.java` (identical render code in
  `mc1.20.1`, `mc1.20.1-forge`, `mc1.20.1-neoforge`, `mc1.20.1-arch/common`)
  — `GuiGraphics`:
  `graphics.fill(contentX(), topPos + 30, contentX() + contentW(), topPos + 32, COLOR_MARQUEE);`
  `graphics.drawString(this.font, "CHIPS \u25C8", contentX() + 2, topPos + 31, COLOR_TEXT_WHITE, false);`
  No new imports (`ResourceLocation` already imported; `fill`/`drawString`
  already used).

Edit placement: directly after the balance-string `text`/`drawString` call,
before `renderGameContent(...)`. Additive only — no existing calls moved or
removed.

---

## Change 4 — Texture plan

**No new textures.** Every model element references only:
- `casino:block/<machine>` (exists for all 8 blocks)
- `casino:block/casino_body_side`
- `casino:block/casino_body_top`

The "glow" feel comes from geometry + the existing brass-lacquer
`casino_body_top` on `marquee_light`, `feet`, and `button_*` elements. Because
no PNG is added, removed, or renamed, `asset_sync_check.py` stays green with
no re-run of `gen_casino_art.py`. If a truly trivial new texture is wanted
later, it must be written byte-identical into all five trees under
`assets/casino/textures/block/` (and `resourcepacks/.../gui` for GUI) before
`asset_sync_check.py` will pass.

---

## Tree matrix

| Change | mc26.3 | mc1.20.1 | mc1.20.1-forge | mc1.20.1-neoforge | mc1.20.1-arch/common |
|---|---|---|---|---|---|
| Block models ×8 | models/block | models/block | models/block | models/block | common/models/block |
| Item models ×8 | models/item | models/item | models/item | models/item | common/models/item |
| BaseCasinoScreen marquee | GuiGraphicsExtractor variant | GuiGraphics variant | same GuiGraphics edit | same GuiGraphics edit | same GuiGraphics edit |
| Textures | none | none | none | none | none |
| Blockstates / lang | none | none | none | none | none |

The four 1.20.1 trees' `BaseCasinoScreen.render` bodies are identical (only
`sendAction` networking differs), so the same edit text applies to all four.
Block/item model JSON is identical across all five trees (verified: `diff -rq`
clean between mc26.3 and mc1.20.1 models).

---

## Verification plan (static gates only)

1. **JSON validity + model sanity** — one-off Python check over the 80 model
   files (8 blocks × 8 items × 5 trees): `json.load`, every element
   `from`/`to` component in [-16, 32], every texture value declared in the
   model's `textures` block, every declared texture file exists on disk, face
   `uv` arrays have 4 numbers. (Run during implementation; not part of the
   shipped gates.)
2. **`python3 tools/layout_check.py <5 src roots>`** — must remain 40/40 (base
   screen excluded; leaf screens untouched).
3. **`python3 tools/asset_sync_check.py`** — must still report all textures
   identical (no texture changes).
4. **`mc_gradle build` (single task) per tree** — Java must compile on all
   five trees: mc26.3 (JDK 25 toolchain), four 1.20.1 trees (JDK 17).
5. **`python3 tools/verify_jars.py`** against `dist/*.jar` — blockstates,
   block models, block textures, GUI sprites, chip model/texture, screens,
   lang, namespace purity, no client leaks in server classes.
6. Existing unit tests (`PricingRulesTest`, `PachinkoEngineTest`,
   `PinballEngineTest`) run as part of each build's `test` task.

Out of scope for this pass: custom block-entity renderers, EMC/price-table
changes, new textures, any in-game testing.