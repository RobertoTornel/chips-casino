# Game economics

Every game in the mod is a machine with a **house edge**: the expected return per unit
bet is below 1.0, so a player loses over time no matter how good they are. This file
records the numbers and, more importantly, *how they are verified*, because one of these
boards shipped returning 54x per bet and nothing in the build noticed.

The rule that matters: **a payout table is not tuned by feel.** The expected value is
computed and asserted in a test; if a multiplier changes, the test fails.

---

## Pachinko — 10.9% house edge

The ball falls through a Galton board: one left/right decision per peg row, ten rows.
Landing counts across 1024 balls:

| pocket | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| count | 1 | 10 | 45 | 120 | 210 | **252** | 210 | 120 | 45 | 10 | 1 |
| payout | 60x | 6x | 2x | 1x | 0 | 1x | 0 | 1x | 2x | 6x | 60x |

The centre pocket holds **24.6% of all balls**. That is the whole design constraint: a
generous multiplier in the middle is self-defeating. An earlier table paid 5x there, which
by itself returned **1.23x per bet** — the board was a money printer before any edge pocket
was considered. The centre therefore pays the smallest positive amount (1x) and the value
lives in the 60x tails, which hit 0.098% of the time.

Expected return = **0.891** (10.9% edge).

### The bug that shipped

The original engine advanced the ball with a *running sum of random deflections* and
clamped it to the walls. That is not a Galton board: the drift accumulated indefinitely, so
the final x was effectively one draw from a very wide distribution. With a ±10px jackpot
window against a ±13px spread, **53% of balls hit the jackpot at 100x**, and the board
returned **54.27x per 1x bet**.

Measured before the fix (400k simulated drops):

| outcome | probability | multiplier | contribution |
|---|---|---|---|
| JACKPOT | 53.36% | 100x | 53.36x |
| TULIP | 6.05% | 15x | 0.91x |
| REGULAR | 0.004% | 5x | 0.0002x |
| LOST | 40.59% | 0x | 0 |

**54.27x return, −5327% house edge.** A single ball at a 100-chip stake paid 10,000 chips
53% of the time.

The fix was structural, not a number tweak: model the peg field as a binomial random walk
so the variance is a property of the board (row count), then tune payouts against that
known distribution.

---

## Pinball — 8% house edge at perfect play, 74% for a careless player

Pinball is the only game here where player input changes the outcome, which makes it the
easiest one to accidentally make unprofitable: a generous per-bumper payout times a skilled
player's long ball is unbounded profit.

Each flipper input is a skill check. Save chance is `0.42 + 0.30 × timing`, so a perfect
flip saves 72% of the time and a mistimed one 42%. Expected bumpers is the geometric mean
`p/(1-p)`, capped at the 8-bumper row.

| timing | save chance | expected bumpers | return |
|---|---|---|---|
| 0.0 (never) | 42% | 0.72 | 0.26x |
| 0.5 | 57% | 1.33 | 0.47x |
| 1.0 (perfect) | 72% | 2.57 | **0.92x** |

The per-bumper payout is calibrated against the **skilled ceiling**, not the average, so
the machine stays profitable even against a player with perfect timing. A single brilliant
ball (8 bumpers) does pay 2.86x the stake — that is the appeal and it is intentional; what
must stay below 1.0 is the expectation, and that is what the tests assert.

---

## Verification

`src/test/java/dev/casino/game/` holds 37 tests. They are plain JUnit on plain Java — the
engines have no Minecraft types, so they run on the JVM in seconds, in every one of the five
project trees.

The ones that matter:

- `expectedReturnIsBelowOne` — the headline invariant.
- `peakPocketIsNotAPrinter` — asserts the binomial peak does not by itself return >1x. This
  is the exact bug that shipped; it now cannot come back.
- `landingDistributionIsBinomial` — pins the physics to the intended model.
- `simulationMatchesAnalytics` — 200k real drops vs the analytic distribution, 4σ.
- `longRunPlayerLoses` — plays 100k hands and checks the balance.
- `perfectPlayerStillLoses` (pinball) — the machine's worst case.
- `everyPocketIsReachable` — no dead payout slots that show a prize nobody can win.

Run them with:

```bash
mc_gradle <tree> test        # or: ./gradlew test
```
