package dev.macasino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.macasino.game.PachinkoEngine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    protected void renderGameContent(GuiGraphicsExtractor e, int mouseX, int mouseY, float partialTick) {
        // Header
        e.text(this.font, "PACHINKO", contentX(), contentY() + 2, COLOR_PACHINKO);

        // Bet display
        e.text(this.font, "Bet: §e" + betAmount + " §7Chips", contentX(), contentY() + 20, COLOR_TEXT_WHITE);

        // Payout result banner
        if (!label.isEmpty()) {
            int color = (payout > 0) ? COLOR_TEXT_GREEN : COLOR_TEXT_RED;
            String res = (payout > 0) ? "§a" + label + " - Won +§e" + payout + " §aChips!"
                    : "§c" + label;
            e.text(this.font, res, contentX(), contentY() + 42, color);
        }

        // Right side: the board
        int boardX = contentX() + 135;
        int boardY = contentY() + 30;
        int boardW = PachinkoEngine.BOARD_WIDTH;
        int boardH = 140;

        e.fill(boardX - 2, boardY - 2, boardX + boardW + 2, boardY + boardH + 2, 0xFF4A3B2C);
        e.blit(CasinoSprites.PACHINKO_BOARD, boardX, boardY, boardW, boardH, 0f, 0f, 128f, 128f);

        // Draw pins
        for (int r = 0; r < PachinkoEngine.ROWS; r++) {
            int py = boardY + 12 + (r * 12);
            int offset = (r % 2 == 0) ? 0 : 8;
            for (int p = 0; p < PachinkoEngine.PINS_PER_ROW; p++) {
                int px = boardX + 16 + (p * 17) + offset;
                e.fill(px, py, px + 2, py + 2, 0xFFFFD700);
            }
        }

        // Payout pockets at bottom
        int botY = boardY + boardH - 14;
        int center = boardX + boardW / 2;

        e.fill(center - 10, botY, center + 10, botY + 12, 0xFFFF2222);
        e.centeredText(this.font, "100x", center, botY + 2, 0xFFFFFFFF);

        e.fill(center - 45, botY, center - 25, botY + 12, 0xFF22AA22);
        e.centeredText(this.font, "15x", center - 35, botY + 2, 0xFFFFFFFF);

        e.fill(center + 25, botY, center + 45, botY + 12, 0xFF22AA22);
        e.centeredText(this.font, "15x", center + 35, botY + 2, 0xFFFFFFFF);

        e.fill(center - 75, botY, center - 55, botY + 12, 0xFF2266DD);
        e.centeredText(this.font, "5x", center - 65, botY + 2, 0xFFFFFFFF);

        e.fill(center + 55, botY, center + 75, botY + 12, 0xFF2266DD);
        e.centeredText(this.font, "5x", center + 65, botY + 2, 0xFFFFFFFF);

        // Draw ball trajectory
        for (int i = 0; i < trajectory.size(); i++) {
            int[] pt = trajectory.get(i);
            int bx = boardX + pt[0];
            int by = boardY + (pt[1] * boardH / PachinkoEngine.BOARD_HEIGHT);

            if (i == trajectory.size() - 1) {
                e.fill(bx - 3, by - 3, bx + 3, by + 3, 0xFFE0E0E0);
                e.fill(bx - 1, by - 1, bx + 1, by + 1, 0xFFFFFFFF);
            } else {
                e.fill(bx - 1, by - 1, bx + 1, by + 1, 0x88AAAAAA);
            }
        }

        // Left side: payout reference (compact)
        int refY = contentY() + 70;
        e.text(this.font, "\u00a76Payouts:", contentX(), refY, COLOR_TEXT_GOLD);
        e.text(this.font, "\u00a7eCenter: 100x", contentX(), refY + 12, COLOR_TEXT_GOLD);
        e.text(this.font, "\u00a7aTulips: 15x", contentX(), refY + 24, COLOR_TEXT_GREEN);
        e.text(this.font, "\u00a7bOuter pins: 5x", contentX(), refY + 36, COLOR_TEXT_WHITE);
        e.text(this.font, "\u00a77Gutters: 0x", contentX(), refY + 48, COLOR_TEXT_DIM);
    }
}
