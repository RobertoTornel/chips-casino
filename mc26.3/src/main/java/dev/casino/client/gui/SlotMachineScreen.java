package dev.casino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.casino.game.SlotMachineEngine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class SlotMachineScreen extends BaseCasinoScreen {

    private long betAmount = 20;
    private boolean spinning = false;
    private int[][] window = new int[3][3]; // [reel][row]
    private int[] reelStop = new int[3];
    private long lastPayout = 0;
    private boolean lastWon = false;

    public SlotMachineScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Slot Machine"), pos, initialStateJson, playerBalance);
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
        addRenderableWidget(SpriteButton.of(contentX() + 60, betY, 30, 16, "50", b -> betAmount = 50));
        addRenderableWidget(SpriteButton.of(contentX() + 94, betY, 34, 16, "100", b -> betAmount = 100));

        // Spin action button
        int actY = contentY() + 24;
        addRenderableWidget(Button.builder(Component.literal("§e§lSPIN"), b -> {
            JsonObject json = new JsonObject();
            json.addProperty("bet", betAmount);
            sendAction("SPIN", GSON.toJson(json));
        }).bounds(contentX(), actY, 110, 24).build());
    }

    @Override
    protected void onStateUpdated() {
        try {
            JsonObject obj = GSON.fromJson(lastStateJson, JsonObject.class);
            if (obj == null) {
                return;
            }
            if (obj.has("bet")) {
                betAmount = obj.get("bet").getAsLong();
            }
            if (obj.has("payout")) {
                lastPayout = obj.get("payout").getAsLong();
            }
            if (obj.has("won")) {
                lastWon = obj.get("won").getAsBoolean();
            }
            if (obj.has("visible")) {
                JsonArray visible = obj.getAsJsonArray("visible");
                for (int r = 0; r < 3 && r < visible.size(); r++) {
                    JsonArray row = visible.get(r).getAsJsonArray();
                    for (int c = 0; c < 3 && c < row.size(); c++) {
                        window[r][c] = row.get(c).getAsInt();
                    }
                }
            }
            if (obj.has("stops")) {
                JsonArray stops = obj.getAsJsonArray("stops");
                for (int r = 0; r < 3 && r < stops.size(); r++) {
                    reelStop[r] = stops.get(r).getAsInt();
                }
            }
            spinning = false;
        } catch (Exception ignored) {
            // leave last good render on screen
        }
    }

    @Override
    protected void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.text(this.font, "SPIN TO WIN (OR LOSE)", contentX(), contentY() + 16, COLOR_SLOT_NEON);
        // Bet label
        extractor.text(this.font, "Bet: §e" + betAmount + " Chips", contentX(), contentY() + 54, COLOR_TEXT_WHITE);

        // Payout result banner
        int bannerY = contentY() + 114;
        if (lastWon) {
            extractor.text(this.font, "§a§lSlot win +§e" + lastPayout + " §aChips!", contentX(), bannerY, COLOR_TEXT_GREEN);
        } else if (lastPayout == 0 && !spinning) {
            extractor.text(this.font, "§cNo win.", contentX(), bannerY, COLOR_TEXT_RED);
        }

        // Three reel strips on the right half
        int reelBaseY = contentY() + 22;
        int reelX0 = contentX() + 150;
        int reelW = 40;
        int reelH = 92;

        // Backdrop for the whole reel area
        extractor.fill(reelX0 - 2, reelBaseY - 2, reelX0 + 3 * reelW + 2, reelBaseY + reelH + 2, 0xFF2A221C);
        extractor.blit(CasinoSprites.SLOT_REEL_WINDOW, reelX0, reelBaseY, reelW * 3, reelH, 0f, 0f, 1f, 1f);

        for (int r = 0; r < 3; r++) {
            int rx = reelX0 + r * reelW + 12; // center of the strip
            for (int row = 0; row < 3; row++) {
                int symY = reelBaseY + 8 + row * 28;
                int sym = window[r][row];
                if (sym >= 0 && sym < 5) {
                    extractor.blit(spriteFor(sym), rx - 8, symY - 8, 16, 16, 0f, 0f, 1f, 1f);
                }
                // reel divider line between strips
                if (r < 2 && row == 0) {
                    extractor.fill(reelX0 + (r + 1) * reelW - 1, reelBaseY, reelX0 + (r + 1) * reelW, reelBaseY + reelH, 0x40000000);
                }
            }
        }
    }

    private static Identifier spriteFor(int sym) {
        return switch (sym) {
            case 0 -> CasinoSprites.SYMBOL_CHERRY;
            case 1 -> CasinoSprites.SYMBOL_LEMON;
            case 2 -> CasinoSprites.SYMBOL_BELL;
            case 3 -> CasinoSprites.SYMBOL_BAR;
            default -> CasinoSprites.SYMBOL_SEVEN;
        };
    }
}
