# EMC Casino — 1.20.1 Architectury multiloader

Canonical Minecraft **1.20.1** deliverable of the EMC Casino mod, built once from a shared
`common` module and packaged independently for **Fabric**, **Forge** and **NeoForge**.

```
mc1.20.1-arch/
├── settings.gradle          pluginManagement repos + include common/fabric/forge/neoforge
├── build.gradle             architectury-plugin + architectury-loom (apply false), per-project loom
├── gradle.properties        versions, mod metadata, org.gradle.java.home (JDK 21 for Gradle)
├── gradlew, gradle/wrapper  Gradle 8.8 (architectury-loom 1.7 requires 8.x, not 9.x)
├── common/                  shared code + ALL assets/data/datapack/resource-pack
├── fabric/                  Fabric entrypoints + fabric.mod.json        reuses loom.platform=fabric
├── forge/                   Forge @Mod entrypoint + mods.toml           reuses loom.platform=forge
└── neoforge/                Forge-API build for NeoForge 1.20.1 (see below), own mods.toml
```

## Build

```bash
./gradlew build          # from mc1.20.1-arch/
```

Artifacts:

| Module | Jar | Contains |
|---|---|---|
| `fabric` | `fabric/build/libs/emc-casino-0.1.0.jar` | all common classes (remapped to intermediary) + assets |
| `forge` | `forge/build/libs/emc-casino-0.1.0.jar` | all common classes (remapped to SRG) + assets |
| `neoforge` | `neoforge/build/libs/emc-casino-0.1.0.jar` | same content, NeoForge-flavoured metadata |

`mod_id = emc_casino`, name **EMC Casino**, version **0.1.0**.

## Architecture

**`common/`** holds everything platform-neutral:

- `dev.emccasino.EmcCasino` — the mod core. Registration goes through Architectury's
  `DeferredRegister` / `RegistrySupplier` (works on all three loaders), plus
  `CommandRegistrationEvent` and `LifecycleEvent` for the `/casino` command and world-scoped
  account persistence.
- `dev.emccasino.network.CasinoNetwork` — one cross-loader packet layer built on Architectury's
  `NetworkManager` with a `FriendlyByteBuf` + `ResourceLocation` wire format:
  `action` (C2S), `sync` (S2C), `open_screen` (S2C). **No `net.minecraft.client.*` reference** —
  see *Dedicated-server safety* below.
- `dev.emccasino.client.CasinoClientInit` / `CasinoClientNetwork` — client-only: registers the
  S2C receivers and opens the matching screen. Only ever loaded from a client entrypoint.
- `dev.emccasino.client.gui.*` — six screens + `CasinoSprites` (sprite-driven, themable UI).
- `dev.emccasino.game.*` — the nine pure-Java engines (roulette, blackjack, baccarat, video poker,
  pachinko, Texas Hold'em + cards/deck/hand evaluator).
- `dev.emccasino.api.*` — `EmcRegistry` (0.8× deposit / 1.2× withdraw), `EmcDataLoader`
  (datapack-driven `data/emc_casino/emc/*.json`, re-applied on `/reload`), `EmcKubeJsBridge`.
- `dev.emccasino.economy.CasinoAccountManager`, `dev.emccasino.command.CasinoCommand`.

**Platform modules** contain only the loader glue:

| Concern | `fabric/` | `forge/` + `neoforge/` |
|---|---|---|
| Entrypoint | `EmcCasinoFabric` (`main`), `EmcCasinoFabricClient` (`client`) | `@Mod(EmcCasino.MOD_ID) EmcCasinoForge` |
| Client setup | `ClientModInitializer` | `FMLClientSetupEvent` + `DistExecutor` |
| `/reload` hook | `SimpleSynchronousResourceReloadListener` | `AddReloadListenerEvent` |
| Dark-mode pack | `ResourceManagerHelper.registerBuiltinResourcePack` | shipped as `resourcepacks/dark_mode/` in the jar |

### How the shared code reaches each jar

Each platform module depends on `:common` via its `namedElements` configuration and merges that
jar into its own `jar` task:

```groovy
jar { from { configurations.common.collect { zipTree(it) } } }
```

The platform `remapJar` task then remaps our code **and** the merged common classes in a single
pass, so every artifact is self-contained:

- Fabric jar → intermediary names (`method_9534`, `class_310`, …)
- Forge/NeoForge jar → SRG names (`m_6227_`, …)

### Dedicated-server safety

`net.minecraft.client.*` is referenced **only** from `dev.emccasino.client`. Verify anytime with:

```bash
# forge/neoforge jars
unzip -p forge/build/libs/emc-casino-0.1.0.jar dev/emccasino/network/CasinoNetwork.class \
  | grep -c "net/minecraft/client/"     # => 0
```

## NeoForge 1.20.1: why this module reuses the Forge build

NeoForge's `net.neoforged:neoforge` artifact starts at **20.2**; for 1.20.1 NeoForge is published
as `net.neoforged:forge:1.20.1-47.1.x` and is implemented on top of the Forge 47 API. Consequently
architectury-loom 1.7's `neoforge()` platform cannot resolve a 20.1 coordinate.

The `neoforge/` module therefore applies `loom.platform=forge` and compiles the **same** `forge/`
sources plus the shared `common` module. The resulting jar is a plain Forge-API mod, which
NeoForge 20.1 (`47.1.x`) loads natively; only its `displayName`/`description` metadata differ so
the two artifacts are distinguishable. This is the documented deviation from the original spec —
no module is skipped and all three artifacts are produced.
