package dev.casino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.casino.game.RouletteEngine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class RouletteScreen extends BaseCasinoScreen {
    private long selectedChip = 50;
    private final List<RouletteEngine.Bet> pendingBets = new ArrayList<>();
    private int winningNumber = -1;
    private long lastWon = 0;
    private boolean isRed = false;
    private boolean isBlack = false;
    private boolean isGreen = false;

    public RouletteScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("European Roulette"), pos, initialStateJson, playerBalance);
        // Wider than the base cabinet: a real roulette cloth needs 3 rows of 12.
        this.bgWidth = 400;
        this.bgHeight = 228;
    }

    @Override
    protected void initGameWidgets() {
        pendingBets.clear();

        // ---- vertical rhythm (all offsets from topPos) --------------------------
        //   38  chip denomination row
        //   58  outside bets, row 1
        //   78  outside bets, row 2
        //  100  number table, 3 rows of 12 + the 0 rail
        //  158  totals line (text)
        //  172  Clear Bets / SPIN
        //  194  result box
        // footer (sell/buy + balance bar) is owned by the base class
        // -------------------------------------------------------------------------

        int chipY = contentY();
        addRenderableWidget(SpriteButton.of(contentX(), chipY, 34, 16, "10", b -> selectedChip = 10));
        addRenderableWidget(SpriteButton.of(contentX() + 38, chipY, 34, 16, "50", b -> selectedChip = 50));
        addRenderableWidget(SpriteButton.of(contentX() + 76, chipY, 40, 16, "100", b -> selectedChip = 100));
        addRenderableWidget(SpriteButton.of(contentX() + 120, chipY, 40, 16, "500", b -> selectedChip = 500));

        int betY = contentY() + 18;
        addRenderableWidget(SpriteButton.of(contentX(), betY, 85, 17, "Red 1:1", SpriteButton.LABEL_RED, b -> addBet(RouletteEngine.BetType.RED, 0)));
        addRenderableWidget(SpriteButton.of(contentX() + 89, betY, 85, 17, "Black 1:1", SpriteButton.LABEL_BLACK, b -> addBet(RouletteEngine.BetType.BLACK, 0)));
        addRenderableWidget(SpriteButton.of(contentX() + 178, betY, 85, 17, "Even 1:1", b -> addBet(RouletteEngine.BetType.EVEN, 0)));
        addRenderableWidget(SpriteButton.of(contentX() + 267, betY, 85, 17, "Odd 1:1", b -> addBet(RouletteEngine.BetType.ODD, 0)));

        int betY2 = contentY() + 36;
        addRenderableWidget(SpriteButton.of(contentX(), betY2, 85, 17, "1-18 1:1", b -> addBet(RouletteEngine.BetType.LOW, 0)));
        addRenderableWidget(SpriteButton.of(contentX() + 89, betY2, 85, 17, "19-36 1:1", b -> addBet(RouletteEngine.BetType.HIGH, 0)));
        addRenderableWidget(SpriteButton.of(contentX() + 178, betY2, 85, 17, "1st 12 2:1", b -> addBet(RouletteEngine.BetType.DOZEN_1, 0)));
        addRenderableWidget(SpriteButton.of(contentX() + 267, betY2, 85, 17, "2nd 12 2:1", b -> addBet(RouletteEngine.BetType.DOZEN_2, 0)));

        // The cloth: 0 down the left rail, then three rows of twelve.
        int numY = contentY() + 56;
        addRenderableWidget(SpriteButton.colored(contentX(), numY, 24, 49, "0",
                SpriteButton.LABEL_GREEN, b -> addBet(RouletteEngine.BetType.STRAIGHT, 0)));

        int gridX = contentX() + 26;
        for (int num = 1; num <= 36; num++) {
            final int n = num;
            int col = (num - 1) % 12;
            int row = (num - 1) / 12;
            boolean red = RouletteEngine.RED_NUMBERS.contains(num);
            addRenderableWidget(SpriteButton.colored(
                    gridX + col * 27, numY + row * 16, 25, 15,
                    String.valueOf(num), red ? SpriteButton.LABEL_RED : SpriteButton.LABEL_NORMAL,
                    b -> addBet(RouletteEngine.BetType.STRAIGHT, n)));
        }

        int actY = contentY() + 112;
        addRenderableWidget(SpriteButton.of(contentX(), actY, 100, 20, "Clear Bets", b -> pendingBets.clear()));
        addRenderableWidget(SpriteButton.primary(contentX() + 106, actY, 140, 20, "SPIN WHEEL", b -> doSpin()));
    }

    private void addBet(RouletteEngine.BetType type, int number) {
        long currentTotal = pendingBets.stream().mapToLong(b -> b.amount).sum();
        if (currentTotal + selectedChip <= playerBalance) {
            pendingBets.add(new RouletteEngine.Bet(type, number, selectedChip));
        }
    }

    private void doSpin() {
        if (pendingBets.isEmpty()) return;
        JsonObject payload = new JsonObject();
        JsonArray betsArr = new JsonArray();
        for (RouletteEngine.Bet b : pendingBets) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", b.type.name());
            obj.addProperty("number", b.number);
            obj.addProperty("amount", b.amount);
            betsArr.add(obj);
        }
        payload.add("bets", betsArr);
        sendAction("SPIN", GSON.toJson(payload));
        pendingBets.clear();
    }

    @Override
    protected void onStateUpdated() {
        try {
            JsonObject obj = GSON.fromJson(lastStateJson, JsonObject.class);
            if (obj != null && obj.has("winningNumber")) {
                this.winningNumber = obj.get("winningNumber").getAsInt();
                if (obj.has("isRed")) this.isRed = obj.get("isRed").getAsBoolean();
                if (obj.has("isBlack")) this.isBlack = obj.get("isBlack").getAsBoolean();
                if (obj.has("isGreen")) this.isGreen = obj.get("isGreen").getAsBoolean();
                if (obj.has("lastWon")) this.lastWon = obj.get("lastWon").getAsLong();
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.text(this.font, "THE WHEEL NEVER SLEEPS", contentX(), contentY() + 16, COLOR_ROULETTE);
        extractor.text(this.font, "Chip: §e" + selectedChip, contentX() + 170, contentY() + 6, COLOR_TEXT_WHITE);

        long totalBet = pendingBets.stream().mapToLong(b -> b.amount).sum();
        extractor.text(this.font, "Total Bet: §e" + totalBet + " Chips §7(" + pendingBets.size() + " bets)", contentX(), contentY() + 104, COLOR_TEXT_GOLD);

        // Roulette wheel sprite, parked in the free right-hand header space so it
        // never sits underneath the bet buttons.
        extractor.blit(CasinoSprites.ROULETTE_WHEEL, contentX() + 308, contentY(), 56, 56, 0f, 0f, 1f, 1f);

        // Roulette Wheel winning result box
        int resBoxX = contentX() + 252;
        int resBoxY = contentY() + 110;
        extractor.fill(resBoxX, resBoxY, resBoxX + 128, resBoxY + 36, 0xFF111111);
        extractor.fill(resBoxX + 1, resBoxY + 1, resBoxX + 127, resBoxY + 35, 0xFF222222);

        extractor.centeredText(this.font, "LATEST RESULT", resBoxX + 64, resBoxY + 3, COLOR_TEXT_GOLD);

        if (winningNumber >= 0) {
            int numColor = isGreen ? 0xFF00FF00 : (isRed ? 0xFFFF4444 : 0xFFFFFFFF);
            String desc = isGreen ? "GREEN 0" : (isRed ? "RED " + winningNumber : "BLACK " + winningNumber);
            extractor.centeredText(this.font, "§l" + desc, resBoxX + 64, resBoxY + 15, numColor);
            String wonText = (lastWon > 0) ? "§aWon: +" + lastWon + " Chips" : "§7No Win";
            extractor.centeredText(this.font, wonText, resBoxX + 64, resBoxY + 26, COLOR_TEXT_WHITE);
        } else {
            extractor.centeredText(this.font, "Place bets & spin", resBoxX + 64, resBoxY + 15, 0xFFAAAAAA);
        }
    }
}
