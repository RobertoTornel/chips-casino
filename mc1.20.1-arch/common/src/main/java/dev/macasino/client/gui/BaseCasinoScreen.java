package dev.macasino.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.macasino.Casino;
import dev.macasino.api.CasinoConfig;
import dev.macasino.api.PriceTable;
import dev.macasino.network.CasinoNetwork;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public abstract class BaseCasinoScreen extends Screen {
    protected static final Gson GSON = new Gson();

    protected final BlockPos pos;
    protected long playerBalance;
    protected String lastStateJson;

    protected int bgWidth = 340;
    protected int bgHeight = 240;
    protected int leftPos;
    protected int topPos;

    protected int contentX() {
        return leftPos + FRAME_INSET - 4 + CONTENT_PAD;
    }

    protected int contentY() {
        return topPos + 32 + CONTENT_PAD;
    }

    protected int contentW() {
        return bgWidth - 2 * (FRAME_INSET - 4 + CONTENT_PAD);
    }

    protected int contentBottom() {
        return topPos + bgHeight - 24 - CONTENT_PAD;
    }

    protected int contentH() {
        return contentBottom() - contentY();
    }

    protected static final int CONTENT_PAD = 4;

    public static int COLOR_TEXT_GOLD    = 0xFFFFD700;
    public static int COLOR_TEXT_GOLD_BRIGHT = 0xFFFFE88C;
    public static int COLOR_TEXT_WHITE   = 0xFFEEEEEE;
    public static int COLOR_TEXT_GREEN   = 0xFF7DDE7D;
    public static int COLOR_TEXT_RED     = 0xFFFF7B7B;
    public static int COLOR_TEXT_GREY    = 0xFF8A8A8A;
    public static int COLOR_TEXT_DIM     = 0xFF6B6B6B;

    public static int COLOR_ROULETTE    = 0xFFB32630;
    public static int COLOR_BLACKJACK   = 0xFF17603A;
    public static int COLOR_BACCARAT    = 0xFF1A3A63;
    public static int COLOR_VIDEOPOKER  = 0xFF8B5CF6;
    public static int COLOR_PACHINKO    = 0xFFD8454E;
    public static int COLOR_PINBALL     = 0xFFF0C55A;
    public static int COLOR_HOLDEM      = 0xFF4A3423;
    public static int COLOR_EXCHANGE    = 0xFFB8862C;
    public static int COLOR_SLOT_NEON   = 0xFF00E5FF;
    public static int COLOR_SLOT_MAG    = 0xFFFF00FF;
    public static int COLOR_MARQUEE     = 0xFF00E5FF;

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
        addCloseButton();
        initGameWidgets();
    }

    protected abstract void initGameWidgets();

    public void updateState(String stateJson, long balance) {
        this.lastStateJson = stateJson;
        this.playerBalance = balance;
        onStateUpdated();
        rebuildWidgets();
    }

    protected void rebuildWidgets() {
        if (this.minecraft == null) return;
        clearWidgets();
        initGameWidgets();
        addCloseButton();
    }

    protected void addCloseButton() {
        addRenderableWidget(SpriteButton.of(leftPos + bgWidth - 26, topPos + 6, 20, 18,
                "\u00a7c\u2715", b -> this.onClose()));
    }

    protected void onStateUpdated() {
    }

    protected void sendAction(String action, String data) {
        CasinoNetwork.sendAction(pos, action, data);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        drawCasinoFrame(graphics, leftPos, topPos, bgWidth, bgHeight);
        drawPanelBacking(graphics, leftPos, topPos, bgWidth, bgHeight);

        blitTexture(graphics, CasinoSprites.HEADER_BAR, leftPos, topPos, bgWidth, 32,
                CasinoSprites.HEADER_BAR_W, CasinoSprites.HEADER_BAR_H);

        blitTexture(graphics, CasinoSprites.BALANCE_BAR, leftPos, topPos + bgHeight - 20, bgWidth, 16,
                CasinoSprites.BALANCE_BAR_W, CasinoSprites.BALANCE_BAR_H);

        int neonX = contentX();
        int neonW = contentW();
        graphics.fill(neonX - 4, topPos + 6, neonX + neonW + 4, topPos + 18, 0x50FFD86B);
        graphics.fill(neonX - 2, topPos + 8, neonX + neonW + 2, topPos + 16, 0x30FFFFFF);

        graphics.drawString(this.font, this.title.getString(), contentX(), topPos + 8, COLOR_TEXT_GOLD, false);
        String balStr = "◈ " + playerBalance;
        graphics.drawString(this.font, balStr, contentX() + this.font.width(this.title.getString()) + 10, topPos + 8, COLOR_TEXT_WHITE, false);
        graphics.drawString(this.font, "Sell §a0.8x§7 · Buy §c1.2x", contentX(), topPos + 20, COLOR_TEXT_GREY, false);

        graphics.fill(contentX(), topPos + 30, contentX() + contentW(), topPos + 32, COLOR_MARQUEE);
        graphics.drawString(this.font, "CHIPS ◈", contentX() + 2, topPos + 31, COLOR_TEXT_WHITE, false);

        graphics.fill(contentX(), topPos + 32, contentX() + contentW(), topPos + 33, 0x30FFFFFF);

        renderGameContent(graphics, mouseX, mouseY, partialTick);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    protected abstract void renderGameContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    public static final int FRAME_INSET = 24;
    private static final float FRAME_TEX = 256f;

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

    protected void drawCasinoFrame(GuiGraphics graphics, int x, int y, int w, int h) {
        int tex = CasinoSprites.CASINO_FRAME_W;
        int i = FRAME_INSET;

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

    protected void drawPanelBacking(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x + FRAME_INSET - 4, y + 30, x + w - FRAME_INSET + 4, y + h - 22, 0xF00D0A09);
        int hx0 = x + FRAME_INSET - 4, hy0 = y + 30;
        int hx1 = x + w - FRAME_INSET + 4, hy1 = y + h - 22;
        graphics.fill(hx0, hy0, hx1, hy0 + 1, 0x40FFD86B);
        graphics.fill(hx0, hy1 - 1, hx1, hy1, 0x30FFD86B);
        graphics.fill(hx0, hy0, hx0 + 1, hy1, 0x30FFD86B);
        graphics.fill(hx1 - 1, hy0, hx1, hy1, 0x30FFD86B);
    }

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

    protected void drawChip(GuiGraphics graphics, int x, int y) {
        blitTexture(graphics, CasinoSprites.CHIP, x, y, 16, 16,
                CasinoSprites.CHIP_W, CasinoSprites.CHIP_H);
    }

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
