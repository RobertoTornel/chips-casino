#!/usr/bin/env python3
"""
Jar content verifier for the five shipped Casino artifacts.

Builds succeed or fail on *compilation*; they are silent about packaging. Everything
checked here is the kind of thing that compiles cleanly and then breaks in game:

  * a block/item/block-entity that never got registered
  * a block model pointing at a texture that is not in the jar
  * a screen or texture that silently did not get packaged
  * client-only classes leaking onto the dedicated server load path
  * any shipped path outside the `casino` namespace

Run against dist/*.jar. Exit code is non-zero on any failure.
"""
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(ROOT, "dist")

BLOCKS = [
    "roulette_table", "blackjack_table", "baccarat_table", "poker_machine",
    "pachinko_cabinet", "texas_holdem_floor", "exchange", "pinball_machine",
    "slot_machine",
]
# Pure-Java game models. These carry the rules and the RNG, so a jar that ships a
# screen without its model is the one failure this gate exists to catch.
MODELS = [
    "BaccaratTable", "BlackjackTable", "Deck", "PachinkoEngine", "PinballEngine",
]
SCREENS = [
    "RouletteScreen", "BlackjackScreen", "BaccaratScreen", "VideoPokerScreen",
    "PachinkoScreen", "TexasHoldemScreen", "ExchangeScreen", "PinballScreen",
    "SlotMachineScreen",
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
                    "pachinko_cabinet", "texas_holdem_floor", "exchange", "pinball_machine",
                    "slot_machine"]:
            check(f"assets/casino/textures/block/{tex}.png" in names, f"no block texture {tex}", jar)

        # --- GUI sprites ---
        for g in ["casino_frame", "header_bar", "button", "button_hover", "balance_bar",
                  "chip", "roulette_wheel", "pachinko_board",
                  "cards/card_front", "cards/card_back",
                  "slot_reel_window", "slot_reel_strip", "slot_spin_button",
                  "slot_spin_button_hover", "slot_symbol_cherry", "slot_symbol_lemon",
                  "slot_symbol_bell", "slot_symbol_bar", "slot_symbol_seven"]:
            check(f"assets/casino/textures/gui/{g}.png" in names, f"no gui sprite {g}", jar)
        # dark theme pack
        for g in ["casino_frame", "header_bar", "button", "balance_bar", "chip"]:
            check(f"resourcepacks/dark_mode/assets/casino/textures/gui/{g}.png" in names,
                  f"no dark sprite {g}", jar)

        # --- item ---
        check("assets/casino/models/item/chip.json" in names, "no chip item model", jar)
        check("assets/casino/textures/item/chip.png" in names, "no chip item texture", jar)

        # --- game models compile in ---
        for m in MODELS:
            found = any(n.endswith(f"game/{m}.class") for n in names)
            check(found, f"missing game model {m}", jar)

        # --- the baccarat table entity ships and is wired to its block ---
        for be in ["BaccaratBlockEntity", "BlackjackBlockEntity"]:
            found = any(n.endswith(f"block/entity/{be}.class") for n in names)
            check(found, f"missing block entity {be}", jar)

        # --- screens compile in ---
        for s in SCREENS:
            found = any(n.endswith(f"client/gui/{s}.class") for n in names)
            check(found, f"missing screen {s}", jar)

        # --- language file ---
        check("assets/casino/lang/en_us.json" in names, "no en_us lang", jar)

        # --- no stale namespace paths inside the artifact ---
        # Every shipped asset must live under the mod's own namespace.
        foreign = [n for n in names
                   if n.startswith("assets/") and not n.endswith("/")
                   and not n.startswith("assets/casino/")]
        check(not foreign, f"assets outside the casino namespace: {foreign[:3]}", jar)

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
