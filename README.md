# Chips Casino

A casino and gambling mod for Minecraft: nine playable tables and machines, a real
money economy (Chips ◈), and server-side bots so the tables are never empty.

## What it does

Chips Casino adds a fully-fledged casino to your world with no economy mod required:

* **Blackjack** — six seats, one shared shoe, one dealer. Hit, stand, double, split. The
  dealer's hole card is stripped from the sync packet until it is turned.
* **Baccarat** — six bettors, full Punto Banco tableau (naturals, player draw 0-5, banker
  third-card table including the 3-vs-8 exception), 5% banker commission, 8:1 tie.
* **European Roulette** — single-zero wheel, SecureRandom spin the client never sees until
  settle, inside/outside bets, chip denominations, felt grid.
* **Texas Hold'em Poker** — six seats, blinds, betting rounds, showdown, hole cards hidden
  until showdown.
* **Video Poker** — Jacks or Better hold/draw against the machine.
* **Pachinko** — Galton board with weighted pockets, binomial landing distribution.
* **Pinball** — skill-based ball launcher, RTP curve under 1.0 even at perfect play.
* **Slot Machine** — three weighted reels, server-owned SecureRandom seed.
* **Exchange** — buy and sell items at 0.8x / 1.2x of base price, datapack-reloadable.

Every table game is multiplayer: shared shoe/wheel/dealer, one animation clock, server-owned
outcomes, and house bots fill empty seats so a lone player still gets a full table.

## Why download it

* **No economy plugin needed.** Chips is a standalone wallet — sell items to the house at
  0.8x base, buy back at 1.2x, and the house edge means no trade loop can come out ahead.
  Money enters the world only by winning it at the tables.
* **Server-authoritative.** The server owns the RNG, the shoe, and every outcome; clients
  never see hidden cards or the winning number until it is revealed. No client can cheat.
* **Cross-platform.** Ships for Minecraft 26.3 (Fabric) and 1.20.1 (Fabric, Forge,
  NeoForge, and Architectury multiloader) — same features on every loader.
* **Creative Mode Tab.** "Chips Casino" tab with the slot machine icon and all nine block
  items.
* **Themable UI.** Every screen element is a resource-pack-overridable sprite, including a
  built-in dark mode pack.

## Critical info before downloading

* **Real money.** Bets are deducted from your wallet the moment they are placed; winnings
  are credited at settle. There is no creative-mode free play.
* **Server-side persistence.** Balances are saved to `<world>/casino/wallets.json` on every
  transaction and on server stop. Back up your world folder to back up your wallet.
* **House edge.** Every machine returns less than the stake over time (Pachinko 0.891,
  Pinball 0.920, Slot Machine calibrated ~12.8% edge, table games per standard odds). The
  casino always wins in the long run — that is by design.
* **Version support.** 26.3 requires Java 25; 1.20.1 requires Java 17. The Architectury
  jar is the recommended 1.20.1 artifact as it covers Fabric, Forge, and NeoForge in one
  download.

---

## Downloads

### Architectury multiloader build (the recommended 1.20.1 artifact)

| Minecraft | Loader | Java | Artifact |
|---|---|---|---|
| **1.20.1** | Fabric ≥ 0.14.21 | 17 | [`architectury/casino-1.20.1-arch-fabric-0.1.9.jar`](dist/architectury/casino-1.20.1-arch-fabric-0.1.9.jar) |
| **1.20.1** | Forge 47.x | 17 | [`architectury/casino-1.20.1-arch-forge-0.1.9.jar`](dist/architectury/casino-1.20.1-arch-forge-0.1.9.jar) |
| **1.20.1** | NeoForge 47.1.x | 17 | [`architectury/casino-1.20.1-arch-neoforge-0.1.9.jar`](dist/architectury/casino-1.20.1-arch-neoforge-0.1.9.jar) |

One `common` source tree in [`mc1.20.1-arch/`](mc1.20.1-arch/), packaged per platform.

### Standalone per-version builds

| Minecraft | Loader | Java | Artifact |
|---|---|---|---|
| **26.3** | Fabric ≥ 0.19.5 | 25 | [`casino-26.3-fabric-0.1.9.jar`](dist/casino-26.3-fabric-0.1.9.jar) |
| **1.20.1** | Fabric ≥ 0.14.21 | 17 | [`casino-1.20.1-fabric-0.1.9.jar`](dist/casino-1.20.1-fabric-0.1.9.jar) |
| **1.20.1** | Forge 47.3.0 | 17 | [`casino-1.20.1-forge-0.1.9.jar`](dist/casino-1.20.1-forge-0.1.9.jar) |
| **1.20.1** | NeoForge 47.1.3 | 17 | [`casino-1.20.1-neoforge-0.1.9.jar`](dist/casino-1.20.1-neoforge-0.1.9.jar) |

Either set works — the Architectury and standalone jars carry the same features.

---

## Money

The mod ships its own wallet. No economy mod or plugin is required, and nothing here
depends on one — the balance is a plain long per player, saved in the world at
`<world>/casino/wallets.json`.

The API is intentionally shaped like the economy services server owners already know, so
it reads familiarly and can be bridged to an external economy later without touching any
call sites:

```java
Wallet.getBalance(uuid)            // current balance
Wallet.has(uuid, amount)           // can they afford it
Wallet.deposit(uuid, amount)       // add money
Wallet.withdraw(uuid, amount)      // take money; false and no change if short
Wallet.transfer(from, to, amount)  // atomic player-to-player
Wallet.setBalance(uuid, amount)    // admin
Wallet.createAccount(uuid)         // explicit account creation
```

Currency name (`Chips`) and symbol (`◈`) are constants on `Wallet`, so rebranding is a
one-line change.

### Commands

```
/casino balance [player]         your balance, or someone else's
/casino wallet set <p> <n>       set a balance          (op 2)
/casino wallet take <p> <n>      remove money           (op 2)
/casino wallet reset <p>         wipe a wallet          (op 2)
/casino give <player> <amount>   grant money            (op 2)
/casino sell hand                sell what you're holding
/casino buy <item> [count]       buy from the house
/casino price <item>             inspect base / buy / sell
/casino setprice <item> <n>      set a base price       (op 2)
/casino reload                   reload prices from disk
```

---

## The house exchange

Two rates, both derived from the same base price:

| Direction | Multiplier | Formula | On an 8192-base item |
|---|---|---|---|
| **House buys** (you sell) | **0.8×** | `floor(base × 0.8)` | 6553 |
| **House sells** (you buy) | **1.2×** | `ceil(base × 1.2)` | 9831 |

Sell-then-buy and buy-then-sell both lose money at every base value — there is no
arbitrage. The 33.3% edge is what makes the machines matter: you cannot grind items into
profit, only win it.

The **Exchange block** (`casino:exchange`) is the physical shop. It shows both columns side
by side so the spread is visible at a glance.

### Editing base prices

Three ways, all live, none of them require restarting:

**1. Command** — `/casino setprice minecraft:diamond 12000`

**2. Datapack** — drop a file at `data/<your_pack>/prices/values.json`:

```json
{
  "values": {
    "minecraft:diamond": 8192,
    "minecraft:emerald": 16384,
    "mymod:custom_thing": 500
  }
}
```

Re-applied on every `/reload`, and it persists across restarts.

**3. KubeJS** — see [`examples/kubejs/`](examples/kubejs/):

```js
const Casino = Java.loadClass('dev.casino.api.PriceKubeJsBridge')
Casino.set('minecraft:diamond', 12000)
```

KubeJS values last for the session; use a datapack for anything permanent.

---

## The machines

| Block | Game | Players |
|---|---|---|
| `casino:roulette_table` | Roulette — number, colour and split bets | 1 |
| `casino:blackjack_table` | Blackjack — one dealer, six seats, hit/stand/double/split | 1–6 |
| `casino:baccarat_table` | Baccarat — player, banker, tie | 1–6 |
| `casino:poker_machine` | Video poker — hold/draw, Jacks or Better | 1 |
| `casino:pachinko_cabinet` | Pachinko — drop a ball, a Galton board decides | 1 |
| `casino:pinball_machine` | Pinball — fire a ball, time your flippers | 1 |
| `casino:texas_holdem_floor` | Texas Hold'em — full table, seats up to 6 | 1–6 |
| `casino:slot_machine` | Slot machine — three reels, weighted symbols | 1 |
| `casino:exchange` | The house exchange — sell and buy items | — |

### House edge

Every machine is tuned to return less than the stake over time, and the number is asserted
in a test rather than eyeballed. Pachinko returns **0.891** (10.9% edge); pinball returns
**0.92** even against a player with perfect flipper timing, and **0.26** for someone who
never times a flip. See [ECONOMY.md](ECONOMY.md) for the payout tables and the derivation —
including the bug that once had the pachinko board returning 54x per bet.

### Multiplayer

Texas Hold'em, Blackjack, Baccarat and Roulette all seat up to six around a shared table.
Hold'em deducts a buy-in when you sit and refunds it if you leave before the hand resolves;
blackjack charges each stake as you commit it and pays the table out at settle; baccarat
lets each bettor back player, banker or tie against one shared shoe; roulette lets each
bettor lay chips on the felt during the betting window and the wheel lands for everyone at
once. Every viewer of a table is kept in sync, so spectators see the same state as the
players — and in blackjack the dealer's hole card is withheld from the sync packet itself
until it is turned, not just hidden on screen.

### Bots

Empty tables are filled by server-side bots with five personalities — **TIGHT**, **BLUFF**,
**CALLING_STATION**, **MANIAC** and **BALANCED** — each pacing its decisions so the table
feels occupied rather than instant. Bots run on the server, so they work in singleplayer
and on a dedicated server alike, and they never need a client mod.

---

## Theming

Every element of every GUI is a texture under `assets/casino/textures/gui/`, so a resource
pack can restyle the entire mod without touching code.

| Sprite | Size | Used for |
|---|---|---|
| `casino_frame.png` | 256×256 | the cabinet panel frame |
| `header_bar.png` | 256×32 | title strip |
| `button.png` / `button_hover.png` | 64×20 | buttons |
| `balance_bar.png` | 256×16 | the money strip |
| `chip.png` | 16×16 | poker chip |
| `roulette_wheel.png` | 64×64 | the wheel |
| `pachinko_board.png` | 128×128 | the pin board |
| `cards/card_front.png` / `card_back.png` | 20×28 | playing cards |

Block faces live in `assets/casino/textures/block/`.

A **dark mode** pack ships built in (enable it in Options → Resource Packs) and overrides
the same filenames under the same paths. See [`ART_PIPELINE.md`](ART_PIPELINE.md) for the
palette and how to regenerate the set.

---

## Building

Each project is self-contained:

```bash
cd mc26.3            && ./gradlew build   # 26.3, Fabric, Java 25
cd mc1.20.1          && ./gradlew build   # 1.20.1, Fabric, Java 17
cd mc1.20.1-forge    && ./gradlew build   # 1.20.1, Forge, Java 17
cd mc1.20.1-neoforge && ./gradlew build   # 1.20.1, NeoForge, Java 17
cd mc1.20.1-arch     && ./gradlew build   # 1.20.1, all three loaders at once
```

Jars land in `<project>/build/libs/`.

### Checks

Three static gates run without launching the game, so a change can be verified in
seconds rather than by driving a client:

```bash
python3 tools/layout_check.py mc26.3/src   # no widget overlaps, nothing outside its panel
python3 tools/asset_sync_check.py          # the five trees ship identical textures
python3 tools/verify_jars.py               # each built jar contains what it must
```

`layout_check.py` parses each screen's widget coordinates out of the source and checks
them against the panel interior — it catches a button drawn over the frame border or
pushed into the footer, which compiles perfectly and only shows up on screen. An
unresolvable widget is reported as a gap in the check, never as a pass.

`tools/gen_block_models.py` regenerates the eight machine models and their item models
from one description, so the furniture shapes stay consistent across all five trees.

### Version notes

- **26.x** is unobfuscated (mojmap). `ResourceLocation` is `Identifier`,
  `BuiltInRegistries.ITEM.getValue(id)` replaces `.get(id)`, screens render through
  `GuiGraphicsExtractor`, and blocks/items require an explicit id on their properties
  (`Properties.setId(...)`) before construction — omitting it throws at class-load.
- **1.20.1** uses `ResourceLocation`, `GuiGraphics.drawString(..., false)`, native-size
  `blit` regions, and `source.hasPermission(2)`.
- **NeoForge 1.20.1** ships as `net.neoforged:forge` for that era; the NeoForge module
  compiles the Forge API sources and is loaded natively by NeoForge 47.1.x.

---

## License

MIT.
