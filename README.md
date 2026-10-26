# EMC Casino

An EMC-based casino and gambling mod for Minecraft. Custom Minecraft-styled sprite-driven
UIs, six game machine blocks, multiplayer poker tables with server-side bots, and a
standalone ProjectE-style economy with the 0.8× / 1.2× exchange spread.

---

## Downloads

| Minecraft | Loader | Java | Artifact |
|---|---|---|---|
| **26.3** | Fabric ≥ 0.19.5 | 25 | `emc-casino-26.3-fabric-0.1.0.jar` |
| **1.20.1** | Fabric ≥ 0.14.21 | 17 | `emc-casino-1.20.1-fabric-0.1.0.jar` |
| **1.20.1** | Forge 47.3.0 | 17 | `emc-casino-1.20.1-forge-0.1.0.jar` |
| **1.20.1** | NeoForge 47.1.3 | 17 | `emc-casino-1.20.1-neoforge-0.1.0.jar` |

Built jars live in [`dist/`](dist/). Drop the matching one into `mods/`.

The 1.20.1 line is also packaged as an **Architectury multiloader** project in
[`mc1.20.1-arch/`](mc1.20.1-arch/) — one `common` source tree plus `fabric`, `forge`
and `neoforge` platform modules.

---

## The economy

The mod does **not** require ProjectE. It ships its own hardcoded EMC table and applies
ProjectE's asymmetric exchange rate:

| Direction | Multiplier | Formula |
|---|---|---|
| **Deposit** (cash items in) | **0.8×** | `floor(base × 0.8)` |
| **Withdraw** (buy items out) | **1.2×** | `ceil(base × 1.2)` |

So a diamond priced at 8192 EMC deposits for 6553 and costs 9831 to buy back — a
consistent 4:6 spread that keeps the table solvent no matter which way players trade.

### Editing values

Three ways, all live:

**1. Commands**

```
/casino balance [player]        view an EMC balance
/casino give <player> <amount>  grant EMC            (op 2)
/casino setemc <item> <value>   set a base EMC value (op 2)
/casino getemc <item>           inspect base / 0.8× / 1.2×
```

**2. Data pack** — durable, survives restarts, re-applied on every `/reload`:

```
data/<your_namespace>/emc/prices.json
```
```json
{ "minecraft:diamond": 9000, "minecraft:emerald": 20000 }
```

Every `data/*/emc/*.json` is merged over the built-in defaults, so a pack only lists
what it wants to change.

**3. KubeJS** — dynamic, for scripted servers:

```js
const EmcCasino = Java.loadClass('dev.emccasino.api.EmcKubeJsBridge')
EmcCasino.set('minecraft:diamond', 9000)
EmcCasino.remove('minecraft:dirt')
console.info(EmcCasino.deposit('minecraft:diamond'))  // 7200
```

Full method table and a working example script live in
[`examples/kubejs/`](examples/kubejs/).

---

## The games

Six placeable machine blocks, each with its own block entity:

| Block | Game | Notes |
|---|---|---|
| `emc_casino:roulette_table` | European Roulette | 0–36, straights, colours, dozens, odd/even |
| `emc_casino:blackjack_table` | Blackjack | Hit / stand / double, 3:2 naturals |
| `emc_casino:baccarat_table` | Punto Banco | Player / Banker (5% commission) / Tie at 8:1 |
| `emc_casino:poker_machine` | Video Poker | Jacks or Better draw, Royal Flush 800:1 |
| `emc_casino:pachinko_cabinet` | Pachinko | Simulated pegboard, multipliers to 100× |
| `emc_casino:texas_holdem_floor` | Texas Hold'em | 6 seats, blinds, side pots, bots |

### Multiplayer seating

The Texas Hold'em floor is a real server-side table. Players open the block, pick a
seat and buy in from their EMC balance; the buy-in is deducted on join and the
remaining stack refunded on leave. Seat state, turn order, pot and cards are broadcast
to every viewer of that block, so several players at one table stay in sync.

### Bots

Empty seats can be filled by server-side bots with distinct strategies:

| Personality | Behaviour |
|---|---|
| `TIGHT` | Folds marginal hands pre-flop, only continues with premium holdings |
| `BLUFF` | High pre-flop aggression, bets into perceived weakness |
| `CALLING_STATION` | Rarely folds, calls down to the river |
| `MANIAC` | Constant raises and all-ins |
| `BALANCED` | Mixed strategy weighting pot odds against hand strength |

---

## The UI

Every screen is drawn from **PNG sprites**, not hardcoded fills, so a resource pack can
completely reskin the mod without touching code:

```
assets/emc_casino/textures/gui/
├── casino_frame.png     256×256  cabinet body
├── header_bar.png       256×32   title strip
├── balance_bar.png      256×16   EMC balance strip
├── button.png           64×20    button normal
├── button_hover.png     64×20    button hover
├── chip.png             16×16    betting chip
├── roulette_wheel.png   64×64    wheel face
├── pachinko_board.png   128×128  pegboard
└── cards/
    ├── card_front.png   20×28
    └── card_back.png    20×28
```

### Dark mode

A built-in resource pack, **EMC Casino Dark Mode**, ships with the mod and can be
enabled in *Options → Resource Packs*. It overrides the same ten sprite paths with a
charcoal / obsidian / violet palette, so enabling it re-themes the whole mod with no
restart beyond the normal pack reload.

---

## Building from source

Each version directory is a standalone Gradle project with its own wrapper:

```
mc26.3/              Fabric, Minecraft 26.3, Java 25
mc1.20.1/            Fabric,  Minecraft 1.20.1, Java 17
mc1.20.1-forge/      Forge,   Minecraft 1.20.1, Java 17
mc1.20.1-neoforge/   NeoForge, Minecraft 1.20.1, Java 17
mc1.20.1-arch/       Architectury multiloader, Minecraft 1.20.1
```

```bash
cd mc1.20.1 && ./gradlew build
# -> build/libs/emc-casino-0.1.0.jar
```

`dist/` holds the assembled release jars for all four standalone targets.

---

## Notes on version specifics

- **26.3** is fully unobfuscated; the mod uses official Mojang names, so
  `ResourceLocation` is `Identifier` and screens implement
  `extractRenderState(GuiGraphicsExtractor, …)`.
- **1.20.1** still uses `ResourceLocation`, `GuiGraphics`, and the MCP-era
  ForgeGradle 6 toolchain for the Forge module.
- 26.3 resource packs declare `pack_format` 97 (with a `supported_formats` range);
  1.20.1 packs declare `pack_format` 15.
