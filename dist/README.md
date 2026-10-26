# Builds

These jars are produced by `./gradlew build` in each project tree and are **not** committed
(see `.gitignore`) — Modrinth is the distribution channel for releases, and committing
binaries bloats the repository history for no benefit.

To build the same set:

| Jar | Project tree | Command |
|---|---|---|
| `casino-26.3-fabric` | `mc26.3` | `gradlew build` |
| `casino-1.20.1-fabric` | `mc1.20.1` | `gradlew build` |
| `casino-1.20.1-forge` | `mc1.20.1-forge` | `gradlew build` |
| `casino-1.20.1-neoforge` | `mc1.20.1-neoforge` | `gradlew build` |
| `casino-1.20.1-arch-{fabric,forge,neoforge}` | `mc1.20.1-arch` | `gradlew build` |

Each `build/libs/casino-0.1.0.jar` is the shippable artifact; the `-sources` jar beside it
is not needed by players.
