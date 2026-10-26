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
