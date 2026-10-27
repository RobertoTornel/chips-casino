# PLAN-FLAIR.md — "Wild Ass Shit" All-Items / All-UI / All-Everything Flair Pass

**Project:** /home/mana/Documents/mcmod/emc-casino — 5 trees
(mc26.3 Fabric Java25, mc1.20.1 Fabric/Forge/NeoForge Java17,
mc1.20.1-arch/common Architectury Java17).

**Goal:** Maximum flair. Every item, every UI, every block gets a custom,
cool, distinct look. No flat blocks, no boring menus, no generic textures.
Static verification only — no client testing. All builds via mc_gradle.
All 5 trees must compile and pass all gates.

## Constraints (iron, non-negotiable)
- No client / in-game testing ever. Java compiles via mc_gradle; JSON/PNG
  validated statically; gate scripts green.
- Zero `emc` references anywhere. Currency is Chips ◈.
- New Java compiles on ALL FIVE trees. Renderer code additive and defensive.
- No new block-entity renderers (loader APIs diverge).
- All textures byte-identical across the 5 trees (asset_sync_check.py).
- All models within [-16, 32] element bounds; textures declared & exist.
- Blockstates, model names, texture names stay consistent → verify_jars.py.

---

## Pass 1 — Items (every block item gets a custom 3D item model + glint)

**Scope:** All 9 block items: roulette_table, blackjack_table, baccarat_table,
poker_machine, pachinko_cabinet, pinball_machine, texas_holdem_floor,
exchange, slot_machine.

**Per item (in models/item/<name>.json):**
- Keep `"parent": "casino:block/<name>"`
- Add `display` block tuned per silhouette:
  - `gui`: jaunty angle so the 3D model reads as an item, not a flat tile
  - `firstperson_righthand/lefthand`: held-in-hand reads as a miniature machine
  - `thirdperson_righthand/lefthand`: hip/back carry
  - `ground`: dropped item on the floor
  - `fixed`: inventory frame
- Table silhouettes (roulette, blackjack, baccarat, texas_holdem_floor):
  gui rotation [45, 225, 0], scale 0.5 — felt top visible
- Cabinet silhouettes (poker_machine, pachinko_cabinet, pinball_machine,
  slot_machine): gui rotation [35, 220, 0], scale 0.55 — marquee toward player
- Exchange (tall counter): gui rotation [40, 230, 0], scale 0.5

**Item "glint" (enchantment glint):**
- Item models do NOT support animated glint without custom code. Skip true
  animated glint; instead add a "highlight" element to block models that reads
  as a lit/premium surface (brass trim, lit marquee). That's the visual signal
  of value.

**Lang:** Every block gets a description-flavored entry:
- block.casino.slot_machine = "Slot Machine"
- item.casino.slot_machine = "One-Armed Bandit" (or keep block name for items)
- Add `item.casino.<name>` flavor entries where distinct from block name.
- Zero `emc` strings.

**Creative tab:** Register a custom CreativeModeTab "Chips Casino" containing all
9 block items, with the slot machine as the tab icon. (Additive Java; compiles on
all 5 trees.)

---

## Pass 2 — UIs (every screen gets a distinct, cool theme)

**Scope:** 10 screens: BaseCasinoScreen (shared chrome), Roulette, Blackjack,
Baccarat, VideoPoker, Pachinko, Pinball, TexasHoldem, Exchange, SlotMachine.

**Shared chrome (BaseCasinoScreen):**
- Keep neon marquee rail + CHIPS ◈ label (already added).
- Add a subtle "chip stack" decoration in corners of the panel.
- Header gets a thinner inner glow line.
- Balance bar gets a metallic bevel edge.

**Per-screen color themes (additive, defined as constants):**
- Roulette: red/gold (COLOR_ROULETTE = 0xFFB32630 accents)
- Blackjack: green felt (COLOR_BLACKJACK = 0xFF17603A)
- Baccarat: navy/gold (COLOR_BACCARAT = 0xFF1A3A63)
- VideoPoker: purple (COLOR_VIDEOPoker = 0xFF8B5CF6)
- Pachinko: pink/red (COLOR_PACHINKO = 0xFFD8454E)
- Pinball: yellow/blue (COLOR_PINBALL = 0xFFF0C55A)
- TexasHoldem: leather/brown (COLOR_HOLDEM = 0xFF4A3423)
- Exchange: brass/gold (COLOR_EXCHANGE = 0xFFB8862C)
- SlotMachine: neon cyan/magenta (COLOR_SLOT = 0xFF00E5FF / 0xFFFF00FF)

**Layout rules (must pass layout_check.py):**
- All widgets inside [contentX(), contentX()+contentW()] × [contentY(), contentBottom()]
- No overlaps between widgets
- Text y within [6, contentBottom−8]
- bgHeight such that bgHeight+8 ≤ 240 (min window height): keep ≤ 232

**SlotMachineScreen specific (already added):**
- Reel strips with symbol sprites (already implemented)
- Bet buttons + SPIN button
- Payout banner

**No new client renderers.** All UI changes are additive `fill`/`text`/`blit`
calls in existing `renderGameContent` methods.

---

## Pass 3 — Textures (custom art for everything, no flat colors)

**New block textures (generated with gen_casino_art.py, added to BLOCK_ASSETS):**
- Each machine already has its own 16x16 face texture. Keep them.
- casino_body_side, casino_body_top already exist.

**New GUI textures (added to GUI_ASSETS):**
- `gui/slot_spin_button.png` (64x20) — distinct spin button sprite
- `gui/slot_reel_strip.png` (16x64) — single reel column for animation frames
- Per-game header accents could reuse existing button sprites.

**Generation:** One deterministic Pillow script run writes all 9 new PNGs
byte-identical into all 5 trees. asset_sync_check.py verifies identity.

---

## Pass 4 — Lang & descriptions

- Add `item.casino.<name>` entries for all 9 items with one-line flavor.
- Add `itemGroup.casino` = "Chips Casino" for the creative tab.
- Zero `emc` strings.

---

## Pass 5 — Registration (all 5 trees)

- `Casino.java`: add SLOT_MACHINE_BLOCK/BE_TYPE (already done), creative tab
  field, register tab.
- Client screen switch: SLOT case (already done in all 5 trees).

---

## Verification gates (all must pass)
1. `tools/layout_check.py <5 src roots>` → 10/10 screens (adds SlotMachineScreen)
2. `tools/asset_sync_check.py` → all textures identical across 5 trees
3. `tools/verify_jars.py` → 371+ PASS / 0 FAIL
4. `mc_gradle build` per tree → compile + all unit tests green
5. `grep -ri emc src/` → 0 hits

---

## Subagent delegation (A→B→C→D)
- **A (plan):** This file. Done by me.
- **B (skeleton):** subagent_mc_skeleton — adds empty stubs for creative tab,
  new textures registrations, ensuring all 5 trees have the same file list.
- **C (content):** THREE parallel subagents:
  - C-items: item models + glint elements + lang entries
  - C-ui: screen theme constants + per-screen render additions
  - C-textures: gen_casino_art.py additions + new PNGs
- **D (verify):** subagent_mc_verify — builds all 5 trees, runs all gates.
- **Critique:** After D, a separate critique subagent reviews the diff for
  missed flair opportunities and consistency, then we fix the top 3 findings.

---

## Tree matrix
| Change | mc26.3 | mc1.20.1 | mc1.20.1-forge | mc1.20.1-neoforge | mc1.20.1-arch/common |
|---|---|---|---|---|---|
| Item models ×9 | models/item | models/item | models/item | models/item | common/models/item |
| Block models ×9 | models/block | ...same... | ... | ... | common/models/block |
| Textures (new PNGs) | textures/ | ... | ... | ... | common/textures/ |
| BaseCasinoScreen chrome | GuiGraphicsExtractor | GuiGraphics ×4 | ... | ... | common |
| Per-screen themes | per screen ×10 | per screen ×10 | ... | ... | common |
| Creative tab | Casino.java | Casino.java | ... | ... | common |
| Lang | en_us.json ×5 | ... | ... | ... | common |

---

**Output:** PLAN-FLAIR.md + a 5-line summary returned to the user. Execution
starts at B stage on approval.
