# Chips Casino

Eight casino machines for Minecraft, with a chip economy that actually balances.

Every machine is a **house-edge game**: the expected return per chip is below 1.0, so
players lose over time and the casino's chip supply stays finite. That is asserted by a
test suite, not assumed — see the numbers below.

## Machines

| Machine | How it plays |
|---|---|
| **Roulette** | Full table: numbers, colours, splits |
| **Blackjack** | Hit, stand, double |
| **Baccarat** | Player, banker, tie |
| **Video poker** | Hold/draw, Jacks or Better |
| **Pachinko** | Drop a ball into a ten-row Galton board and watch it land |
| **Pinball** | Fire a ball, time your flippers — skill changes the odds |
| **Texas Hold'em** | Six-seat multiplayer table with server-side bots |
| **The exchange** | Sell items at 80% of value, buy back at 120% |

## The economy

Chips (◈) are per-player and server-authoritative: every roll happens on the server, so a
modified client cannot influence a payout, and wallets are saved per world.

The exchange spread is what keeps the item market honest. Items sell to the house for **80%**
of their value and cost **120%** to buy back, so no buy-sell loop can turn a profit — the
casino's prices can never become free money.

Item prices are editable from KubeJS without a restart:

```js
ServerEvents.loaded(event => {
    const prices = Java.loadClass('dev.casino.api.PriceKubeJsBridge')
    prices.set('minecraft:diamond', 8192)
    prices.remove('minecraft:dirt')      // make an item untradable
})
```

## The house edge

| Machine | Return | Edge |
|---|---|---|
| Pachinko | 0.891 | 10.9% |
| Pinball, perfect timing | 0.920 | 8.0% |
| Pinball, no timing | 0.259 | 74.1% |

Pinball is the interesting one. It is the only skill-based machine, so its payout scale is
calibrated against the **skilled ceiling** rather than the average: even a player who saves
72% of balls with perfect flipper timing still returns 0.92x. A single brilliant ball pays
2.86x the stake — that is the appeal, and it is intentional. What stays below 1.0 is the
expectation.

Pachinko is a real Galton board: one 50/50 decision per row, ten rows, so the landing
distribution is exactly binomial. The centre pocket holds 24.6% of balls, which means it
must pay the least — the value lives in the 60x tails that hit 0.1% of the time.

These numbers are verified by 37 unit tests that run on a plain JVM, because the game
engines deliberately contain no Minecraft types. The suite pins the physics to the intended
model (200k simulated drops matched against the analytic binomial within 4σ) and asserts
the headline invariant on every game.

## Customisation

Panel chrome, every machine sprite and the interface palette come from one deterministic
script, and a dark-mode resource pack is bundled in the jar.

## Requirements

- **Minecraft 26.3**: Fabric Loader 0.19.5+ and Fabric API
- **Minecraft 1.20.1**: Fabric, Forge or NeoForge — pick the jar matching your loader
- Java 25 on 26.3, Java 17 on 1.20.1
- Client and server both need the mod (the menus are custom screens)

## Multiplayer

Fully server-authoritative and safe for shared worlds. The Texas Hold'em table seats six
with spectators kept in sync, and bots fill empty seats so a table is playable alone.
