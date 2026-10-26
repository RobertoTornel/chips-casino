package dev.emccasino.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.emccasino.EmcCasino;
import dev.emccasino.network.CasinoNetwork;
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
 * All visual elements are drawn from texture sprites under assets/emc_casino/textures/gui/
 * so resource packs (including the built-in dark_mode pack) can fully retheme the UI.
 */
public abstract class BaseCasinoScreen extends Screen {
    protected static final Gson GSON = new Gson();

    protected final BlockPos pos;
    protected long playerBalance;
    protected String lastStateJson;

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
        CasinoNetwork.sendAction(this.pos, action, data);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        // Sprite-driven casino frame (resource-pack overridable)
        drawCasinoFrame(graphics, leftPos, topPos, bgWidth, bgHeight);

        // Header bar sprite
        blitTexture(graphics, CasinoSprites.HEADER_BAR, leftPos, topPos, bgWidth, 32);

        // Balance bar sprite at bottom
        blitTexture(graphics, CasinoSprites.BALANCE_BAR, leftPos, topPos + bgHeight - 28, bgWidth, 16);

        // Title & Balance text (over sprites)
        graphics.drawString(this.font, this.title.getString(), leftPos + 10, topPos + 8, COLOR_TEXT_GOLD, false);
        String balStr = "EMC Balance: §e" + playerBalance + " §7(Dep 0.8x / With 1.2x)";
        graphics.drawString(this.font, balStr, leftPos + 10, topPos + 20, COLOR_TEXT_WHITE, false);

        // Game contents
        renderGameContent(graphics, mouseX, mouseY, partialTick);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    protected abstract void renderGameContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    /**
     * Draws the casino cabinet frame using the casino_frame sprite texture.
     * Resource packs can override assets/emc_casino/textures/gui/casino_frame.png
     * to retheme the entire cabinet appearance.
     */
    protected void drawCasinoFrame(GuiGraphics graphics, int x, int y, int w, int h) {
        blitTexture(graphics, CasinoSprites.CASINO_FRAME, x - 4, y - 4, w + 8, h + 8);
    }

    /**
     * Draws a playing card using sprite textures for the card face/back.
     * Card textures at assets/emc_casino/textures/gui/cards/ are resource-pack overridable.
     */
    protected void drawCard(GuiGraphics graphics, int x, int y, String cardStr) {
        int cardW = 20;
        int cardH = 28;

        if ("??".equals(cardStr)) {
            blitTexture(graphics, CasinoSprites.CARD_BACK, x, y, cardW, cardH);
            graphics.drawCenteredString(this.font, "?", x + cardW / 2, y + 9, 0xFFFFFFFF);
            return;
        }

        blitTexture(graphics, CasinoSprites.CARD_FRONT, x, y, cardW, cardH);

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
        blitTexture(graphics, CasinoSprites.CHIP, x, y, 16, 16);
    }

    /**
     * Blit a texture stretched to the given width and height.
     * In 1.20.1 GuiGraphics, blit(ResourceLocation, x, y, u, v, w, h, texW, texH) is used.
     */
    protected void blitTexture(GuiGraphics graphics, ResourceLocation texture, int x, int y, int w, int h) {
        RenderSystem.enableBlend();
        graphics.blit(texture, x, y, 0, 0, w, h, w, h);
        RenderSystem.disableBlend();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
