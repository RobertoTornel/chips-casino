package dev.casino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class BlackjackScreen extends BaseCasinoScreen {
    private long betAmount = 50;
    private String status = "BETTING";
    private String result = "NONE";
    private int playerTotal = 0;
    private int dealerTotal = 0;
    private long payout = 0;
    private final List<String> playerCards = new ArrayList<>();
    private final List<String> dealerCards = new ArrayList<>();

    public BlackjackScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Blackjack Table (Pays 3:2)"), pos, initialStateJson, playerBalance);
        this.bgWidth = 340;
        this.bgHeight = 220;
    }

    @Override
    protected void initGameWidgets() {
        onStateUpdated();

        // Bet selectors
        int betY = contentY() + 2;
        addRenderableWidget(SpriteButton.of(contentX(), betY, 26, 16, "10", b -> betAmount = 10));
        addRenderableWidget(SpriteButton.of(contentX() + 30, betY, 26, 16, "50", b -> betAmount = 50));
        addRenderableWidget(SpriteButton.of(contentX() + 60, betY, 32, 16, "100", b -> betAmount = 100));
        addRenderableWidget(SpriteButton.of(contentX() + 96, betY, 32, 16, "500", b -> betAmount = 500));

        // Game actions
        int actY = contentY() + 124;
        addRenderableWidget(SpriteButton.primary(contentX(), actY, 55, 20, "Deal", b -> {
            JsonObject json = new JsonObject();
            json.addProperty("bet", betAmount);
            sendAction("DEAL", GSON.toJson(json));
        }));

        addRenderableWidget(SpriteButton.of(contentX() + 60, actY, 50, 20, "Hit", b -> sendAction("HIT", "{}")));
        addRenderableWidget(SpriteButton.of(contentX() + 115, actY, 55, 20, "Stand", b -> sendAction("STAND", "{}")));
        addRenderableWidget(SpriteButton.of(contentX() + 175, actY, 60, 20, "Double", b -> sendAction("DOUBLE", "{}")));
    }

    @Override
    protected void onStateUpdated() {
        try {
            JsonObject obj = GSON.fromJson(lastStateJson, JsonObject.class);
            if (obj != null) {
                if (obj.has("status")) this.status = obj.get("status").getAsString();
                if (obj.has("result")) this.result = obj.get("result").getAsString();
                if (obj.has("playerTotal")) this.playerTotal = obj.get("playerTotal").getAsInt();
                if (obj.has("dealerTotal")) this.dealerTotal = obj.get("dealerTotal").getAsInt();
                if (obj.has("payout")) this.payout = obj.get("payout").getAsLong();

                playerCards.clear();
                if (obj.has("playerCards")) {
                    for (var el : obj.getAsJsonArray("playerCards")) playerCards.add(el.getAsString());
                }
                dealerCards.clear();
                if (obj.has("dealerCards")) {
                    for (var el : obj.getAsJsonArray("dealerCards")) dealerCards.add(el.getAsString());
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void renderGameContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.drawString(this.font, "Current Bet: §e" + betAmount + " Chips", contentX() + 140, contentY() + 6, COLOR_TEXT_WHITE, false);

        // Dealer Section
        int dY = contentY() + 24;
        graphics.drawString(this.font, "Dealer (" + dealerTotal + "):", contentX() + 2, dY + 8, COLOR_TEXT_GOLD, false);
        for (int i = 0; i < dealerCards.size(); i++) {
            drawCard(graphics, contentX() + 80 + (i * 26), dY, dealerCards.get(i));
        }

        // Player Section
        int pY = contentY() + 69;
        graphics.drawString(this.font, "Player (" + playerTotal + "):", contentX() + 2, pY + 8, COLOR_TEXT_GOLD, false);
        for (int i = 0; i < playerCards.size(); i++) {
            drawCard(graphics, contentX() + 80 + (i * 26), pY, playerCards.get(i));
        }

        // Result Banner
        if (!"NONE".equals(result)) {
            String banner;
            int color;
            switch (result) {
                case "BLACKJACK" -> { banner = "★ BLACKJACK! Won " + payout + " Chips ★"; color = COLOR_TEXT_GREEN; }
                case "PLAYER_WIN", "DEALER_BUST" -> { banner = "YOU WON! +" + payout + " Chips"; color = COLOR_TEXT_GREEN; }
                case "PUSH" -> { banner = "PUSH (Tie) - Bet returned"; color = COLOR_TEXT_GOLD; }
                case "PLAYER_BUST" -> { banner = "BUSTED! Lost bet"; color = COLOR_TEXT_RED; }
                default -> { banner = "Dealer Wins"; color = COLOR_TEXT_RED; }
            }
            graphics.drawCenteredString(this.font, banner, leftPos + bgWidth / 2, contentY() + 108, color);
        }
    }
}
