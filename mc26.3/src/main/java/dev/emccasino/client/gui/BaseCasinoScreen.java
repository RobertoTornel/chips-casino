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

/**
 * Base casino screen using sprite-driven rendering.
 * All visual elements are drawn from texture sprites under assets/emc_casino/textures/gui/
 * so resource packs (including the built-in dark_mode pack) can fully retheme the UI.
 */
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

        // Common Deposit / Withdraw controls
        addRenderableWidget(Button.builder(Component.literal("§aDeposit Hand (0.8x)"), b -> {
            sendAction("DEPOSIT_MAIN_HAND", "{}");
        }).bounds(leftPos + 10, topPos + bgHeight - 24, 110, 18).build());

        addRenderableWidget(Button.builder(Component.literal("§bBuy Diamond (1.2x)"), b -> {
            JsonObject json = new JsonObject();
            json.addProperty("itemId", "minecraft:diamond");
            json.addProperty("count", 1);
            sendAction("WITHDRAW_ITEM", GSON.toJson(json));
        }).bounds(leftPos + 124, topPos + bgHeight - 24, 110, 18).build());

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

        // Header bar sprite
        extractor.blit(CasinoSprites.HEADER_BAR, leftPos, topPos, bgWidth, 32, 0f, 0f, 1f, 1f);

        // Balance bar sprite at bottom
        extractor.blit(CasinoSprites.BALANCE_BAR, leftPos, topPos + bgHeight - 28, bgWidth, 16, 0f, 0f, 1f, 1f);

        // Title & Balance text (over sprites)
        extractor.text(this.font, this.title.getString(), leftPos + 10, topPos + 8, COLOR_TEXT_GOLD);
        String balStr = "EMC Balance: §e" + playerBalance + " §7(Dep 0.8x / With 1.2x)";
        extractor.text(this.font, balStr, leftPos + 10, topPos + 20, COLOR_TEXT_WHITE);

        // Game-specific content
        renderGameContent(extractor, mouseX, mouseY, partialTick);

        // Sub-widgets (buttons)
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    protected abstract void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick);

    /**
     * Draws the casino cabinet frame using the casino_frame sprite texture.
     * Resource packs can override assets/emc_casino/textures/gui/casino_frame.png
     * to retheme the entire cabinet appearance.
     */
    protected void drawCasinoFrame(GuiGraphicsExtractor extractor, int x, int y, int w, int h) {
        // Main frame sprite (stretched to fill the cabinet area)
        extractor.blit(CasinoSprites.CASINO_FRAME, x - 4, y - 4, w + 8, h + 8, 0f, 0f, 1f, 1f);
    }

    /**
     * Draws a playing card using sprite textures for the card face/back.
     * Card textures at assets/emc_casino/textures/gui/cards/ are resource-pack overridable.
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
     * Resource-pack overridable via assets/emc_casino/textures/gui/chip.png.
     */
    protected void drawChip(GuiGraphicsExtractor extractor, int x, int y) {
        extractor.blit(CasinoSprites.CHIP, x, y, 16, 16, 0f, 0f, 1f, 1f);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
