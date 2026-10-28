package dev.macasino.client.gui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public class WheelOfFortuneScreen extends BaseCasinoScreen {

    private long betAmount = 10;
    private int lastSegment = 0;
    private long lastPayout = 0;
    private boolean hasSpun = false;

    public WheelOfFortuneScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Wheel of Fortune"), pos, initialStateJson, playerBalance);
        this.bgWidth = 340;
        this.bgHeight = 230;
    }

    @Override
    protected void initGameWidgets() {
        onStateUpdated();
        int betY = contentY() + 4;
        addRenderableWidget(SpriteButton.of(contentX(), betY, 26, 16, "10", b -> betAmount = 10));
        addRenderableWidget(SpriteButton.of(contentX() + 30, betY, 26, 16, "50", b -> betAmount = 50));
        addRenderableWidget(SpriteButton.of(contentX() + 60, betY, 30, 16, "100", b -> betAmount = 100));
        int actY = contentY() + 26;
        addRenderableWidget(SpriteButton.primary(contentX(), actY, 110, 20, "SPIN WHEEL", b -> {
            JsonObject json = new JsonObject();
            json.addProperty("bet", betAmount);
            sendAction("SET_BET", GSON.toJson(json));
        }));
    }

    @Override
    protected void onStateUpdated() {
        try {
            JsonObject obj = GSON.fromJson(lastStateJson, JsonObject.class);
            if (obj == null) return;
            if (obj.has("bet")) betAmount = obj.get("bet").getAsLong();
            if (obj.has("lastSegment")) lastSegment = obj.get("lastSegment").getAsInt();
            if (obj.has("lastPayout")) lastPayout = obj.get("lastPayout").getAsLong();
            hasSpun = lastSegment >= 0;
        } catch (Exception ignored) {}
    }

    @Override
    protected void renderGameContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.text(this.font, "WHEEL OF FORTUNE", contentX(), contentY() + 2, 0xFFB32630);
        g.text(this.font, "Bet: §e" + betAmount + " §7Chips", contentX(), contentY() + 20, COLOR_TEXT_WHITE);

        if (hasSpun) {
            String[] segments = {"2x", "5x", "10x", "20x", "50x", "100x", "5x", "2x"};
            g.text(this.font, "Landed on: §e" + segments[lastSegment], contentX(), contentY() + 50, COLOR_TEXT_GOLD);
            if (lastPayout > 0) {
                g.text(this.font, "§aWon +§e" + lastPayout + " §aChips!", contentX(), contentY() + 66, COLOR_TEXT_GREEN);
            } else {
                g.text(this.font, "§cNo win!", contentX(), contentY() + 66, COLOR_TEXT_RED);
            }
        } else {
            g.text(this.font, "§7Place your bet and spin", contentX(), contentY() + 50, COLOR_TEXT_GREY);
        }
    }
}
