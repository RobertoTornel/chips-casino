package dev.casino.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.casino.network.CasinoActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Base casino screen using sprite-driven rendering.
 * All visual elements are drawn from texture sprites under assets/casino/textures/gui/
 * so resource packs (including the built-in dark_mode pack) can fully retheme the UI.
 */
public abstract class BaseCasinoScreen extends Screen {
    protected static final Gson GSON = new Gson();

    protected final BlockPos pos;
    protected long playerBalance;
    protected String lastStateJson;

    // Window dimensions
    protected int bgWidth = 340;
    protected int bgHeight = 240;
    protected int leftPos;
    protected int topPos;

    /**
     * Content origin: the top-left of the usable interior, inside the ornate border.
     *
     * <p>The frame is nine-sliced with {@link #FRAME_INSET}-wide destination corners that
     * overhang the panel by 4px, so the decorative border physically occupies
     * {@code leftPos-4 .. leftPos+FRAME_INSET-4} on the left (and the same on the other
     * three sides). Screens used to draw from {@code leftPos+10}, which put every label and
     * button on top of the gold scrollwork — legible text over a bright pattern, which is
     * what made the panels look broken.
     *
     * <p>These are the coordinates screens should position against.
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
    }

    /** Breathing room between the border and the first pixel of content. */
    protected static final int CONTENT_PAD = 4;

    // Text color constants (kept for text overlay — these are not backgrounds)
    public static int COLOR_TEXT_GOLD    = 0xFFFFD700;
    public static int COLOR_TEXT_WHITE   = 0xFFEEEEEE;
    public static int COLOR_TEXT_GREEN   = 0xFF55FF55;
    public static int COLOR_TEXT_RED     = 0xFFFF5555;

    // Per-screen theme accent colors (neon/casino palette)
    public static int COLOR_ROULETTE    = 0xFFB32630; // red
    public static int COLOR_BLACKJACK   = 0xFF17603A; // green felt
    public static int COLOR_BACCARAT    = 0xFF1A3A63; // navy
    public static int COLOR_VIDEOPOKER  = 0xFF8B5CF6; // purple
    public static int COLOR_PACHINKO    = 0xFFD8454E; // pink/red
    public static int COLOR_PINBALL     = 0xFFF0C55A; // yellow
    public static int COLOR_HOLDEM      = 0xFF4A3423; // leather
    public static int COLOR_EXCHANGE    = 0xFFB8862C; // brass
    public static int COLOR_SLOT_NEON   = 0xFF00E5FF; // neon cyan
    public static int COLOR_SLOT_MAG    = 0xFFFF00FF; // magenta

    public BaseCasinoScreen(Component title, BlockPos pos, String initialStateJson, long playerBalance) {
        super(title);
        this.pos = pos;
        this.lastStateJson = initialStateJson;
        this.playerBalance = playerBalance;
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - this.bgWidth) / 2;
        this.topPos = (this.height - this.bgHeight) / 2;
        clearWidgets();

        // Close button only. The base deliberately adds no other controls: a shared
        // footer row used to sit here on every screen and collided with each screen's own
        // footer, and its actions were not handled by any block entity. A screen's controls
        // belong to that screen.
        addCloseButton();
        initGameWidgets();
    }

    protected abstract void initGameWidgets();

    public void updateState(String stateJson, long balance) {
        this.lastStateJson = stateJson;
        this.playerBalance = balance;
        onStateUpdated();

        // Rebuild the widget strip after the state changes.
        //
        // Without this, a screen kept the buttons it created on its first render: after
        // dealing a hand the "Deal" button stayed and "Hit"/"Stand" never appeared,
        // because the widgets were only ever built once from init(). The state fields
        // themselves updated, which is why the text looked right while the controls did
        // not — the exact "menus are broken when changing anything" symptom.
        rebuildWidgets();
    }

    /**
     * Tears the widget strip down and recreates it from the current state.
     *
     * <p>Called on every state packet. Cheap enough to run per packet (a handful of
     * buttons), and far more reliable than trying to diff controls in place.
     */
    protected void rebuildWidgets() {
        if (this.minecraft == null) {
            return; // not yet added to a screen stack; init() will build them
        }
        clearWidgets();
        initGameWidgets();
        addCloseButton();
    }

    /** Extracted so both {@code init()} and {@link #rebuildWidgets()} add it exactly once. */
    protected void addCloseButton() {
        addRenderableWidget(SpriteButton.of(leftPos + bgWidth - 26, topPos + 6, 20, 18,
                "\u00a7c\u2715", b -> this.onClose()));
    }

    protected void onStateUpdated() {
    }

    protected void sendAction(String action, String data) {
        ClientPlayNetworking.send(new CasinoActionPayload(this.pos, action, data));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        // Semi-transparent world dim
        extractor.fill(0, 0, this.width, this.height, 0x88000000);

        // Sprite-driven casino frame (resource-pack overridable)
        drawCasinoFrame(extractor, leftPos, topPos, bgWidth, bgHeight);

        // Solid interior drawn over the frame's centre tile but under all text and content
        drawPanelBacking(extractor, leftPos, topPos, bgWidth, bgHeight);

        // Header bar sprite
        extractor.blit(CasinoSprites.HEADER_BAR, leftPos, topPos, bgWidth, 32, 0f, 0f, 1f, 1f);

        // Balance bar sprite at bottom
        extractor.blit(CasinoSprites.BALANCE_BAR, leftPos, topPos + bgHeight - 20, bgWidth, 16, 0f, 0f, 1f, 1f);

        // Neon marquee glow behind the title — makes the cabinet feel like a lit machine
        // rather than a flat panel.
        int neonX = contentX();
        int neonW = contentW();
        extractor.fill(neonX - 4, topPos + 6, neonX + neonW + 4, topPos + 18, 0x50FFD86B);
        extractor.fill(neonX - 2, topPos + 8, neonX + neonW + 2, topPos + 16, 0x30FFFFFF);

        // Title & Balance text (over sprites)
        extractor.text(this.font, this.title.getString(), contentX(), topPos + 8, COLOR_TEXT_GOLD);
        String balStr = "Chips: §e" + playerBalance + " §7(sell 0.8x / buy 1.2x)";
        extractor.text(this.font, balStr, contentX(), topPos + 20, COLOR_TEXT_WHITE);

        // Inner glow line below the header bar — subtle highlight under the header.
        extractor.fill(contentX(), topPos + 32, contentX() + contentW(), topPos + 33, 0x30FFFFFF);
        // Corner accent marks — four dimmed gold blocks at the panel interior corners.
        int accent = 0x80FFFFD8; // COLOR_TEXT_GOLD dimmed
        extractor.fill(contentX(), contentY(), contentX() + 4, contentY() + 4, accent);
        extractor.fill(contentX() + contentW() - 4, contentY(), contentX() + contentW(), contentY() + 4, accent);
        extractor.fill(contentX(), contentBottom() - 4, contentX() + 4, contentBottom(), accent);
        extractor.fill(contentX() + contentW() - 4, contentBottom() - 4, contentX() + contentW(), contentBottom(), accent);

        // Game-specific content
        renderGameContent(extractor, mouseX, mouseY, partialTick);

        // Sub-widgets (buttons)
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    protected abstract void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick);

    /**
     * Inset, in source pixels, of the ornate corner ornaments in {@code casino_frame.png}.
     * Shared with the art pipeline (ART_PIPELINE.md) — change both together.
     */
    public static final int FRAME_INSET = 24;
    /** Source texture is square; UVs are normalised against this. */
    private static final float FRAME_TEX = 256f;

    /**
     * Draws the casino cabinet frame as a true nine-slice.
     *
     * <p>Stretching the whole sprite to fit would smear the corner ornaments into diagonal
     * streaks, so the four corners are drawn at fixed size and only the edge and centre
     * tiles are stretched. Resource packs can override
     * {@code assets/casino/textures/gui/casino_frame.png} to retheme the cabinet.
     */
    protected void drawCasinoFrame(GuiGraphicsExtractor extractor, int x, int y, int w, int h) {
        int i = FRAME_INSET;
        // Destination slices: fixed-size corners, stretched edges.
        int[] dx = { x - 4, x - 4 + i, x - 4 + w + 8 - i };
        int[] dw = { i, w + 8 - 2 * i, i };
        // Source slices in normalised UV space.
        float[] u0 = { 0f, i / FRAME_TEX, (FRAME_TEX - i) / FRAME_TEX };
        float[] uw = { i / FRAME_TEX, (FRAME_TEX - 2 * i) / FRAME_TEX, i / FRAME_TEX };

        for (int row = 0; row < 3; row++) {
            int dy = (row == 0) ? y - 4 : (row == 1 ? y - 4 + i : y - 4 + h + 8 - i);
            int dh = (row == 1) ? h + 8 - 2 * i : i;
            float v0 = (row == 0) ? 0f : (row == 1 ? i / FRAME_TEX : (FRAME_TEX - i) / FRAME_TEX);
            float vh = (row == 1) ? (FRAME_TEX - 2 * i) / FRAME_TEX : i / FRAME_TEX;
            for (int col = 0; col < 3; col++) {
                extractor.blit(CasinoSprites.CASINO_FRAME,
                        dx[col], dy, dw[col], dh,
                        u0[col], v0, u0[col] + uw[col], v0 + vh);
            }
        }
    }

    /**
     * Fills the panel interior with a flat dark surface.
     *
     * <p>The frame sprite's centre is opaque, but once nine-sliced the stretched centre tile
     * can show the world through it if a pack ships a translucent centre — so a solid
     * backing is drawn first to guarantee content is always readable.
     */
    protected void drawPanelBacking(GuiGraphicsExtractor extractor, int x, int y, int w, int h) {
        // Covers the true interior (inside the border, below the header, above the balance
        // bar) so no text ever lands on the frame's own pattern.
        extractor.fill(x + FRAME_INSET - 4, y + 30, x + w - FRAME_INSET + 4, y + h - 22, 0xF00D0A09);
        // A subtle inner hairline reads as an inlaid panel rather than a flat black hole.
        int hx0 = x + FRAME_INSET - 4, hy0 = y + 30;
        int hx1 = x + w - FRAME_INSET + 4, hy1 = y + h - 22;
        extractor.fill(hx0, hy0, hx1, hy0 + 1, 0x40FFD86B);
        extractor.fill(hx0, hy1 - 1, hx1, hy1, 0x30FFD86B);
        extractor.fill(hx0, hy0, hx0 + 1, hy1, 0x30FFD86B);
        extractor.fill(hx1 - 1, hy0, hx1, hy1, 0x30FFD86B);
    }

    /**
     * Draws a playing card using sprite textures for the card face/back.
     * Card textures at assets/casino/textures/gui/cards/ are resource-pack overridable.
     */
    protected void drawCard(GuiGraphicsExtractor extractor, int x, int y, String cardStr) {
        int cardW = 20;
        int cardH = 28;

        if ("??".equals(cardStr)) {
            // Face-down card — use card_back sprite
            extractor.blit(CasinoSprites.CARD_BACK, x, y, cardW, cardH, 0f, 0f, 1f, 1f);
            extractor.centeredText(this.font, "?", x + cardW / 2, y + 9, 0xFFFFFFFF);
            return;
        }

        // Face-up card — use card_front sprite
        extractor.blit(CasinoSprites.CARD_FRONT, x, y, cardW, cardH, 0f, 0f, 1f, 1f);

        boolean isRed = cardStr.contains("♥") || cardStr.contains("♦");
        int textColor = isRed ? 0xFFCC1111 : 0xFF111111;

        String rank = cardStr.replaceAll("[^0-9JQKA]", "");
        String suit = cardStr.replaceAll("[0-9JQKA]", "");

        extractor.text(this.font, rank, x + 3, y + 3, textColor);
        extractor.text(this.font, suit, x + 3, y + 14, textColor);
    }

    /**
     * Draws a chip sprite at the given position.
     * Resource-pack overridable via assets/casino/textures/gui/chip.png.
     */
    protected void drawChip(GuiGraphicsExtractor extractor, int x, int y) {
        extractor.blit(CasinoSprites.CHIP, x, y, 16, 16, 0f, 0f, 1f, 1f);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
