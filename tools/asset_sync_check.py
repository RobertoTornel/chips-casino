#!/usr/bin/env python3
"""
Cross-tree asset drift check.

The five projects (mc26.3 + the four 1.20.1 variants) are separate Gradle builds with
their own resource directories. Every texture they ship must be byte-identical across
all of them, because they are all generated from one script. In practice they drifted:
each tree kept the art it was scaffolded with, so the same filename meant different
pixels depending on which jar you opened. Nothing catches that at compile time.

Run this after generating art, or in CI. Exit code is non-zero on any drift.
"""
import hashlib
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TREES = [
    "mc26.3",
    "mc1.20.1",
    "mc1.20.1-forge",
    "mc1.20.1-neoforge",
    "mc1.20.1-arch/common",
]

# Resource subtrees that must agree everywhere.
CHECKED = [
    "assets/casino/textures/gui",
    "assets/casino/textures/block",
    "assets/casino/textures/item",
    "resourcepacks/dark_mode/assets/casino/textures/gui",
]


def rel_files(tree, sub):
    base = os.path.join(ROOT, tree, "src/main/resources", sub)
    if not os.path.isdir(base):
        return set()
    out = set()
    for dirpath, _, names in os.walk(base):
        for n in names:
            if n.endswith(".png"):
                full = os.path.join(dirpath, n)
                out.add(os.path.relpath(full, base))
    return out


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    problems = 0
    for sub in CHECKED:
        # union of filenames across trees
        names = set()
        for t in TREES:
            names |= rel_files(t, sub)
        if not names:
            continue
        for name in sorted(names):
            hashes = {}
            missing = []
            for t in TREES:
                path = os.path.join(ROOT, t, "src/main/resources", sub, name)
                if os.path.exists(path):
                    hashes.setdefault(md5(path), []).append(t)
                else:
                    missing.append(t)
            if len(hashes) > 1:
                problems += 1
                print(f"DRIFT {sub}/{name}")
                for h, trees in hashes.items():
                    print(f"    {h[:12]}  {', '.join(trees)}")
            if missing:
                problems += 1
                print(f"MISSING {sub}/{name} in: {', '.join(missing)}")
    if problems:
        print(f"\n{problems} asset problem(s) — run tools/gen_casino_art.py to resync")
        return 1
    print("all shipped textures identical across all five trees")
    return 0


if __name__ == "__main__":
    sys.exit(main())
