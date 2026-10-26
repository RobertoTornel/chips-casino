#!/usr/bin/env python3
"""Port the UI fixes from the 26.3 tree to the 1.20.1 trees, in place.

The 1.20.1 trees are not drop-in copies of 26.3: they render with
`GuiGraphics.drawString/blit(int...)` instead of `GuiGraphicsExtractor`, and they talk to
the server through `CasinoNetwork` raw packets instead of payload records. Copying the
26.3 sources over them loses all of that.

So each fix is applied as a semantic edit here — the same change, expressed in whatever
API the target tree actually uses. Idempotent: re-running does nothing if already applied.

Run:
    python3 tools/port_ui_fixes.py
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TREES = ["mc1.20.1", "mc1.20.1-forge", "mc1.20.1-neoforge", "mc1.20.1-arch/common"]

# Screens that carry a footer row of their own.
GAME_SCREENS = ["BaccaratScreen", "BlackjackScreen", "ExchangeScreen", "PachinkoScreen",
                "PinballScreen", "RouletteScreen", "TexasHoldemScreen", "VideoPokerScreen"]


def path(tree, rel):
    return os.path.join(ROOT, tree, "src/main/java/dev/casino", rel)


def patch(tree):
    changed = []

    # ---------------------------------------------------------------- BaseCasinoScreen
    p = path(tree, "client/gui/BaseCasinoScreen.java")
    s = open(p).read()
    orig = s

    # (a) The hardcoded Sell/Buy footer. Present in every screen, collided with each
    #     screen's own footer, and its actions duplicated the Exchange.
    footer_re = re.compile(
        r'\n[ \t]*// Common sell / buy controls\n.*?sendAction\("WITHDRAW_ITEM".*?\n\s*\}\)\);\n',
        re.S)
    if footer_re.search(s):
        s = footer_re.sub("\n", s, count=1)

    # (b) Rebuild widgets when state arrives.
    if "rebuildWidgets" not in s:
        old_update = '''    public void updateState(String stateJson, long balance) {
        this.lastStateJson = stateJson;
        this.playerBalance = balance;
        onStateUpdated();
    }'''
        new_update = '''    public void updateState(String stateJson, long balance) {
        this.lastStateJson = stateJson;
        this.playerBalance = balance;
        onStateUpdated();

        // Rebuild the widget strip after the state changes. Without this a screen kept the
        // buttons it made on its first render: after dealing a hand "Deal" stayed and
        // "Hit"/"Stand" never appeared. The state fields updated, so the text looked right
        // while the controls did not.
        rebuildWidgets();
    }

    /** Tears down and recreates the widget strip from the current state. */
    protected void rebuildWidgets() {
        if (this.minecraft == null) {
            return; // not on a screen stack yet; init() will build them
        }
        clearWidgets();
        initGameWidgets();
        addCloseButton();
    }

    /** Extracted so init() and rebuildWidgets() add it exactly once. */
    protected void addCloseButton() {
        addRenderableWidget(SpriteButton.of(leftPos + bgWidth - 26, topPos + 6, 20, 18,
                "\\u00a7c\\u2715", b -> this.onClose()));
    }'''
        if old_update in s:
            s = s.replace(old_update, new_update)

    # (c) Content origin inside the ornate border.
    if "contentX()" not in s:
        anchor = '''    /** Inset of the ornate corners in casino_frame.png, shared with ART_PIPELINE.md. */
    public static final int FRAME_INSET = 24;'''
        addition = anchor + '''

    /** Breathing room between the frame border and the first pixel of content. */
    protected static final int CONTENT_PAD = 4;

    /**
     * Content origin: the top-left of the usable interior, inside the ornate border.
     *
     * <p>The frame is nine-sliced with {@link #FRAME_INSET}-wide corners that overhang the
     * panel by 4px, so the decorative border physically occupies
     * {@code leftPos-4 .. leftPos+FRAME_INSET-4}. Screens used to draw from
     * {@code leftPos+10}, putting every label and button on top of the gold scrollwork.
     */
    protected int contentX() {
        return leftPos + FRAME_INSET - 4 + CONTENT_PAD;
    }

    protected int contentY() {
        return topPos + 32 + CONTENT_PAD;
    }

    protected int contentW() {
        return bgWidth - 2 * (FRAME_INSET - 4 + CONTENT_PAD);
    }

    /** Bottom of the usable interior, above the balance bar. */
    protected int contentBottom() {
        return topPos + bgHeight - 24 - CONTENT_PAD;
    }'''
        if anchor in s:
            s = s.replace(anchor, addition)

    # (d) Panel backing must cover the true interior, with an inlaid hairline.
    backing_re = re.compile(
        r'protected void drawPanelBacking\(GuiGraphics (\w+), int x, int y, int w, int h\) \{.*?\n    \}',
        re.S)
    new_backing = '''protected void drawPanelBacking(GuiGraphics %s, int x, int y, int w, int h) {
        // Covers the true interior (inside the border, below the header, above the balance
        // bar) so no text ever lands on the frame's own pattern.
        %s.fill(x + FRAME_INSET - 4, y + 30, x + w - FRAME_INSET + 4, y + h - 22, 0xF00D0A09);
        // A subtle inner hairline reads as an inlaid panel rather than a flat black hole.
        int hx0 = x + FRAME_INSET - 4, hy0 = y + 30;
        int hx1 = x + w - FRAME_INSET + 4, hy1 = y + h - 22;
        %s.fill(hx0, hy0, hx1, hy0 + 1, 0x40FFD86B);
        %s.fill(hx0, hy1 - 1, hx1, hy1, 0x30FFD86B);
        %s.fill(hx0, hy0, hx0 + 1, hy1, 0x30FFD86B);
        %s.fill(hx1 - 1, hy0, hx1, hy1, 0x30FFD86B);
    }'''
    m = backing_re.search(s)
    if m:
        g = m.group(1)
        s = s[:m.start()] + (new_backing % (g, g, g, g, g, g)) + s[m.end():]

    # (e) Header/balance text clear the border too.
    s = s.replace("this.font, this.title.getString(), leftPos + 10, topPos + 8,",
                  "this.font, this.title.getString(), contentX(), topPos + 8,")
    s = s.replace("this.font, balStr, leftPos + 10, topPos + 20,",
                  "this.font, balStr, contentX(), topPos + 20,")

    if s != orig:
        open(p, "w").write(s)
        changed.append("BaseCasinoScreen")

    # ---------------------------------------------------------------- each game screen
    for name in GAME_SCREENS:
        p = path(tree, f"client/gui/{name}.java")
        if not os.path.exists(p):
            continue
        s = open(p).read()
        orig = s

        # Rebase content offsets onto the interior. 1.20.1 screens still say leftPos+N /
        # topPos+N, so shift them the same way 26.3 was shifted.
        def rx(m):
            n = int(m.group(1))
            delta = n - 10
            return f"contentX() + {delta}" if delta else "contentX()"

        def ry(m):
            n = int(m.group(1))
            delta = n - 36
            if n < 36:
                return m.group(0)   # header band stays put
            return f"contentY() + {delta}" if delta else "contentY()"

        if "contentX()" not in s:
            s = re.sub(r"leftPos \+ (\d+)", rx, s)
            s = re.sub(r"topPos \+ (\d+)", ry, s)

        if s != orig:
            open(p, "w").write(s)
            changed.append(name)

    return changed


def main():
    rc = 0
    for tree in TREES:
        try:
            changed = patch(tree)
        except FileNotFoundError as exc:
            print(f"{tree}: missing file {exc.filename}")
            rc = 1
            continue
        print(f"{tree}: {', '.join(changed) if changed else 'already up to date'}")
    return rc


if __name__ == "__main__":
    sys.exit(main())
