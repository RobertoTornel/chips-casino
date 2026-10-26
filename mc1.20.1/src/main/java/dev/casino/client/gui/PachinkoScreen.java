package dev.casino.client.gui;

import com.google.gson.JsonObject;
import dev.casino.game.PachinkoEngine;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class PachinkoScreen extends BaseCasinoScreen {
    private long betAmount = 20;
    private String outcome = "NONE";
    private String label = "";
    private long payout = 0;
    private final List<int[]> trajectory = new ArrayList<>();

    public PachinkoScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Pachinko Fever Cabinet"), pos, initialStateJson, playerBalance);
        this.bgWidth = 340;
        this.bgHeight = 230;
    }

    @Override
    protected void initGameWidgets() {
        onStateUpdated();

        // Bet selectors
        int betY = contentY();
        addRenderableWidget(SpriteButton.of(contentX(), betY, 26, 16, "10", b -> betAmount = 10));
        addRenderableWidget(SpriteButton.of(contentX() + 30, betY, 26, 16, "20", b -> betAmount = 20));
        addRenderableWidget(SpriteButton.of(contentX() + 60, betY, 26, 16, "50", b -> betAmount = 50));
        addRenderableWidget(SpriteButton.of(contentX() + 90, betY, 32, 16, "100", b -> betAmount = 100));

        // Drop Ball Action Button
        int actY = contentY() + 24;
        addRenderableWidget(SpriteButton.of(contentX(), actY, 110, 24, "LAUNCH BALL", b -> {
            JsonObject json = new JsonObject();
            json.addProperty("bet", betAmount);
            sendAction("DROP", GSON.toJson(json));
        }));
    }

    @Override
    protected void onStateUpdated() {
        try {
            JsonObject obj = GSON.fromJson(lastStateJson, JsonObject.class);
            if (obj != null) {
                if (obj.has("outcome")) this.outcome = obj.get("outcome").getAsString();
                if (obj.has("label")) this.label = obj.get("label").getAsString();
                if (obj.has("payout")) this.payout = obj.get("payout").getAsLong();

                trajectory.clear();
                if (obj.has("trajectory")) {
                    for (var el : obj.getAsJsonArray("trajectory")) {
                        var pair = el.getAsJsonArray();
                        trajectory.add(new int[]{pair.get(0).getAsInt(), pair.get(1).getAsInt()});
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void renderGameContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.drawString(this.font, "Ball Bet: §e" + betAmount + " Chips", contentX(), contentY() + 54, COLOR_TEXT_WHITE, false);

        // Payout table info
        graphics.drawString(this.font, "§6[Payouts]", contentX(), contentY() + 74, COLOR_TEXT_GOLD, false);
        graphics.drawString(this.font, "★ Center: §e100x Fever", contentX(), contentY() + 86, COLOR_TEXT_GOLD, false);
        graphics.drawString(this.font, "♦ Tulips: §a15x Pockets", contentX(), contentY() + 98, COLOR_TEXT_GREEN, false);
        graphics.drawString(this.font, "• Outer: §b5x Pins", contentX(), contentY() + 110, COLOR_TEXT_WHITE, false);
        graphics.drawString(this.font, "✕ Gutters: §70x (Lost)", contentX(), contentY() + 122, COLOR_TEXT_WHITE, false);

        // Result text
        if (!label.isEmpty()) {
            int color = (payout > 0) ? COLOR_TEXT_GREEN : COLOR_TEXT_RED;
            String res = (payout > 0) ? "§a" + label + " - Won +" + payout + " Chips!" : "§c" + label;
            graphics.drawString(this.font, res, contentX(), contentY() + 140, color, false);
        }

        // Draw Pachinko Board Cabinet Frame (right half)
        int boardX = contentX() + 135;
        int boardY = contentY();
        int boardW = PachinkoEngine.BOARD_WIDTH;
        int boardH = 150;

        graphics.fill(boardX - 2, boardY - 2, boardX + boardW + 2, boardY + boardH + 2, 0xFF4A3B2C);
        blitTexture(graphics, CasinoSprites.PACHINKO_BOARD, boardX, boardY, boardW, boardH,
                CasinoSprites.PACHINKO_BOARD_W, CasinoSprites.PACHINKO_BOARD_H); // Sprite board (resource-pack overridable)

        // Draw Pins grid
        for (int r = 0; r < PachinkoEngine.ROWS; r++) {
            int py = boardY + 12 + (r * 12);
            int offset = (r % 2 == 0) ? 0 : 8;
            for (int p = 0; p < PachinkoEngine.PINS_PER_ROW; p++) {
                int px = boardX + 16 + (p * 17) + offset;
                graphics.fill(px, py, px + 2, py + 2, 0xFFFFD700); // Brass pin
            }
        }

        // Draw Catch Pockets at bottom
        int botY = boardY + boardH - 14;
        int center = boardX + boardW / 2;

        // Center Fever Jackpot Pocket
        graphics.fill(center - 10, botY, center + 10, botY + 12, 0xFFFF2222);
        graphics.drawCenteredString(this.font, "100x", center, botY + 2, 0xFFFFFFFF);

        // Left & right tulips (15x)
        graphics.fill(center - 45, botY, center - 25, botY + 12, 0xFF22AA22);
        graphics.drawCenteredString(this.font, "15x", center - 35, botY + 2, 0xFFFFFFFF);

        graphics.fill(center + 25, botY, center + 45, botY + 12, 0xFF22AA22);
        graphics.drawCenteredString(this.font, "15x", center + 35, botY + 2, 0xFFFFFFFF);

        // Outer pins (5x)
        graphics.fill(center - 75, botY, center - 55, botY + 12, 0xFF2266DD);
        graphics.drawCenteredString(this.font, "5x", center - 65, botY + 2, 0xFFFFFFFF);

        graphics.fill(center + 55, botY, center + 75, botY + 12, 0xFF2266DD);
        graphics.drawCenteredString(this.font, "5x", center + 65, botY + 2, 0xFFFFFFFF);

        // Draw animated/plotted ball drop trajectory
        for (int i = 0; i < trajectory.size(); i++) {
            int[] pt = trajectory.get(i);
            int bx = boardX + pt[0];
            int by = boardY + (pt[1] * boardH / PachinkoEngine.BOARD_HEIGHT);

            if (i == trajectory.size() - 1) {
                // Final ball position: shiny silver ball
                graphics.fill(bx - 3, by - 3, bx + 3, by + 3, 0xFFE0E0E0);
                graphics.fill(bx - 1, by - 1, bx + 1, by + 1, 0xFFFFFFFF);
            } else {
                // Ball trail
                graphics.fill(bx - 1, by - 1, bx + 1, by + 1, 0x88AAAAAA);
            }
        }
    }
}
