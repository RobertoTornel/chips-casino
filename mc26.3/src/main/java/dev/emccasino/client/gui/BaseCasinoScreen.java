package dev.emccasino.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.emccasino.network.CasinoActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public abstract class BaseCasinoScreen extends Screen {
    protected static final Gson GSON = new Gson();

    protected final BlockPos pos;
    protected long playerBalance;
    protected String lastStateJson;

    // Window dimensions
    protected int bgWidth = 340;
    protected int bgHeight = 220;
    protected int leftPos;
    protected int topPos;

    // Theming colors
    public static int COLOR_BORDER_OUTER = 0xFF2A1808; // Rich dark wood
    public static int COLOR_BORDER_INNER = 0xFF8A6232; // Gold/bronze trim
    public static int COLOR_FELT_GREEN   = 0xFF0E381A; // Casino felt green
    public static int COLOR_FELT_DARK    = 0xFF082210;
    public static int COLOR_PANEL_HEADER = 0xFF141F14;
    public static int COLOR_TEXT_GOLD    = 0xFFFFD700;
    public static int COLOR_TEXT_WHITE   = 0xFFEEEEEE;
    public static int COLOR_TEXT_GREEN   = 0xFF55FF55;
    public static int COLOR_TEXT_RED     = 0xFFFF5555;
    public static int COLOR_CARD_BG      = 0xFFFFFFFF;
    public static int COLOR_CARD_BORDER  = 0xFF222222;

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

        // Common Deposit / Withdraw controls
        // Deposit Main Hand item (0.8x ProjectE EMC balancing)
        addRenderableWidget(Button.builder(Component.literal("§aDeposit Hand (0.8x)"), b -> {
            sendAction("DEPOSIT_MAIN_HAND", "{}");
        }).bounds(leftPos + 10, topPos + bgHeight - 24, 110, 18).build());

        // Withdraw Diamond (1.2x ProjectE EMC balancing)
        addRenderableWidget(Button.builder(Component.literal("§bBuy Diamond (1.2x)"), b -> {
            JsonObject json = new JsonObject();
            json.addProperty("itemId", "minecraft:diamond");
            json.addProperty("count", 1);
            sendAction("WITHDRAW_ITEM", GSON.toJson(json));
        }).bounds(leftPos + 124, topPos + bgHeight - 24, 110, 18).build());

        // Close button
        addRenderableWidget(Button.builder(Component.literal("§c✕"), b -> {
            this.onClose();
        }).bounds(leftPos + bgWidth - 24, topPos + 4, 18, 16).build());

        initGameWidgets();
    }

    protected abstract void initGameWidgets();

    public void updateState(String stateJson, long balance) {
        this.lastStateJson = stateJson;
        this.playerBalance = balance;
        onStateUpdated();
    }

    protected void onStateUpdated() {
        // Subclasses re-parse state and adjust widgets if necessary
    }

    protected void sendAction(String action, String data) {
        ClientPlayNetworking.send(new CasinoActionPayload(this.pos, action, data));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        // Draw dark tinted world background
        extractor.fill(0, 0, this.width, this.height, 0x88000000);

        // Draw Casino Machine Cabinet / Table Board
        drawCasinoFrame(extractor, leftPos, topPos, bgWidth, bgHeight);

        // Draw Title & Balance Header
        extractor.text(this.font, this.title.getString(), leftPos + 10, topPos + 8, COLOR_TEXT_GOLD);
        String balStr = "EMC Balance: §e" + playerBalance + " §7(Dep 0.8x / With 1.2x)";
        extractor.text(this.font, balStr, leftPos + 10, topPos + 20, COLOR_TEXT_WHITE);

        // Draw specific game contents
        renderGameContent(extractor, mouseX, mouseY, partialTick);

        // Draw sub-widgets (buttons, etc.)
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    protected abstract void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick);

    protected void drawCasinoFrame(GuiGraphicsExtractor extractor, int x, int y, int w, int h) {
        // Outer wood border
        extractor.fill(x - 4, y - 4, x + w + 4, y + h + 4, COLOR_BORDER_OUTER);
        // Gold trim
        extractor.fill(x - 2, y - 2, x + w + 2, y + h + 2, COLOR_BORDER_INNER);
        // Inner felt background
        extractor.fillGradient(x, y, x + w, y + h, COLOR_FELT_GREEN, COLOR_FELT_DARK);
        // Header separator bar
        extractor.fill(x + 4, y + 32, x + w - 4, y + 33, COLOR_BORDER_INNER);
        // Footer separator bar
        extractor.fill(x + 4, y + h - 28, x + w - 4, y + h - 27, COLOR_BORDER_INNER);
    }

    protected void drawCard(GuiGraphicsExtractor extractor, int x, int y, String cardStr) {
        int cardW = 20;
        int cardH = 28;

        // Card background
        extractor.fill(x, y, x + cardW, y + cardH, COLOR_CARD_BORDER);
        extractor.fill(x + 1, y + 1, x + cardW - 1, y + cardH - 1, COLOR_CARD_BG);

        if ("??".equals(cardStr)) {
            // Face down card
            extractor.fill(x + 2, y + 2, x + cardW - 2, y + cardH - 2, 0xFF882222);
            extractor.centeredText(this.font, "?", x + cardW / 2, y + 9, 0xFFFFFFFF);
            return;
        }

        boolean isRed = cardStr.contains("♥") || cardStr.contains("♦");
        int textColor = isRed ? 0xFFCC1111 : 0xFF111111;

        String rank = cardStr.replaceAll("[^0-9JQKA]", "");
        String suit = cardStr.replaceAll("[0-9JQKA]", "");

        extractor.text(this.font, rank, x + 3, y + 3, textColor);
        extractor.text(this.font, suit, x + 3, y + 14, textColor);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
