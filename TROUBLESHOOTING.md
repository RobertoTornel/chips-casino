# Troubleshooting

## 26.3 client crashes at launch with `NoClassDefFoundError: org/lwjgl/sdl/SDL`

```
Description: Loading library SDL
java.lang.NoClassDefFoundError: org/lwjgl/sdl/SDL
    at com.mojang.blaze3d.platform.NativeLibrariesBootstrap.loadSdl(...)
```

**This is not a mod bug.** The mod loads fine and appears in the mod list; the client dies
before it can render anything. Every 26.x client will fail this way, with or without any
mods installed.

### Cause

Minecraft 26.3 moved from GLFW to **SDL3** for windowing, and its version manifest lists
LWJGL **3.4.3** — that is the first LWJGL line with an `lwjgl-sdl` module. A launcher
instance that pins an older LWJGL (3.3.x, which has no `lwjgl-sdl` at all) cannot load the
class, and crashes during native library bootstrap.

Prism/MultiMC store the LWJGL version explicitly in the instance's `mmc-pack.json`. When a
26.3 instance is created by editing an older instance's Minecraft version, the `org.lwjgl3`
component keeps its old pin, because 26.3's metadata only *suggests* 3.4.3 — it does not
require it, so the launcher is free to keep the stale one.

### Fix

Set the component version in the instance's `mmc-pack.json`:

```json
{
    "uid": "org.lwjgl3",
    "version": "3.4.3",
    "cachedVersion": "3.4.3",
    "cachedVolatile": true
}
```

Or in Prism's UI: **Edit instance → Version → LWJGL 3 → Change version → 3.4.3**.

The launcher downloads the rest on next start. If it does not, the 23 linux/x86_64
artifacts can be fetched straight from `libraries.minecraft.net` by reading the artifact
URLs out of the `org.lwjgl3` 3.4.3 component metadata.

### Verifying the correct pairing

| Minecraft | LWJGL | Notes |
|---|---|---|
| 26.x | 3.4.3 | Needs `lwjgl-sdl`, `lwjgl-vulkan`, `lwjgl-shaderc`, `lwjgl-spvc`, `lwjgl-vma` |
| 1.20.1 | 3.3.1 | GLFW-based; 3.3.1 is correct here |
| 1.16.5 and older | 3.2.2 | |

Do not "upgrade" an older instance to 3.4.3 to match — the pairing is per-line.

### Related crash lines

The same root cause produces these, which appear *before* the fatal one and can be
mistaken for separate problems:

```
[WARN] Failed to load Vulkan loader
java.lang.ClassNotFoundException: org/lwjgl/vulkan/VK

[WARN] Failed to get system info for SDL Platform
java.lang.ClassNotFoundException: org/lwjgl/sdl/SDLPlatform
```

All three are one missing `org.lwjgl3:3.4.3`.
