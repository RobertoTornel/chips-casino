# PLAN-SLOT.md — Chips Casino: Slot Machine game (A 方案 plan)

**Project:** /home/mana/Documents/mcmod/emc-casino — five trees:
`mc26.3` (Fabric 26.3, Java 25), `mc1.20.1` (Fabric, Java 17),
`mc1.20.1-forge` (Forge, Java 17), `mc1.20.1-neoforge` (NeoForge, Java 17),
`mc1.20.1-arch/common` (Architectury multiloader, Java 17).
**Goal:** Add a SLOT MACHINE game (block + block entity + client screen + pure
math engine) to the Chips Casino mod, static verification only — no client
testing. This is the A-stage plan; the B stage (skeleton) implements from it.

**Mod id:** `casino`  |  **Package:** `dev.macasino`  |  **Block registry name:**
`slot_machine`  |  **Lang:** `block.casino.slot_machine = "Slot Machine"`.

---

## Constraints (iron rules, non-negotiable)

- NO in-game / client testing. All verification static: `mc_gradle build` (one
  task per projectDir) + JSON validity + PNG validity + gate scripts green.
- NO EMC references anywhere. Currency is Chips ◈; the price table is "just
  numbers". Zero `emc` strings, no `thermal`/`integrateddynamics` hooks.
- New Java files compile on ALL FIVE trees. Renderer code additive and
  defensive.
- Platform specifics:
  - `mc26.3`: `GuiGraphicsExtractor` + `Identifier` + normalized-UV blits +
    `CasinoActionPayload`/`CasinoSyncPayload`.
  - `mc1.20.1` trees: `GuiGraphics` + `ResourceLocation` +
    `drawString(...false)` + mouse `(double,double,int)` + `CasinoNetwork`
    raw `FriendlyByteBuf` packets.
- Networking: follow the EXISTING pattern per tree (copy Pachinko/Pinball/
  Exchange structure exactly). Do NOT invent a new network protocol.
- No new block-entity renderers. No new block states/variants — single variant
  `slot_machine`.
- All builds via `mc_gradle` (projectDir + single task), never `./gradlew`
  directly.
- The four 1.20.1 trees share identical render/screen code; `mc26.3` differs
  only in GUI API.
- Custom textures: generated statically with PIL, byte-identical across all
  five trees (asset_sync_check.py gates this).

---

## 1. Game design — engine math (pure Java, JVM-testable)

**Geometry:** 3 reels × 3 rows visible per reel. One shared reel strip of 20
positions (all reels identical → analytic RTP exact and testable).

**Symbols + weights (per strip position of 20):**
| Symbol | Weight | Share |
|---|---|---|
| CHERRY | 8 | 40% |
| LEMON  | 5 | 25% |
| BELL   | 4 | 20% |
| BAR    | 2 | 10% |
| SEVEN  | 1 | 5%  |

**Paylines:** centre line only (middle row of the window), bet = chips per spin.
No near-miss logic — pure weighted random, fair and published.

**Payout table (multiplier × bet):**
| Combination (centre line) | Multiplier |
|---|---|
| SEVEN SEVEN SEVEN | 200× |
| BAR BAR BAR | 50× |
| BELL BELL BELL | 18× |
| LEMON LEMON LEMON | 7× |
| CHERRY CHERRY CHERRY | 4× |
| exactly 2× CHERRY (any positions) | 1× |
| anything else | 0 |

**RTP (analytic, asserted by test):**
- P(3 SEVEN) = (1/20)³ → EV 200/8000 = 0.025
- P(3 BAR)   = (2/20)³ → EV 400/8000 = 0.050
- P(3 BELL)  = (4/20)³ → EV 1152/8000 = 0.144
- P(3 LEMON) = (5/20)³ → EV 875/8000 = 0.109375
- P(3 CHERRY)= (8/20)³ → EV 2048/8000 = 0.256
- P(exactly 2 CHERRY) = 3·(8/20)²·(12/20) = 0.288 → EV 0.288
- **Total EV = 0.872375 → RTP ≈ 87.24%, house edge ≈ 12.76%** ✓ (10–15% target)

**Determinism:** `spin(bet, seed)` constructs `new Random(seed)`; each reel stop
offset = `random.nextInt(20)`. Same seed → same result. Inject `Random` in the
constructor for testability.

---

## 2. Java files to ADD (per tree)

Common to all five trees (same package `dev.macasino.*`). Where a tree differs,
it is noted.

### 2.1 game/SlotMachineEngine.java (all 5 trees)
Pure math, zero Minecraft types.
- `public enum Symbol { CHERRY, LEMON, BELL, BAR, SEVEN }`
- `public static final int REELS = 3;`
- `public static final int ROWS = 3;`
- `public static final int REEL_LENGTH = 20;`
- `public static final int[] STRIP = { ... }` — 20 entries with the weight
  counts above (8 CHERRY, 5 LEMON, 4 BELL, 2 BAR, 1 SEVEN), fixed order.
- `public static final int[][] PAYOUT` — keyed by symbol triplet → multiplier;
  plus `cherryPairPays = 1`.
- Constructor `SlotMachineEngine()` and `SlotMachineEngine(Random random)`.
- `public SpinResult spin(long bet, long seed)` — deterministic, throws on bet
  < 0, returns `SpinResult`.
- `public static double expectedReturn()` — exact enumeration over all
  20³ = 8000 centre-line outcomes → 0.872375.
- `public static double houseEdge()` → `1 - expectedReturn()`.
- `public static final double EXPECTED_RETURN = expectedReturn();`
- Inner `public static class SpinResult { public final int[][] window; // [reel][row]
  public final int[] stopOffset; public final long bet; public final long payout;
  public final boolean won; }`

### 2.2 game/SlotMachineEngineTest.java (all 5 trees)
JUnit 5, plain JVM (no Minecraft classes on classpath → safe).
- `expectedReturnIsBelowOne` — `EXPECTED_RETURN < 1.0`.
- `houseEdgeIsReasonable` — edge in (0.02, 0.25), assert ~0.1276.
- `expectedReturnMatchesEnumeration` — `expectedReturn()` == 0.872375 (tol 1e-9).
- `payoutTableSane` — multipliers non-negative, ≤ 500.
- `spinIsDeterministicWithSeed` — two engines, same seed, 1000 spins → equal
  windows and payouts.
- `payoutMatchesTable` — for a fixed seed, each spin's payout equals the table
  value for its centre line (recompute from window).
- `zeroBetPaysNothing`, `negativeBetRejected`, `everySymbolReachable` over
  many spins, `windowBounds` (symbols in 0..4, 3×3 shape).

### 2.3 block/SlotMachineBlock.java (all 5 trees)
```java
public class SlotMachineBlock extends BaseCasinoBlock {
    public SlotMachineBlock() { super("slot_machine"); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SlotMachineBlockEntity(pos, state);
    }
}
```
Extends `BaseCasinoBlock` (existing, already in every tree). One-line-per-tree
identical except the import path is the same (`dev.macasino.block.*`).

### 2.4 block/entity/SlotMachineBlockEntity.java (all 5 trees)
Extends `BaseCasinoBlockEntity` (existing). Pattern: copy PachinkoBlockEntity
structure, slot-specific actions.
- `private final SlotMachineEngine engine = new SlotMachineEngine();`
- `private long pendingBet = 20;` (default bet)
- `private boolean spinning;`
- `private int[][] lastWindow;` (3×3 symbol ordinals)
- `private long lastPayout = 0; private boolean lastWon = false;`
- `public SlotMachineBlockEntity(BlockPos pos, BlockState state)` →
  `super(Casino.SLOT_BE_TYPE, pos, state)` (26.3/Architectury) or
  `super(Casino.SLOT_BE_TYPE, pos, state)` — see tree notes below for the
  field name used per tree (`PACHINKO_BE_TYPE` analog).
- `getGameType()` → `"SLOT"`.
- `serializeState(ServerPlayer)` → JSON:
  `{"gameType":"SLOT","spinning":..., "bet":..., "lastPayout":...,
   "lastWon":..., "window":[[...],[...],[...]]}`.
- `handlesAction(action)` → true for `BET`, `SPIN`, `STOP_REEL`, else false.
- `handleGameAction(player, action, data)`:
  - `BET`: parse `{"bet":N}`, validate > 0, store `pendingBet` (no balance
    change yet), `syncTo(player)`.
  - `SPIN`: require `pendingBet > 0`, `CasinoAccountManager.deductBalance`,
    `engine.spin(pendingBet, seed)` (seed = `System.nanoTime()` XOR player UUID
    hash — server-side only, fine), grant `payout` if > 0, set spinning=false,
    `syncTo(player)`. On any failure, refund-safe: do not deduct unless spin
    succeeds; catch exceptions, leave state unchanged.
  - `STOP_REEL`: acknowledge (spin is instant; set spinning=false), `syncTo`.
- Defensive: malformed JSON → ignore, never throw out of `handleGameAction`.

### 2.5 client/gui/SlotMachineScreen.java (all 5 trees)
Extends `BaseCasinoScreen`. **Two API variants** (26.3 = GuiGraphicsExtractor;
1.20.1 trees = GuiGraphics). Layout all inside `contentX()`/`contentW()`
interior; passes `layout_check.py`.

`bgWidth = 340`, `bgHeight = 240` (same as Pachinko; fits min window).
Fields: `betAmount = 20`, `spinning`, `window` (3×3 ints), `lastPayout`,
`lastWon`, `reelOffset` (int[] of 3 for visual roll).

`initGameWidgets()` (positions relative to panel origin; contentX() = L+24,
contentY() = T+36):
1. Bet label text: `drawString("Bet: §e" + betAmount + " Chips", contentX(),
   contentY() + 2, COLOR_TEXT_WHITE)` — text, not a widget (no rect).
2. Bet buttons row at `contentY() + 22`:
   - "10": (contentX(), +22, 26, 16)
   - "20": (contentX()+30, +22, 26, 16)
   - "50": (contentX()+60, +22, 30, 16)
   - "100": (contentX()+94, +22, 34, 16)
   → max x2 = L+24+94+34 = L+152 < inner_right (L+320) ✓
3. SPIN button (primary): (contentX(), contentY()+46, 110, 24) → x2 = L+134,
   y2 = T+106 < contentBottom (T+216) ✓
   Action: `sendAction("SPIN", {"bet":betAmount})`.
   Disabled while `spinning` (SpriteButton disabled state dims the sprite).

`onStateUpdated()`: parse `lastStateJson` → spinning/window/lastPayout/lastWon
/betAmount (defensive try/catch, leave defaults on failure).

`renderGameContent(...)`:
- Draw 3 reel strips on the right half: reel `i` at
  `contentX() + 150 + i*44`, width 40, height 92 (from contentY()+18).
  Blit `CasinoSprites.SLOT_REEL_WINDOW` behind the three strips as one
  rounded-rect backdrop (single blit, resource-pack overridable).
- Per reel, 3 symbol sprites (16×16) at rows contentY()+22, +50, +78,
  x = reelX + 12 (centered in 40-wide strip). Sprite chosen by
  `slot_symbol_<name>`; while `spinning` advance `reelOffset[i]` with
  `partialTick` so reels appear to roll (visual only; server state is
  authoritative).
- Payout banner: if `lastWon` → green text
  `§aSlot win +<payout> Chips!` at (contentX(), contentY()+114), else red
  `§cNo win.` when spinning finished and lastPayout == 0.
- Bet display + balance (balance already drawn by base header).

**26.3 variant:** `renderGameContent(GuiGraphicsExtractor extractor, ...)`;
`extractor.blit(identifier, x, y, w, h, u0, v0, u1, v1)` with normalized UVs;
`extractor.text(this.font, ..., color)`. `CasinoSprites.SLOT_REEL_WINDOW` is
an `Identifier`.

**1.20.1 variant:** `renderGameContent(GuiGraphics graphics, ...)`;
`blitTexture(graphics, resourceLocation, x, y, w, h, texW, texH)` (existing
base helper); `graphics.drawString(this.font, ..., color, false)`.

**layout_check.py arithmetic (must pass):**
- Widget rects: all 4 bet buttons + spin button inside [20, 320]×[0, 216]
  (inner box), no overlaps (bet row y 58–74, spin y 82–106; x ranges disjoint
  from reel area which is blits, not widgets). ✓
- Text y in [6, 208]: bet label T+38 ✓, banner T+150 ✓. x in [4, 320] ✓.
- Panel 340+8 = 348 ≤ 427 min width; 240+8 = 248 > 240 min height!
  ⚠️ Pachinko uses bgHeight 230 → 238 ≤ 240. Use **bgHeight = 230** (not 240)
  so `bgh + FRAME_OVERHANG = 238 ≤ MIN_WINDOW_H 240` ✓.
  contentBottom = T + 230 − 24 − 4 = T + 202. Spin button y2 = T+106 ✓,
  banner T+150 ✓ within [6, 194] text band. Reel area ends contentY()+110 =
  T+146 < contentBottom ✓.

---

## 3. JSON / data per tree (identical content, all 5 trees)

### 3.1 assets/casino/blockstates/slot_machine.json (all 5 trees)
```json
{ "variants": { "": { "model": "casino:block/slot_machine" } } }
```

### 3.2 assets/casino/models/block/slot_machine.json (all 5 trees)
Rich 3D cabinet, single variant. Textures declared: `particle`, `face`,
`side`, `top` → `casino:block/slot_machine`, `casino:block/slot_machine_side`,
`casino:block/slot_machine_top` (new custom textures; face = cabinet front art).
Elements (all within [-16, 32]):
| Element | from | to | Faces |
|---|---|---|---|
| plinth | [0,0,0] | [16,2,16] | up→top; others→side |
| body | [1,2,1] | [15,14,15] | up/down→top/side; north→face; S/E/W→side |
| cap | [0,14,0] | [16,16,16] | all→top |
| marquee_box | [1,16,1] | [15,19,15] | up→top; north→face; others→side |
| marquee_light | [2,16,2] | [14,19,14] | up/down→top; N/S/E/W→top (glow strip) |
| reel_window_bezel | [2,4,-0.5] | [14,11,1.5] | north→face; E/W→side; up/down→top |
| reel_window_glass | [3,5,0.5] | [13,10,0.51] | north/south→face (the "screen") |
| controls | [2,2,-1] | [14,4,1] | up→top; down→side; north→face; E/W→side |
| button_a/b/c | small boxes on controls panel | →side/top/face |
Max coordinate 19 ≤ 32 ✓; min −1 ≥ −16 ✓. All declared textures exist as
shipped PNGs (byte-identity across trees enforced by asset_sync_check.py).

### 3.3 assets/casino/models/item/slot_machine.json (all 5 trees)
```json
{ "parent": "casino:block/slot_machine",
  "display": { /* gui/ground/firstperson/thirdperson transforms, scale ~0.5 */ } }
```

### 3.4 assets/casino/lang/en_us.json (all 5 trees)
Add line: `"block.casino.slot_machine": "Slot Machine"`.
Zero `emc` strings — verify by grep.

---

## 4. Custom textures (PIL-generated, byte-identical across all 5 trees)

Generated once with a deterministic Pillow script, then copied byte-identical
into all five trees under `src/main/resources/assets/casino/textures/`.
`asset_sync_check.py` covers both `textures/block` and `textures/gui`, so these
new files are included in the check.

| File | Size | Description |
|---|---|---|
| `block/slot_machine.png` | 16×16 | Cabinet front face: dark wood bevel, brass trim, "SLOT" marquee hint |
| `block/slot_machine_side.png` | 16×16 | Cabinet side: wood grain + brass edge |
| `block/slot_machine_top.png` | 16×16 | Cabinet top: brass lacquer (brightest existing texture tone) |
| `gui/slot_reel_window.png` | 96×96 (or similar) | Reel display backdrop: dark glass panel with brass frame |
| `gui/slot_symbol_cherry.png` | 16×16 | Cherry symbol |
| `gui/slot_symbol_lemon.png` | 16×16 | Lemon symbol |
| `gui/slot_symbol_bell.png` | 16×16 | Bell symbol |
| `gui/slot_symbol_bar.png` | 16×16 | BAR symbol |
| `gui/slot_symbol_seven.png` | 16×16 | SEVEN symbol |

All binary alpha (A∈{0,255}), palette-locked to the light palette from
ART_PIPELINE.md (§4), 1-light-direction bevels. 5 symbol textures + reel window
+ 3 block textures = 9 new PNGs per tree, 45 total, all byte-identical.

---

## 5. Networking — follow EXISTING pattern per tree

The slot reuses the existing `action`/`sync`/`open_screen` channels. No new
packets.

### mc26.3 (Fabric 26.3)
- `Casino.java`: register `SLOT_MACHINE_BLOCK`, `SLOT_MACHINE_BE_TYPE`, block
  item `slot_machine`. BE type registered with `Set.of(SLOT_MACHINE_BLOCK)`.
- `CasinoClient.java`: add `case "SLOT" -> new SlotMachineScreen(pos, state,
  bal)` to the `OpenCasinoScreenPayload` receiver switch. `SLOT` gameType string
  matches `BlockEntity.getGameType()`.
- `SlotMachineBlockEntity.syncTo` → `ServerPlayNetworking.send(player, new
  CasinoSyncPayload(...))` (already in BaseCasinoBlockEntity, no edit needed).
- `BaseCasinoBlock.useWithoutItem` already sends `OpenCasinoScreenPayload` — no
  edit needed.

### mc1.20.1 (Fabric)
- `Casino.java`: `Registry.register(BuiltInRegistries.BLOCK, id("slot_machine"),
  SLOT_MACHINE_BLOCK)`; BE type; block item.
- `CasinoClient.java`: add `case "SLOT"` to both receivers' switches (open
  screen + sync). Packets via `CasinoNetwork.sendSync`/`sendAction` (FriendlyByteBuf).

### mc1.20.1-forge / mc1.20.1-neoforge
- `Casino.java`: `BLOCKS.register("slot_machine", ...)`; `BLOCK_ENTITIES.register`;
  `ITEMS.register` block item.
- `client/ClientPacketHandler.java`: add `case "SLOT" -> new SlotMachineScreen(...)`
  to `openScreen` switch. `updateState` needs no change (generic).

### mc1.20.1-arch/common
- Common `Casino.java` (Architectury `DeferredRegister`): add `SLOT_MACHINE_BLOCK`,
  `SLOT_MACHINE_BE_TYPE`, block item; `Casino.init()` registers blocks → BEs →
  items.
- `common/src/main/java/dev/casino/client/CasinoClientNetwork.java`: add
  `case "SLOT"` to the `openScreen` switch (Architectury `NetworkManager` S2C
  handler; shared by Fabric/Forge/NeoForge loader subprojects).
- `BaseCasinoBlockEntity.syncTo` → `CasinoNetwork.sendSync(...)` (already shared;
  no edit needed).

**Game type string:** `"SLOT"` everywhere — `serializeState` emits it, client
switches on it.

---

## 6. Casino.java edits per tree (registration)

| Tree | Edits |
|---|---|
| mc26.3 | Add fields `SLOT_MACHINE_BLOCK`, `SLOT_MACHINE_BE_TYPE`; `registerBlock("slot_machine", ...)`; BE `Registry.register(...)`; block item via `registerBlock` (creates item too). |
| mc1.20.1 (Fabric) | Same as 26.3 but direct `Registry.register` + `new BlockItem(...)`. |
| mc1.20.1-forge | `BLOCKS.register("slot_machine", SlotMachineBlock::new)`; `BLOCK_ENTITIES.register`; `ITEMS.register` block item. |
| mc1.20.1-neoforge | Same as forge. |
| mc1.20.1-arch/common | `BLOCKS.register("slot_machine", SlotMachineBlock::new)`; `BLOCK_ENTITIES.register` via `makeType(...)`; `ITEMS.register` block item. |

No `emc` strings anywhere; lang key added separately.

---

## 7. Tree matrix — which files go where

| File | mc26.3 | mc1.20.1 | mc1.20.1-forge | mc1.20.1-neoforge | mc1.20.1-arch/common |
|---|---|---|---|---|---|
| `game/SlotMachineEngine.java` | src/main/java | src/main/java | src/main/java | src/main/java | common/src/main/java |
| `game/SlotMachineEngineTest.java` | src/test/java | src/test/java | src/test/java | src/test/java | common/src/test/java |
| `block/SlotMachineBlock.java` | src/main/java | src/main/java | src/main/java | src/main/java | common/src/main/java |
| `block/entity/SlotMachineBlockEntity.java` | same | same | same | same | common |
| `client/gui/SlotMachineScreen.java` | GuiGraphicsExtractor variant | GuiGraphics variant | GuiGraphics variant | GuiGraphics variant | GuiGraphics variant |
| `Casino.java` (registration) | edit | edit | edit | edit | edit (DeferredRegister) |
| Client screen switch | `CasinoClient.java` | `CasinoClient.java` | `ClientPacketHandler.java` | `ClientPacketHandler.java` | `CasinoClientNetwork.java` |
| `blockstates/slot_machine.json` | resources | resources | resources | resources | common/resources |
| `models/block/slot_machine.json` | same | same | same | same | common |
| `models/item/slot_machine.json` | same | same | same | same | common |
| `lang/en_us.json` (+slot_machine) | same | same | same | same | common |
| `textures/block/slot_machine{,_side,_top}.png` | byte-identical copy | byte-identical copy | byte-identical copy | byte-identical copy | byte-identical copy |
| `textures/gui/slot_reel_window.png` + 5 `slot_symbol_*.png` | same ×5 | same ×5 | same ×5 | same ×5 | same ×5 |

The four 1.20.1 trees' screen code is identical; only `mc26.3` uses the
`GuiGraphicsExtractor`/`Identifier` variant.

---

## 8. Verification plan (static gates only)

1. **JSON validity + model bounds** — one-off Python check over the new model:
   `json.load`, every element `from`/`to` component in [-16, 32], every texture
   declared in the model's `textures` block exists as a shipped file in all 5
   trees, face `uv` arrays have 4 numbers. (Pre-build gate; not shipped.)
2. **PNG validity** — all 9 new PNGs per tree load via Pillow, mode RGBA,
   binary alpha, sizes as specified; byte-identical md5 across the 5 trees.
3. **`python3 tools/layout_check.py <5 src roots>`** — must be 41/40 (adds
   SlotMachineScreen as a leaf screen; it is a leaf, no overlap, inside panel).
4. **`python3 tools/asset_sync_check.py`** — all textures identical across all
   5 trees, including the 9 new slot textures → green.
5. **`mc_gradle build` (single task) per tree** — Java compiles on all five
   trees; JUnit 5 tests run (`test` task): `SlotMachineEngineTest` +
   existing `PachinkoEngineTest`, `PinballEngineTest`, `PricingRulesTest` green.
6. **`python3 tools/verify_jars.py`** against `dist/*.jar` — update `BLOCKS`
   to include `slot_machine` (+3 checks/jar: blockstate, model, texture),
   `SCREENS` to include `SlotMachineScreen`, block texture `slot_machine` +
   GUI sprites `slot_reel_window`, `slot_symbol_*`. Target: 371+ PASS / 0 FAIL.
7. **No-`emc` grep** — `grep -ri emc` over `src/` returns no hits in Java or
   resources (lang/prices).

**Out of scope:** custom block-entity renderers (renderer APIs diverge), new
block states, in-game testing.

---

## 9. Notes for B stage (skeleton)

- Scaffold nothing: all five trees already exist. Add files into the existing
  source roots per the tree matrix.
- `SlotMachineScreen` must not reference Minecraft client types in its field
  declarations that would break the dedicated-server class scan
  (`verify_jars.py` client-leak check only inspects server classes, but keep
  screen code strictly in `client/gui`).
- `SlotMachineBlockEntity` must not import `net.minecraft.client.*`.
- The `SLOT` gameType string is the contract between BE (`serializeState`) and
  each tree's client screen switch — keep it identical (`"SLOT"`).
- Texture generation: write one PIL script, run it once, copy the 9 PNGs
  byte-identical into all 5 trees, then run `asset_sync_check.py` before any
  build.
- `verify_jars.py` `BLOCKS`/`SCREENS` lists must be updated in all trees'
  copies of the script (it lives at repo root `tools/verify_jars.py`, single
  copy run against all jars).
