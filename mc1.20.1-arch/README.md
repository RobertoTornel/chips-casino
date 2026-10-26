# Chips Casino — mc1.20.1-arch

Architectury multiloader (Fabric + Forge + NeoForge) build of the casino mod for Minecraft 1.20.1, targeting Java 17.

## Build

```bash
./gradlew build
```

The shippable jar lands in `build/libs/casino-0.1.0.jar`. The `-sources` jar next to it is
not needed by players.

## Test

```bash
./gradlew test
```

The game engines (`PachinkoEngine`, `PinballEngine`, `RouletteEngine`, ...) are plain Java
with no Minecraft types, so they run as ordinary JVM unit tests. See `ECONOMY.md` at the
repository root for the payout tables and why the expected return must stay below 1.0.

## Install

Drop the jar into `mods/` and restart the client or server. The mod is server-authoritative:
the money lives in `<world>/casino/wallets.json` and every game outcome is rolled on the
server, so a modified client cannot influence a payout.
