package dev.macasino.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.macasino.Casino;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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

    protected int bgWidth = 340;
    protected int bgHeight = 240;
    protected int leftPos;
    protected int topPos;

    /** Inset of the ornate corners in casino_frame.png, shared with ART_PIPELINE.md. */
    public static final int FRAME_INSET = 24;

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
    }

    // Text color constants (kept for text overlay — these are not backgrounds)
    public static int COLOR_TEXT_GOLD    = 0xFFFFD700;
    public static int COLOR_TEXT_WHITE   = 0xFFEEEEEE;
    public static int COLOR_TEXT_GREEN   = 0xFF55FF55;
    public static int COLOR_TEXT_RED     = 0xFFFF5555;
    public static int COLOR_TEXT_GREY    = 0xFF8A8A8A;
    public static int COLOR_TEXT_DIM     = 0xFF6B6B6B;

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
    public static int COLOR_MARQUEE     = 0xFF00E5FF; // neon cyan marquee rail

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

        addRenderableWidget(SpriteButton.of(leftPos + 10, topPos + bgHeight - 44, 110, 18, "Sell Hand", b -> {
            sendAction("DEPOSIT_MAIN_HAND", "{}");
        }));

        addRenderableWidget(SpriteButton.of(leftPos + 124, topPos + bgHeight - 44, 110, 18, "Buy Diamond", b -> {
            JsonObject json = new JsonObject();
            json.addProperty("itemId", "minecraft:diamond");
            json.addProperty("count", 1);
            sendAction("WITHDRAW_ITEM", GSON.toJson(json));
        }));

        addRenderableWidget(SpriteButton.of(leftPos + bgWidth - 24, topPos + 4, 18, 16, "✕", b -> {
            this.onClose();
        }));

        initGameWidgets();
    }

    protected abstract void initGameWidgets();

    public void updateState(String stateJson, long balance) {
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
                "\u00a7c\u2715", b -> this.onClose()));
    }

    protected void onStateUpdated() {
    }

    protected void sendAction(String action, String data) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(this.pos);
        buf.writeUtf(action);
        buf.writeUtf(data);
        ClientPlayNetworking.send(Casino.ACTION_ID, buf);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        // Sprite-driven casino frame (resource-pack overridable)
        drawCasinoFrame(graphics, leftPos, topPos, bgWidth, bgHeight);

        // Solid interior drawn over the frame's centre tile but under all text and content
        drawPanelBacking(graphics, leftPos, topPos, bgWidth, bgHeight);

        // Header bar sprite
        blitTexture(graphics, CasinoSprites.HEADER_BAR, leftPos, topPos, bgWidth, 32,
                CasinoSprites.HEADER_BAR_W, CasinoSprites.HEADER_BAR_H);

        // Balance bar sprite at bottom
        blitTexture(graphics, CasinoSprites.BALANCE_BAR, leftPos, topPos + bgHeight - 20, bgWidth, 16,
                CasinoSprites.BALANCE_BAR_W, CasinoSprites.BALANCE_BAR_H);

        // Neon marquee glow behind the title — makes the cabinet feel like a lit machine
        // rather than a flat panel.
        int neonX = contentX();
        int neonW = contentW();
        graphics.fill(neonX - 4, topPos + 6, neonX + neonW + 4, topPos + 18, 0x50FFD86B);
        graphics.fill(neonX - 2, topPos + 8, neonX + neonW + 2, topPos + 16, 0x30FFFFFF);

        // Title & Balance text (over sprites)
        graphics.drawString(this.font, this.title.getString(), contentX(), topPos + 8, COLOR_TEXT_GOLD, false);
        String balStr = "Chips: §e" + playerBalance + " §7(sell 0.8x / buy 1.2x)";
        graphics.drawString(this.font, balStr, contentX(), topPos + 20, COLOR_TEXT_WHITE, false);

        // Marquee rail + CHIPS label beneath the header bar
        graphics.fill(contentX(), topPos + 30, contentX() + contentW(), topPos + 32, COLOR_MARQUEE);
        graphics.drawString(this.font, "CHIPS ◈", contentX() + 2, topPos + 31, COLOR_TEXT_WHITE, false);

        // Inner glow line below the header bar — subtle highlight under the header.
        graphics.fill(contentX(), topPos + 32, contentX() + contentW(), topPos + 33, 0x30FFFFFF);
        // Corner accent marks — four dimmed gold blocks at the panel interior corners.
        int accent = 0x80FFFFD8; // COLOR_TEXT_GOLD dimmed
        graphics.fill(contentX(), contentY(), contentX() + 4, contentY() + 4, accent);
        graphics.fill(contentX() + contentW() - 4, contentY(), contentX() + contentW(), contentY() + 4, accent);
        graphics.fill(contentX(), contentBottom() - 4, contentX() + 4, contentBottom(), accent);
        graphics.fill(contentX() + contentW() - 4, contentBottom() - 4, contentX() + contentW(), contentBottom(), accent);

        // Game contents
        renderGameContent(graphics, mouseX, mouseY, partialTick);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    protected abstract void renderGameContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    /**
     * Draws the casino cabinet frame using the casino_frame sprite texture.
     * Resource packs can override assets/casino/textures/gui/casino_frame.png
     * to retheme the entire cabinet appearance.
     */
    protected void drawCasinoFrame(GuiGraphics graphics, int x, int y, int w, int h) {
        int tex = CasinoSprites.CASINO_FRAME_W;
        int i = FRAME_INSET;

        // Destination slices: fixed-size ornate corners, stretched edges and centre.
        int outerW = w + 8;
        int outerH = h + 8;
        int ox = x - 4;
        int oy = y - 4;
        int[] dx = { ox, ox + i, ox + outerW - i };
        int[] dw = { i, outerW - 2 * i, i };
        int[] dy = { oy, oy + i, oy + outerH - i };
        int[] dh = { i, outerH - 2 * i, i };
        int[] dx0 = { 0, i, tex - i };
        int[] dws = { i, tex - 2 * i, i };

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                graphics.blit(CasinoSprites.CASINO_FRAME,
                        dx[col], dy[row], dw[col], dh[row],
                        dx0[col], dx0[row], dws[col], dws[row], tex, tex);
                RenderSystem.disableBlend();
            }
        }
    }

    /**
     * Fills the panel interior with a flat dark surface so content stays legible no
     * matter what a resource pack ships in the frame's centre tile.
     */
    protected void drawPanelBacking(GuiGraphics graphics, int x, int y, int w, int h) {
        // Covers the true interior (inside the border, below the header, above the balance
        // bar) so no text ever lands on the frame's own pattern.
        graphics.fill(x + FRAME_INSET - 4, y + 30, x + w - FRAME_INSET + 4, y + h - 22, 0xF00D0A09);
        // A subtle inner hairline reads as an inlaid panel rather than a flat black hole.
        int hx0 = x + FRAME_INSET - 4, hy0 = y + 30;
        int hx1 = x + w - FRAME_INSET + 4, hy1 = y + h - 22;
        graphics.fill(hx0, hy0, hx1, hy0 + 1, 0x40FFD86B);
        graphics.fill(hx0, hy1 - 1, hx1, hy1, 0x30FFD86B);
        graphics.fill(hx0, hy0, hx0 + 1, hy1, 0x30FFD86B);
        graphics.fill(hx1 - 1, hy0, hx1, hy1, 0x30FFD86B);
    }

    /**
     * Draws a playing card using sprite textures for the card face/back.
     * Card textures at assets/casino/textures/gui/cards/ are resource-pack overridable.
     */
    protected void drawCard(GuiGraphics graphics, int x, int y, String cardStr) {
        int cardW = 20;
        int cardH = 28;

        if ("??".equals(cardStr)) {
            blitTexture(graphics, CasinoSprites.CARD_BACK, x, y, cardW, cardH,
                    CasinoSprites.CARD_W, CasinoSprites.CARD_H);
            graphics.drawCenteredString(this.font, "?", x + cardW / 2, y + 9, 0xFFFFFFFF);
            return;
        }

        blitTexture(graphics, CasinoSprites.CARD_FRONT, x, y, cardW, cardH,
                    CasinoSprites.CARD_W, CasinoSprites.CARD_H);

        boolean isRed = cardStr.contains("♥") || cardStr.contains("♦");
        int textColor = isRed ? 0xFFCC1111 : 0xFF111111;

        String rank = cardStr.replaceAll("[^0-9JQKA]", "");
        String suit = cardStr.replaceAll("[0-9JQKA]", "");

        graphics.drawString(this.font, rank, x + 3, y + 3, textColor, false);
        graphics.drawString(this.font, suit, x + 3, y + 14, textColor, false);
    }

    /**
     * Draws a chip sprite at the given position.
     */
    protected void drawChip(GuiGraphics graphics, int x, int y) {
        blitTexture(graphics, CasinoSprites.CHIP, x, y, 16, 16,
                CasinoSprites.CHIP_W, CasinoSprites.CHIP_H);
    }

    /**
     * Blit a texture, scaling the whole sprite into the destination box.
     *
     * 1.20.1's GuiGraphics takes an explicit source region plus the texture's native
     * size, so callers must pass the sprite's real pixel dimensions:
     * {@code blit(id, x, y, dstW, dstH, uOffset, vOffset, uWidth, vHeight, texW, texH)}.
     */
    protected void blitTexture(GuiGraphics graphics, ResourceLocation texture,
                               int x, int y, int w, int h,
                               int texW, int texH) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(texture, x, y, w, h, 0f, 0f, texW, texH, texW, texH);
        RenderSystem.disableBlend();
    }

    /**
     * Fit text to a width by truncating with ellipsis.
     */
    protected static String str(JsonObject o, String key, String fallback) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            try { return o.get(key).getAsString(); } catch (RuntimeException ignored) { return fallback; }
        }
        return fallback;
    }

    protected static int num(JsonObject o, String key, int fallback) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            try { return o.get(key).getAsInt(); } catch (RuntimeException ignored) { return fallback; }
        }
        return fallback;
    }

    protected static long lng(JsonObject o, String key, long fallback) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            try { return o.get(key).getAsLong(); } catch (RuntimeException ignored) { return fallback; }
        }
        return fallback;
    }

    protected static boolean bool(JsonObject o, String key, boolean fallback) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            try { return o.get(key).getAsBoolean(); } catch (RuntimeException ignored) { return fallback; }
        }
        return fallback;
    }

    protected static String fit(String text, int maxW) {
        if (text == null || text.isEmpty()) return "";
        int approxCharW = 6;
        if (text.length() * approxCharW <= maxW) return text;
        String cut = text;
        while (cut.length() > 1 && cut.length() * approxCharW > maxW) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }
}
