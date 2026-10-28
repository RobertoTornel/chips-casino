package dev.casino.client.gui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class VideoPokerScreen extends BaseCasinoScreen {

    private static final int CARD_W = 20;
    private static final int CARD_H = 28;

    private long betAmount = 50;
    private String state = "BETTING";
    private String handName = "";
    private long payout = 0;
    private int multiplier = 0;
    private final List<String> cards = new ArrayList<>();
    private final boolean[] held = new boolean[5];

    public VideoPokerScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Video Poker"), pos, initialStateJson, playerBalance);
        this.bgWidth = 350;
        this.bgHeight = 224;
    }

    @Override
    protected void initGameWidgets() {
        onStateUpdated();

        // Bet selector - left side
        int betY = contentY();
        addRenderableWidget(SpriteButton.of(contentX(), betY, 26, 16, "10", b -> betAmount = 10));
        addRenderableWidget(SpriteButton.of(contentX() + 30, betY, 26, 16, "50", b -> betAmount = 50));
        addRenderableWidget(SpriteButton.of(contentX() + 60, betY, 30, 16, "100", b -> betAmount = 100));

        // HOLD toggle buttons above cards
        int cardX = contentX() + 25;
        int cardY = contentY() + 54;
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            addRenderableWidget(SpriteButton.of(cardX + (i * 56) + 10, cardY + 34, 36, 12,
                    held[i] ? "\u00a7aHELD" : "\u00a77hold",
                    b -> {
                        JsonObject json = new JsonObject();
                        json.addProperty("index", idx);
                        sendAction("TOGGLE_HOLD", GSON.toJson(json));
                    }));
        }

        // Deal / Draw action buttons - bottom right
        int actY = contentY() + 132;
        addRenderableWidget(SpriteButton.primary(contentX() + 70, actY, 80, 20, "DEAL", b -> {
            JsonObject json = new JsonObject();
            json.addProperty("bet", betAmount);
            sendAction("DEAL", GSON.toJson(json));
        }));
        addRenderableWidget(SpriteButton.of(contentX() + 170, actY, 80, 20, "DRAW", b -> {
            sendAction("DRAW", "{}");
        }));
    }

    @Override
    protected void onStateUpdated() {
        try {
            JsonObject obj = GSON.fromJson(lastStateJson, JsonObject.class);
            if (obj == null) return;
            if (obj.has("state")) this.state = obj.get("state").getAsString();
            if (obj.has("handName")) this.handName = obj.get("handName").getAsString();
            if (obj.has("payout")) this.payout = obj.get("payout").getAsLong();
            if (obj.has("multiplier")) this.multiplier = obj.get("multiplier").getAsInt();

            cards.clear();
            if (obj.has("cards")) {
                for (var el : obj.getAsJsonArray("cards")) cards.add(el.getAsString());
            }
            if (obj.has("held")) {
                var arr = obj.getAsJsonArray("held");
                for (int i = 0; i < 5 && i < arr.size(); i++) held[i] = arr.get(i).getAsBoolean();
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        // Header with state
        String stateTag = switch (state) {
            case "BETTING" -> "§aBETTING";
            case "DEALT" -> "§eDEALT";
            case "DRAWN" -> "§cDRAWN";
            default -> "§7" + state;
        };
        extractor.text(this.font, "JACKS OR BETTER  §7|  " + stateTag, contentX(), contentY() + 2, COLOR_VIDEOPOKER);

        // Bet display
        extractor.text(this.font, "Bet: §e" + betAmount + " §7Chips", contentX() + 105, contentY() + 4, COLOR_TEXT_WHITE);

        // Paytable summary banner
        int ptY = contentY() + 20;
        extractor.fill(contentX(), ptY, contentX() + contentW() - 10, ptY + 28, 0xFF081A0B);
        extractor.text(this.font, "Royal Flush: 800x   Str Flush: 50x   4 of a Kind: 25x",
                contentX() + 6, ptY + 4, COLOR_TEXT_WHITE);
        extractor.text(this.font, "Full House: 9x   Flush: 6x   Straight: 4x   3 of a Kind: 3x   Jacks+: 1x",
                contentX() + 6, ptY + 16, COLOR_TEXT_WHITE);

        // 5 cards with hold indicators
        int cardX = contentX() + 25;
        int cardY = contentY() + 54;
        for (int i = 0; i < 5; i++) {
            int cx = cardX + (i * 56);
            if (i < cards.size()) {
                drawCard(extractor, cx + 10, cardY, cards.get(i));
            } else {
                drawCard(extractor, cx + 10, cardY, "??");
            }
            if (held[i]) {
                extractor.centeredText(this.font, "\u00a7eHELD", cx + 22, cardY - 10, COLOR_TEXT_GOLD);
            }
        }

        // Result text - centered below cards
        if (!handName.isEmpty()) {
            String res = (payout > 0)
                    ? "\u00a7a" + handName + " (" + multiplier + "x) - Won +\u00a7e" + payout + " \u00a7aChips!"
                    : "\u00a77" + handName + " - No Win";
            extractor.centeredText(this.font, res, leftPos + bgWidth / 2, contentY() + 114,
                    COLOR_TEXT_WHITE);
        }
    }
}
