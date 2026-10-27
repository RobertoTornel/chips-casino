## 0.1.7 — Creative Mode Tab + SLOT screen fix

- **Creative Mode Tab**: "Chips Casino" tab registered in all 5 trees (Fabric 26.3, Fabric 1.20.1, Forge 1.20.1, NeoForge 1.20.1, Architectury 1.20.1 multiloader), with slot_machine icon and all 9 block items. No more `null` tab / NPE risk.
- **SLOT screen fix**: `SlotMachineBlockEntity.getGameType()` now returns `"SLOT"` matching the client handler's `case "SLOT"` — previously returned `"SLOT_MACHINE"`, so the slot machine screen never opened. Fixed in all 5 trees.
- Version bumped 0.1.6 → 0.1.7 across all five trees.

## 0.1.6 — Multiplayer baccarat and roulette tables

Every table game now shares one shoe/wheel and one dealer clock, at blackjack's standard:

### New: Baccarat table (multiplayer)
Six bettors on a semicircle facing one dealer shoe, backing PLAYER / BANKER / TIE. The
server deals one card per beat, runs the full Punto Banco tableau (naturals stop the
hand, the player draws 0-5 and stands 6-7, the banker's complete third-card table
including the 3-vs-8 exception), pays 5% commission on banker and 8:1 on a tie, and
settles each seat once per round through a payout queue that never pays twice.

### New: Roulette table (multiplayer)
Six bettors around a European single-zero wheel. Chips go down during a betting window,
then the server spins a SecureRandom number it never sends until SETTLE, so a client
cannot read the result out of the packet stream. Every bet is refundable while the
window is open, the wheel angle is a pure function of the server clock so all six
screens spin in lockstep, and house players fill empty seats without ever touching the
economy queue.

### Both tables
- `toJson(UUID)` strips the secret (hole card / winning number) until it is revealed,
  not merely hidden client-side.
- `drainPayouts()` pays each human exactly once per round; bots are never queued, so
  house players can't mint chips.
- 26.3 `GuiGraphicsExtractor`, 1.20.1 `GuiGraphics` — ported mechanically with
  `tools/port_screen.py`; `tools/layout_check.py` gates the panel at 400x232 with no
  overlapping widgets.
- 18 new JUnit tests for roulette (payouts match `RouletteEngine` byte-for-byte across
  every bet type × number), 15 for baccarat (the full banker third-card table, pushes
  on tie, bots excluded from the queue), all green in every tree.

## 0.1.4 — Multiplayer blackjack table

The blackjack table stops being a single-player form and becomes a real table: six
seats on a semicircle facing one dealer, everyone playing the same shoe, with cards
that visibly travel out of it. Five more players can sit down with you, and the houses
deal themselves into empty chairs so the table is never empty.

### Multiplayer
- Six seats, one shared dealer hand, one shared shoe. The server owns the shoe, the
  shuffle and every outcome; a client sends a bet and a verb and nothing else.
- The hole card is stripped server-side until the dealer actually turns it, so the
  secret is not merely hidden on screen — it is not in the packet.
- Seats fill with house players on their own, so a lone player still gets a full table.
  Each one bets, hits, stands, doubles and splits on its own clock.
- The table runs on a 20 Hz state machine: betting window, paced deal, turns in seat
  order, dealer reveal, dealer draws, settle, next round. Any seated player can call
  the deal instead of waiting for the window.

### Rules
- Full blackjack: naturals pay 3:2, push on equal totals, dealer stands on all 17.
- **Double** on any two cards, **split** up to four hands (aces receive one card each
  and close), both charged against your balance before the card is dealt and refunded
  if the verb was illegal.
- Each of a split's halves is settled separately, so you can win one and lose the other.

### Animation
- Every dealt card records the tick it landed, so all clients slide it out of the shoe
  along the same eased path and agree on the frame.
- The hole card plays a flip when the dealer reveals; the dealer draws with a beat
  between cards rather than dumping its hand at once.
- Chips scale in when a bet is committed, the active seat pulses, and the dealer
  narrates the round in a speech panel.

### Fixed
- A split hand was marked as *not* from a split, so a 21 built from a split pair would
  have been paid as a natural and could be split again.
- After a split, only the first half was closed on 21 — the second stayed playable and
  could be hit into a bust.
- `Deck` reshuffled the whole shoe mid-hand when it ran dry; it now reshuffles only
  between rounds, and never below 52 cards.
- The slot machine's spin seed was derived from the clock and the player's UUID, which
  is guessable; it now comes from a `SecureRandom`, and the bet is taken from the
  server-side pending bet rather than from the packet.

### Tooling
- `tools/port_screen.py` rewrites a 26.3 screen into its 1.20.1 equivalent, so a screen
  is authored once and cannot drift between eras.
- `tools/verify_jars.py` now covers the slot machine (block, model, texture, screen and
  nine sprites): 462 checks across seven jars.

## 0.1.3 — Slot Machine game

A brand-new casino machine with custom art, not a flat block: a steel-and-brass
cabinet with a lit marquee that rises above the block, three reel windows, and a
protruding spin button. Reels spin on the server and land on a weighted symbol
pool (CHERRY, LEMON, BELL, BAR, SEVEN) with a calibrated ~12.8% house edge — the
same honest math as Pachinko and Pinball, asserted by unit tests.

### New: Slot Machine
- Bet 10 / 20 / 50 / 100 Chips ◈, spin, and watch three reels stop on symbols.
- 3-of-a-kind on the centre line pays: SEVEN 200×, BAR 50×, BELL 18×, LEMON 7×,
  CHERRY 4×; two left-aligned CHERRYs pay 2×.
- House edge ≈ 12.76% (RTP ≈ 87.24%), verified by `SlotMachineEngineTest` over
  200,000 simulated spins.
- Custom textures: cabinet face/side/top, reel-window backdrop, and five symbol
  sprites, generated deterministically and byte-identical across all five trees.

### Visual flair pass (all machines)
- Richer 3D block models: tables have inset felt, side aprons, legs with foot
  caps; cabinets have marquees, bezelled screens, control ledges; every machine
  rises above or sits below a flat block silhouette.
- Neon marquee rail + "CHIPS ◈" label in every screen header.
- New `gen_block_models.py` and `gen_casino_art.py` produce all geometry and art
  deterministically.

## 0.1.2 — UI, models and the exchange
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
