package dev.macasino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

        // Bet selector - left side, clearly labelled
        int betY = contentY() + 4;
        addRenderableWidget(SpriteButton.of(contentX(), betY, 26, 16, "10", b -> betAmount = 10));
        addRenderableWidget(SpriteButton.of(contentX() + 30, betY, 26, 16, "50", b -> betAmount = 50));
        addRenderableWidget(SpriteButton.of(contentX() + 60, betY, 30, 16, "100", b -> betAmount = 100));

        // Spin button - prominent, right side
        int actY = contentY() + 26;
        addRenderableWidget(SpriteButton.primary(contentX() + contentW() - 92, actY, 80, 20, "SPIN",
                b -> {
                    JsonObject json = new JsonObject();
                    json.addProperty("bet", betAmount);
                    sendAction("SPIN", GSON.toJson(json));
                }));
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
        // Header
        extractor.text(this.font, "SPIN TO WIN", contentX(), contentY() + 2, COLOR_SLOT_NEON);

        // Bet display - prominent
        extractor.text(this.font, "Bet: §e" + betAmount + " §7Chips", contentX(), contentY() + 20, COLOR_TEXT_WHITE);

        // Payout result banner - center, high contrast
        int bannerY = contentY() + 100;
        if (lastWon) {
            extractor.text(this.font, "§a§lWIN +§e" + lastPayout + " §aChips!", contentX(), bannerY, COLOR_TEXT_GREEN);
        } else if (lastPayout == 0 && !spinning) {
            extractor.text(this.font, "§cNo win.", contentX(), bannerY, COLOR_TEXT_RED);
        }

        // Three reel strips on the right half
        int reelBaseY = contentY() + 40;
        int reelX0 = contentX() + 150;
        int reelW = 40;
        int reelH = 92;

        // Backdrop for the whole reel area
        extractor.fill(reelX0 - 2, reelBaseY - 2, reelX0 + 3 * reelW + 2, reelBaseY + reelH + 2, 0xFF2A221C);
        extractor.blit(CasinoSprites.SLOT_REEL_WINDOW, reelX0, reelBaseY, reelW * 3, reelH, 0f, 0f, 96f, 96f);

        for (int r = 0; r < 3; r++) {
            int rx = reelX0 + r * reelW + 12; // center of the strip
            for (int row = 0; row < 3; row++) {
                int symY = reelBaseY + 8 + row * 28;
                int sym = window[r][row];
                if (sym >= 0 && sym < 5) {
                    extractor.blit(spriteFor(sym), rx - 8, symY - 8, 16, 16, 0f, 0f, 16f, 16f);
                }
                // reel divider line between strips
                if (r < 2 && row == 0) {
                    extractor.fill(reelX0 + (r + 1) * reelW - 1, reelBaseY,
                            reelX0 + (r + 1) * reelW, reelBaseY + reelH, 0x40000000);
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
