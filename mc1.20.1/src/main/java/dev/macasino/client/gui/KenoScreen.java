package dev.macasino.client.gui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class KenoScreen extends BaseCasinoScreen {

    private long betAmount = 10;
    private int matches = 0;
    private long lastPayout = 0;
    private boolean hasPlayed = false;

    public KenoScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Keno Board"), pos, initialStateJson, playerBalance);
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
        addRenderableWidget(SpriteButton.primary(contentX(), actY, 110, 20, "PICK NUMBERS", b -> {
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
            if (obj.has("matches")) matches = obj.get("matches").getAsInt();
            if (obj.has("lastPayout")) lastPayout = obj.get("lastPayout").getAsLong();
            hasPlayed = matches >= 0;
        } catch (Exception ignored) {}
    }

    @Override
    protected void renderGameContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.drawString(this.font, "KENO BOARD", contentX(), contentY() + 2, 0xFFB32630, false);
        g.drawString(this.font, "Bet: §e" + betAmount + " §7Chips", contentX(), contentY() + 20, COLOR_TEXT_WHITE, false);

        if (hasPlayed) {
            g.drawString(this.font, "Matches: §e" + matches + "/5", contentX(), contentY() + 50, COLOR_TEXT_GOLD, false);
            if (lastPayout > 0) {
                g.drawString(this.font, "§aWon +§e" + lastPayout + " §aChips!", contentX(), contentY() + 66, COLOR_TEXT_GREEN, false);
            } else {
                g.drawString(this.font, "§cNo matches!", contentX(), contentY() + 66, COLOR_TEXT_RED, false);
            }
        } else {
            g.drawString(this.font, "§7Pick your numbers and play", contentX(), contentY() + 50, COLOR_TEXT_GREY, false);
        }
    }
}
