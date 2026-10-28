package dev.macasino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.macasino.game.PachinkoEngine;
import net.minecraft.client.gui.GuiGraphics;
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
        super(Component.literal("Pachinko"), pos, initialStateJson, playerBalance);
        this.bgWidth = 340;
        this.bgHeight = 230;
    }

    @Override
    protected void initGameWidgets() {
        onStateUpdated();
        int betY = contentY() + 4;
        addRenderableWidget(SpriteButton.of(contentX(), betY, 26, 16, "10", b -> betAmount = 10));
        addRenderableWidget(SpriteButton.of(contentX() + 30, betY, 26, 16, "20", b -> betAmount = 20));
        addRenderableWidget(SpriteButton.of(contentX() + 60, betY, 30, 16, "50", b -> betAmount = 50));
        addRenderableWidget(SpriteButton.of(contentX() + 94, betY, 34, 16, "100", b -> betAmount = 100));
        int actY = contentY() + 26;
        addRenderableWidget(SpriteButton.primary(contentX(), actY, 110, 20, "LAUNCH BALL", b -> {
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
    protected void renderGameContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.drawString(this.font, "PACHINKO", contentX(), contentY() + 2, COLOR_PACHINKO, false);
        g.drawString(this.font, "Bet: §e" + betAmount + " §7Chips", contentX(), contentY() + 20, COLOR_TEXT_WHITE, false);

        if (!label.isEmpty()) {
            int color = (payout > 0) ? COLOR_TEXT_GREEN : COLOR_TEXT_RED;
            String res = (payout > 0) ? "§a" + label + " - Won +§e" + payout + " §aChips!"
                    : "§c" + label;
            g.drawString(this.font, res, contentX(), contentY() + 42, color, false);
        }

        int boardX = contentX() + 135;
        int boardY = contentY() + 30;
        int boardW = PachinkoEngine.BOARD_WIDTH;
        int boardH = 140;

        g.fill(boardX - 2, boardY - 2, boardX + boardW + 2, boardY + boardH + 2, 0xFF4A3B2C);
        g.blit(CasinoSprites.PACHINKO_BOARD, boardX, boardY, 0, 0, boardW, boardH,
                CasinoSprites.PACHINKO_BOARD_W, CasinoSprites.PACHINKO_BOARD_H);

        for (int r = 0; r < PachinkoEngine.ROWS; r++) {
            int py = boardY + 12 + (r * 12);
            int offset = (r % 2 == 0) ? 0 : 8;
            for (int p = 0; p < PachinkoEngine.PINS_PER_ROW; p++) {
                int px = boardX + 16 + (p * 17) + offset;
                g.fill(px, py, px + 2, py + 2, 0xFFFFD700);
            }
        }

        int botY = boardY + boardH - 14;
        int center = boardX + boardW / 2;

        g.fill(center - 10, botY, center + 10, botY + 12, 0xFFFF2222);
        g.drawCenteredString(this.font, "100x", center, botY + 2, 0xFFFFFFFF);

        g.fill(center - 45, botY, center - 25, botY + 12, 0xFF22AA22);
        g.drawCenteredString(this.font, "15x", center - 35, botY + 2, 0xFFFFFFFF);

        g.fill(center + 25, botY, center + 45, botY + 12, 0xFF22AA22);
        g.drawCenteredString(this.font, "15x", center + 35, botY + 2, 0xFFFFFFFF);

        g.fill(center - 75, botY, center - 55, botY + 12, 0xFF2266DD);
        g.drawCenteredString(this.font, "5x", center - 65, botY + 2, 0xFFFFFFFF);

        g.fill(center + 55, botY, center + 75, botY + 12, 0xFF2266DD);
        g.drawCenteredString(this.font, "5x", center + 65, botY + 2, 0xFFFFFFFF);

        for (int i = 0; i < trajectory.size(); i++) {
            int[] pt = trajectory.get(i);
            int bx = boardX + pt[0];
            int by = boardY + (pt[1] * boardH / PachinkoEngine.BOARD_HEIGHT);
            if (i == trajectory.size() - 1) {
                g.fill(bx - 3, by - 3, bx + 3, by + 3, 0xFFE0E0E0);
                g.fill(bx - 1, by - 1, bx + 1, by + 1, 0xFFFFFFFF);
            } else {
                g.fill(bx - 1, by - 1, bx + 1, by + 1, 0x88AAAAAA);
            }
        }

        int refY = contentY() + 70;
        g.drawString(this.font, "\u00a76Payouts:", contentX(), refY, COLOR_TEXT_GOLD, false);
        g.drawString(this.font, "\u00a7eCenter: 100x", contentX(), refY + 12, COLOR_TEXT_GOLD, false);
        g.drawString(this.font, "\u00a7aTulips: 15x", contentX(), refY + 24, COLOR_TEXT_GREEN, false);
        g.drawString(this.font, "\u00a7bOuter pins: 5x", contentX(), refY + 36, COLOR_TEXT_WHITE, false);
        g.drawString(this.font, "\u00a77Gutters: 0x", contentX(), refY + 48, COLOR_TEXT_DIM, false);
    }
}
