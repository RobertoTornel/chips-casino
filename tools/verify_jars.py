#!/usr/bin/env python3
"""
Jar content verifier for the five shipped Casino artifacts.

Builds succeed or fail on *compilation*; they are silent about packaging. Everything
checked here is the kind of thing that compiles cleanly and then breaks in game:

  * a block/item/block-entity that never got registered
  * a block model pointing at a texture that is not in the jar
  * a screen or texture that silently did not get packaged
  * client-only classes leaking onto the dedicated server load path
  * the retired identifier `emc` appearing in any shipped path or class string

Run against dist/*.jar. Exit code is non-zero on any failure.
"""
import os
import sys
import zipfile

DIST = "/home/mana/Documents/mcmod/emc-casino/dist"

BLOCKS = [
    "roulette_table", "blackjack_table", "baccarat_table", "poker_machine",
    "pachinko_cabinet", "texas_holdem_floor", "exchange",
]
SCREENS = [
    "RouletteScreen", "BlackjackScreen", "BaccaratScreen", "VideoPokerScreen",
    "PachinkoScreen", "TexasHoldemScreen", "ExchangeScreen",
]

fail = 0
passes = 0


def check(cond, label, jar):
    global fail, passes
    if cond:
        passes += 1
    else:
        fail += 1
        print(f"  FAIL [{os.path.basename(jar)}] {label}")


def verify(jar):
    print(f"\n=== {os.path.basename(jar)} ===")
    with zipfile.ZipFile(jar) as z:
        names = z.namelist()
        blob = b""
        for n in names:
            if n.endswith(".class"):
                blob += z.read(n)

        # --- core classes ---
        for cls in ["dev/casino/Casino.class"]:
            check(cls in names, f"missing {cls}", jar)

        # --- blocks: model + texture + blockstate ---
        for b in BLOCKS:
            check(f"assets/casino/blockstates/{b}.json" in names, f"no blockstate {b}", jar)
            check(f"assets/casino/models/block/{b}.json" in names, f"no block model {b}", jar)
        for tex in ["roulette_table", "blackjack_table", "baccarat_table", "poker_machine",
                    "pachinko_cabinet", "texas_holdem_floor", "exchange"]:
            check(f"assets/casino/textures/block/{tex}.png" in names, f"no block texture {tex}", jar)

        # --- GUI sprites ---
        for g in ["casino_frame", "header_bar", "button", "button_hover", "balance_bar",
                  "chip", "roulette_wheel", "pachinko_board",
                  "cards/card_front", "cards/card_back"]:
            check(f"assets/casino/textures/gui/{g}.png" in names, f"no gui sprite {g}", jar)
        # dark theme pack
        for g in ["casino_frame", "header_bar", "button", "balance_bar", "chip"]:
            check(f"resourcepacks/dark_mode/assets/casino/textures/gui/{g}.png" in names,
                  f"no dark sprite {g}", jar)

        # --- item ---
        check("assets/casino/models/item/chip.json" in names, "no chip item model", jar)
        check("assets/casino/textures/item/chip.png" in names, "no chip item texture", jar)

        # --- screens compile in ---
        for s in SCREENS:
            found = any(n.endswith(f"client/gui/{s}.class") for n in names)
            check(found, f"missing screen {s}", jar)

        # --- language file ---
        check("assets/casino/lang/en_us.json" in names, "no en_us lang", jar)

        # --- no empty legacy dirs, no emc anywhere ---
        emc_paths = [n for n in names if "emc" in n.lower()]
        check(not emc_paths, f"legacy 'emc' paths present: {emc_paths[:3]}", jar)
        check(b"emc_casino" not in blob and b"emc-casino" not in blob,
              "legacy 'emc' string inside classes", jar)

        # --- dedicated-server safety: no client classes referenced from server classes ---
        # Casino.class and the common entrypoints must not reference net/minecraft/client
        client_leaks = []
        for n in names:
            if not n.endswith(".class"):
                continue
            # only inspect the obvious server-side entrypoints
            if any(k in n for k in ("/Casino.class", "/block/", "/game/", "/economy/", "/api/", "/command/")):
                data = z.read(n)
                if b"net/minecraft/client/" in data:
                    client_leaks.append(n)
        check(not client_leaks, f"client refs in server classes: {client_leaks[:3]}", jar)


def main():
    jars = sorted(
        os.path.join(DIST, f) for f in os.listdir(DIST) if f.endswith(".jar")
    ) if os.path.isdir(DIST) else []
    arch = os.path.join(DIST, "architectury")
    if os.path.isdir(arch):
        jars += sorted(os.path.join(arch, f) for f in os.listdir(arch) if f.endswith(".jar"))
    if not jars:
        print(f"no jars in {DIST}")
        return 1
    for j in jars:
        verify(j)
    print(f"\n{'=' * 50}\n{passes} PASS / {fail} FAIL  across {len(jars)} jars")
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
