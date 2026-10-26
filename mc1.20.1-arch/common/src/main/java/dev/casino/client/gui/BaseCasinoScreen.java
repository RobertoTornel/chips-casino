package dev.casino.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.casino.Casino;
import dev.casino.network.CasinoNetwork;
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

    // Text color constants (kept for text overlay — these are not backgrounds)
    public static int COLOR_TEXT_GOLD    = 0xFFFFD700;
    public static int COLOR_TEXT_WHITE   = 0xFFEEEEEE;
    public static int COLOR_TEXT_GREEN   = 0xFF55FF55;
    public static int COLOR_TEXT_RED     = 0xFFFF5555;

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
    }

    protected void onStateUpdated() {
    }

    protected void sendAction(String action, String data) {
        CasinoNetwork.sendAction(this.pos, action, data);
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

        // Title & Balance text (over sprites)
        graphics.drawString(this.font, this.title.getString(), leftPos + 10, topPos + 8, COLOR_TEXT_GOLD, false);
        String balStr = "Chips: §e" + playerBalance + " §7(sell 0.8x / buy 1.2x)";
        graphics.drawString(this.font, balStr, leftPos + 10, topPos + 20, COLOR_TEXT_WHITE, false);

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
        graphics.fill(x + FRAME_INSET - 2, y + 32, x + w - FRAME_INSET + 2, y + h - 22, 0xF00D0A09);
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
