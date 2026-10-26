package dev.emccasino.client.gui;

import com.google.gson.JsonObject;
import dev.emccasino.game.BaccaratEngine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class BaccaratScreen extends BaseCasinoScreen {
    private long betAmount = 100;
    private BaccaratEngine.BetTarget target = BaccaratEngine.BetTarget.PLAYER;
    private String outcome = "NONE";
    private int playerTotal = 0;
    private int bankerTotal = 0;
    private long payout = 0;
    private final List<String> playerCards = new ArrayList<>();
    private final List<String> bankerCards = new ArrayList<>();

    public BaccaratScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Baccarat (Punto Banco)"), pos, initialStateJson, playerBalance);
        this.bgWidth = 340;
        this.bgHeight = 220;
    }

    @Override
    protected void initGameWidgets() {
        onStateUpdated();

        // Bet amounts
        int betY = topPos + 38;
        addRenderableWidget(Button.builder(Component.literal("50"), b -> betAmount = 50).bounds(leftPos + 10, betY, 28, 16).build());
        addRenderableWidget(Button.builder(Component.literal("100"), b -> betAmount = 100).bounds(leftPos + 42, betY, 32, 16).build());
        addRenderableWidget(Button.builder(Component.literal("500"), b -> betAmount = 500).bounds(leftPos + 78, betY, 32, 16).build());

        // Target selector buttons
        int tgtY = topPos + 58;
        addRenderableWidget(Button.builder(Component.literal("Player (1:1)"), b -> target = BaccaratEngine.BetTarget.PLAYER).bounds(leftPos + 10, tgtY, 95, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Banker (0.95:1)"), b -> target = BaccaratEngine.BetTarget.BANKER).bounds(leftPos + 110, tgtY, 110, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Tie (8:1)"), b -> target = BaccaratEngine.BetTarget.TIE).bounds(leftPos + 225, tgtY, 80, 18).build());

        // Play action button
        int actY = topPos + 160;
        addRenderableWidget(Button.builder(Component.literal("§a§lDEAL HAND"), b -> {
            JsonObject json = new JsonObject();
            json.addProperty("target", target.name());
            json.addProperty("bet", betAmount);
            sendAction("PLAY", GSON.toJson(json));
        }).bounds(leftPos + 10, actY, 120, 20).build());
    }

    @Override
    protected void onStateUpdated() {
        try {
            JsonObject obj = GSON.fromJson(lastStateJson, JsonObject.class);
            if (obj != null) {
                if (obj.has("outcome")) this.outcome = obj.get("outcome").getAsString();
                if (obj.has("playerTotal")) this.playerTotal = obj.get("playerTotal").getAsInt();
                if (obj.has("bankerTotal")) this.bankerTotal = obj.get("bankerTotal").getAsInt();
                if (obj.has("payout")) this.payout = obj.get("payout").getAsLong();

                playerCards.clear();
                if (obj.has("playerCards")) {
                    for (var el : obj.getAsJsonArray("playerCards")) playerCards.add(el.getAsString());
                }
                bankerCards.clear();
                if (obj.has("bankerCards")) {
                    for (var el : obj.getAsJsonArray("bankerCards")) bankerCards.add(el.getAsString());
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.text(this.font, "Bet: §e" + betAmount + " EMC §7on §b" + target.name(), leftPos + 120, topPos + 42, COLOR_TEXT_WHITE);

        // Player side
        int pY = topPos + 86;
        extractor.text(this.font, "§bPLAYER §f(" + playerTotal + " pts)", leftPos + 14, pY - 2, COLOR_TEXT_WHITE);
        for (int i = 0; i < playerCards.size(); i++) {
            drawCard(extractor, leftPos + 14 + (i * 26), pY + 10, playerCards.get(i));
        }

        // Banker side
        int bX = leftPos + 180;
        extractor.text(this.font, "§cBANKER §f(" + bankerTotal + " pts)", bX, pY - 2, COLOR_TEXT_WHITE);
        for (int i = 0; i < bankerCards.size(); i++) {
            drawCard(extractor, bX + (i * 26), pY + 10, bankerCards.get(i));
        }

        // Result banner
        if (!"NONE".equals(outcome)) {
            String banner = outcome.replace("_", " ");
            int color = (payout > 0) ? COLOR_TEXT_GREEN : COLOR_TEXT_RED;
            String text = banner + " - " + ((payout > 0) ? "Won +" + payout + " EMC" : "Lost bet");
            extractor.centeredText(this.font, "§l" + text, leftPos + bgWidth / 2, topPos + 138, color);
        }
    }
}
