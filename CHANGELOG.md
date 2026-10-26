## 0.1.1 — UI, models and the exchange

Everything here was reported as broken; each is fixed at the cause rather than the
symptom.

### Menus went stale after any action

`BaseCasinoScreen.updateState()` updated its fields and repainted, but never rebuilt
its widgets. A screen kept the buttons it created on its first render, so after
dealing a hand "Deal" remained and "Hit"/"Stand" never appeared. The state text
updated correctly, which is why it looked like the menus were broken rather than the
game. State packets now rebuild the widget strip.

### Panels drew content on top of the decorative border

The nine-sliced frame overhangs the panel by 4px and its corners are 24px wide, so the
border occupies `leftPos-4 .. leftPos+20`. Screens positioned content at `leftPos+10`,
putting every label and button on the gold scrollwork. The base screen now exposes
`contentX()/contentY()/contentW()/contentBottom()` as the interior origin, and every
screen positions against those. The backing panel covers the true interior and gains a
faint inlaid hairline.

That change exposed real collisions, all fixed: roulette's outer bet rows and 12-column
number grid overran the right edge; pinball's two flipper buttons overlapped each other
and the drain button ran past the edge; the Hold'em action row summed to 358px inside a
332px interior with its Leave button outside the panel; blackjack's action row intruded
into the footer band.

### Machines were solid cubes

All eight blocks used `minecraft:block/cube_all`, so a blackjack table rendered as a
featureless box indistinguishable from stone — which is what made the items look wrong.
Every machine is now a real element model in one of three silhouettes:

- **tables** — felt top, raised rim, four legs (roulette, blackjack, baccarat)
- **cabinets** — body, lit marquee, recessed screen, control ledge (poker, pachinko, pinball)
- **a low inlaid table** for the Hold'em floor, and a counter-height table for the exchange

### Held-in-hand and inventory icons

Item models were bare parents of the block models and inherited a flat front-on
projection. Each now carries the full display set (gui, fixed, ground, first and third
person) so a machine reads as furniture in the hotbar and in hand.

### The chip item is gone

`ChipItem`, its registration in all five trees, its model, texture and lang key are
removed. Currency lives in the player's wallet; there is no item for it. The GUI chip
sprite remains, because that is the balance icon.

### The exchange did nothing at all

`ExchangeBlockEntity` overrides `handleGameAction()`, but `BaseCasinoBlockEntity`
intercepted `SELL_MAIN_HAND` and `EXCHANGE_BUY` before delegating, so no button on the
Exchange screen ever reached the Exchange code. The base now asks the subclass first via
a `handlesAction()` hook. Also added the missing `SELL_ALL_TRADABLE` handler, wired up
`EXCHANGE_REFRESH` (it was sent by the screen but had no handler), and removed the
duplicated price arithmetic that let two sell paths drift apart.

### Tooling

`layout_check.py` had been silently blind: it skipped the base screen, required literal
integer widths for widgets, and resolved positions against an empty constants map — so it
reported "0 widgets" and passed everything. It now discovers layout locals from source,
expands loop-generated widgets, resolves the interior accessors, and treats an
unresolvable widget as a gap in the check rather than a pass.

The 0.8x/1.2x arithmetic moved into `PricingRules`, which has no Minecraft dependency and
is covered by tests asserting the spread is positive at every base value — a round trip
can never profit.

### Notes

The spatial layout for each screen was re-flowed, so button positions will not match
0.1.0; nothing needs reconfiguring.

## 0.1.0 — first release

Initial release of Chips Casino: eight casino machines, a server-authoritative chip
economy, and a house edge that is asserted in tests rather than assumed.

### Machines

- **Roulette** — number, colour and split bets on a full table.
- **Blackjack** — hit, stand, double.
- **Baccarat** — player, banker, tie.
- **Video poker** — hold/draw, Jacks or Better.
- **Pachinko** — drop a ball into a ten-row Galton board.
- **Pinball** — fire a ball and time your flippers; the only skill-based machine.
- **Texas Hold'em** — a full table seating six, with server-side bots, in multiplayer.
- **The exchange** — sell items to the house for 80% of their value, buy back at 120%.

### Economy

Chips are per-player and server-side; every outcome is rolled on the server, so a modified
client cannot influence a payout. The exchange spread is the balancing mechanism: no trade
loop can come out ahead, which is what stops the casino's item prices from becoming a free
money source.

The word "money" here means the mod's own **Chips** currency (◈), not a Vault economy —
there is no external economy plugin dependency.

### House edge

Each machine returns less than the stake over time, and the numbers are checked by a
37-test suite that runs on plain JVM (the engines have no Minecraft types):

| Machine | Return | Edge |
|---|---|---|
| Pachinko | 0.891 | 10.9% |
| Pinball (perfect timing) | 0.920 | 8.0% |
| Pinball (no timing) | 0.259 | 74.1% |

The tests assert the headline invariants directly: expected return stays below 1.0, the
most likely pachinko pocket does not by itself return more than 1x, the landing
distribution is binomial, a 200k-drop simulation matches the analytic distribution within
4σ, and a player with perfect pinball timing still loses in the long run.

### Platforms

Seven jars across two Minecraft lines:

| Jar | Minecraft | Loader |
|---|---|---|
| `casino-0.1.0.jar` (mc26.3) | 26.3 | Fabric |
| `casino-0.1.0.jar` (mc1.20.1) | 1.20.1 | Fabric |
| `casino-0.1.0.jar` (mc1.20.1-forge) | 1.20.1 | Forge |
| `casino-0.1.0.jar` (mc1.20.1-neoforge) | 1.20.1 | NeoForge |
| `casino-0.1.0.jar` (arch ×3) | 1.20.1 | Fabric / Forge / NeoForge |

### Customisation

Interface colours, panel chrome and every machine sprite are generated from one
deterministic script, and a dark-mode resource pack ships in the jar. Item prices are
editable from a KubeJS server script — see `examples/kubejs/`.
