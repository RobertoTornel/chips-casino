package dev.macasino.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.macasino.network.CasinoActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

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
        ClientPlayNetworking.send(new CasinoActionPayload(this.pos, action, data));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.fill(0, 0, this.width, this.height, 0x88000000);

        drawCasinoFrame(extractor, leftPos, topPos, bgWidth, bgHeight);
        drawPanelBacking(extractor, leftPos, topPos, bgWidth, bgHeight);

        extractor.blit(CasinoSprites.HEADER_BAR, leftPos, topPos, bgWidth, 32, 0f, 0f, 256f, 32f);
        extractor.blit(CasinoSprites.BALANCE_BAR, leftPos, topPos + bgHeight - 20, bgWidth, 16, 0f, 0f, 256f, 16f);

        int neonX = contentX();
        int neonW = contentW();
        extractor.fill(neonX - 4, topPos + 6, neonX + neonW + 4, topPos + 18, 0x50FFD86B);
        extractor.fill(neonX - 2, topPos + 8, neonX + neonW + 2, topPos + 16, 0x30FFFFFF);

        extractor.text(this.font, this.title.getString(), contentX(), topPos + 8, COLOR_TEXT_GOLD);
        String balStr = "◈ " + playerBalance;
        extractor.text(this.font, balStr, contentX() + this.font.width(this.title.getString()) + 10, topPos + 8, COLOR_TEXT_WHITE);
        extractor.text(this.font, "Sell §a0.8x§7 · Buy §c1.2x", contentX(), topPos + 20, COLOR_TEXT_GREY);

        extractor.fill(contentX(), topPos + 32, contentX() + contentW(), topPos + 33, 0x30FFFFFF);

        renderGameContent(extractor, mouseX, mouseY, partialTick);

        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    protected abstract void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick);

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

    protected void drawCasinoFrame(GuiGraphicsExtractor extractor, int x, int y, int w, int h) {
        int i = FRAME_INSET;
        int[] dx = { x - 4, x - 4 + i, x - 4 + w + 8 - i };
        int[] dw = { i, w + 8 - 2 * i, i };
        float[] u0 = { 0f, i, FRAME_TEX - i };
        float[] uw = { i, FRAME_TEX - 2 * i, i };

        for (int row = 0; row < 3; row++) {
            int dy = (row == 0) ? y - 4 : (row == 1 ? y - 4 + i : y - 4 + h + 8 - i);
            int dh = (row == 1) ? h + 8 - 2 * i : i;
            float v0 = (row == 0) ? 0f : (row == 1 ? i : (FRAME_TEX - i));
            float vh = (row == 1) ? (FRAME_TEX - 2 * i) : i;
            for (int col = 0; col < 3; col++) {
                extractor.blit(CasinoSprites.CASINO_FRAME,
                        dx[col], dy, dw[col], dh,
                        u0[col], v0, u0[col] + uw[col], v0 + vh);
            }
        }
    }

    protected void drawPanelBacking(GuiGraphicsExtractor extractor, int x, int y, int w, int h) {
        extractor.fill(x + FRAME_INSET - 4, y + 30, x + w - FRAME_INSET + 4, y + h - 22, 0xF00D0A09);
        int hx0 = x + FRAME_INSET - 4, hy0 = y + 30;
        int hx1 = x + w - FRAME_INSET + 4, hy1 = y + h - 22;
        extractor.fill(hx0, hy0, hx1, hy0 + 1, 0x40FFD86B);
        extractor.fill(hx0, hy1 - 1, hx1, hy1, 0x30FFD86B);
        extractor.fill(hx0, hy0, hx0 + 1, hy1, 0x30FFD86B);
        extractor.fill(hx1 - 1, hy0, hx1, hy1, 0x30FFD86B);
    }

    protected void drawCard(GuiGraphicsExtractor extractor, int x, int y, String cardStr) {
        int cardW = 20;
        int cardH = 28;

        if ("??".equals(cardStr)) {
            extractor.blit(CasinoSprites.CARD_BACK, x, y, cardW, cardH, 0f, 0f, cardW, cardH);
            extractor.centeredText(this.font, "?", x + cardW / 2, y + 9, 0xFFFFFFFF);
            return;
        }

        extractor.blit(CasinoSprites.CARD_FRONT, x, y, cardW, cardH, 0f, 0f, cardW, cardH);

        boolean isRed = cardStr.contains("♥") || cardStr.contains("♦");
        int textColor = isRed ? 0xFFCC1111 : 0xFF111111;

        String rank = cardStr.replaceAll("[^0-9JQKA]", "");
        String suit = cardStr.replaceAll("[0-9JQKA]", "");

        extractor.text(this.font, rank, x + 3, y + 3, textColor);
        extractor.text(this.font, suit, x + 3, y + 14, textColor);
    }

    protected void drawChip(GuiGraphicsExtractor extractor, int x, int y) {
        extractor.blit(CasinoSprites.CHIP, x, y, 16, 16, 0f, 0f, 16f, 16f);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
