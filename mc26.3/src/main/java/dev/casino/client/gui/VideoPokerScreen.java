package dev.casino.client.gui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class VideoPokerScreen extends BaseCasinoScreen {
    private long betAmount = 50;
    private String state = "BETTING";
    private String handName = "";
    private long payout = 0;
    private int multiplier = 0;
    private final List<String> cards = new ArrayList<>();
    private final boolean[] held = new boolean[5];

    public VideoPokerScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Video Poker (Jacks or Better)"), pos, initialStateJson, playerBalance);
        this.bgWidth = 350;
        this.bgHeight = 230;
    }

    @Override
    protected void initGameWidgets() {
        onStateUpdated();

        // Bet selectors
        int betY = topPos + 36;
        addRenderableWidget(SpriteButton.of(leftPos + 10, betY, 26, 16, "10", b -> betAmount = 10));
        addRenderableWidget(SpriteButton.of(leftPos + 40, betY, 26, 16, "50", b -> betAmount = 50));
        addRenderableWidget(SpriteButton.of(leftPos + 70, betY, 32, 16, "100", b -> betAmount = 100));

        // HOLD toggle buttons for 5 cards
        int cardX = leftPos + 35;
        int cardY = topPos + 90;
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            addRenderableWidget(Button.builder(Component.literal("HOLD"), b -> {
                JsonObject json = new JsonObject();
                json.addProperty("index", idx);
                sendAction("TOGGLE_HOLD", GSON.toJson(json));
            }).bounds(cardX + (i * 56), cardY + 34, 46, 16).build());
        }

        // Deal / Draw action buttons
        int actY = topPos + 168;
        addRenderableWidget(Button.builder(Component.literal("§aDEAL"), b -> {
            JsonObject json = new JsonObject();
            json.addProperty("bet", betAmount);
            sendAction("DEAL", GSON.toJson(json));
        }).bounds(leftPos + 80, actY, 80, 20).build());

        addRenderableWidget(Button.builder(Component.literal("§eDRAW"), b -> {
            sendAction("DRAW", "{}");
        }).bounds(leftPos + 180, actY, 80, 20).build());
    }

    @Override
    protected void onStateUpdated() {
        try {
            JsonObject obj = GSON.fromJson(lastStateJson, JsonObject.class);
            if (obj != null) {
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
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.text(this.font, "Bet: §e" + betAmount + " Chips", leftPos + 115, topPos + 40, COLOR_TEXT_WHITE);

        // Paytable summary banner
        int ptY = topPos + 56;
        extractor.fill(leftPos + 10, ptY, leftPos + bgWidth - 10, ptY + 28, 0xFF081A0B);
        extractor.text(this.font, "§eRoyal Flush: 800x  §fStr Flush: 50x  §e4 of a Kind: 25x", leftPos + 16, ptY + 4, COLOR_TEXT_WHITE);
        extractor.text(this.font, "§fFull House: 9x  §eFlush: 6x  §fStraight: 4x  §e3 of a Kind: 3x  §fJacks+: 1x", leftPos + 16, ptY + 16, COLOR_TEXT_WHITE);

        // 5 cards
        int cardX = leftPos + 35;
        int cardY = topPos + 90;
        for (int i = 0; i < 5; i++) {
            int cx = cardX + (i * 56);
            if (i < cards.size()) {
                drawCard(extractor, cx + 10, cardY, cards.get(i));
            } else {
                drawCard(extractor, cx + 10, cardY, "??");
            }
            if (held[i]) {
                extractor.centeredText(this.font, "§e[HELD]", cx + 22, cardY - 10, COLOR_TEXT_GOLD);
            }
        }

        // Result text
        if (!handName.isEmpty()) {
            String res = (payout > 0) ? "§a" + handName + " (" + multiplier + "x) - Won +" + payout + " Chips!" : "§7" + handName + " - No Win";
            extractor.centeredText(this.font, res, leftPos + bgWidth / 2, topPos + 150, COLOR_TEXT_WHITE);
        }
    }
}
