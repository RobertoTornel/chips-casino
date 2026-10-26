package dev.casino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.casino.game.RouletteEngine;
import net.minecraft.client.gui.GuiGraphics;
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

        int chipY = topPos + 36;
        addRenderableWidget(SpriteButton.of(leftPos + 10, chipY, 34, 16, "10", b -> selectedChip = 10));
        addRenderableWidget(SpriteButton.of(leftPos + 48, chipY, 34, 16, "50", b -> selectedChip = 50));
        addRenderableWidget(SpriteButton.of(leftPos + 86, chipY, 40, 16, "100", b -> selectedChip = 100));
        addRenderableWidget(SpriteButton.of(leftPos + 130, chipY, 40, 16, "500", b -> selectedChip = 500));

        int betY = topPos + 54;
        addRenderableWidget(SpriteButton.of(leftPos + 10, betY, 92, 17, "Red 1:1", SpriteButton.LABEL_RED, b -> addBet(RouletteEngine.BetType.RED, 0)));
        addRenderableWidget(SpriteButton.of(leftPos + 106, betY, 92, 17, "Black 1:1", SpriteButton.LABEL_BLACK, b -> addBet(RouletteEngine.BetType.BLACK, 0)));
        addRenderableWidget(SpriteButton.of(leftPos + 202, betY, 92, 17, "Even 1:1", b -> addBet(RouletteEngine.BetType.EVEN, 0)));
        addRenderableWidget(SpriteButton.of(leftPos + 298, betY, 92, 17, "Odd 1:1", b -> addBet(RouletteEngine.BetType.ODD, 0)));

        int betY2 = topPos + 72;
        addRenderableWidget(SpriteButton.of(leftPos + 10, betY2, 92, 17, "1-18 1:1", b -> addBet(RouletteEngine.BetType.LOW, 0)));
        addRenderableWidget(SpriteButton.of(leftPos + 106, betY2, 92, 17, "19-36 1:1", b -> addBet(RouletteEngine.BetType.HIGH, 0)));
        addRenderableWidget(SpriteButton.of(leftPos + 202, betY2, 92, 17, "1st 12 2:1", b -> addBet(RouletteEngine.BetType.DOZEN_1, 0)));
        addRenderableWidget(SpriteButton.of(leftPos + 298, betY2, 92, 17, "2nd 12 2:1", b -> addBet(RouletteEngine.BetType.DOZEN_2, 0)));

        // The cloth: 0 down the left rail, then three rows of twelve.
        int numY = topPos + 92;
        addRenderableWidget(SpriteButton.colored(leftPos + 10, numY, 30, 49, "0",
                SpriteButton.LABEL_GREEN, b -> addBet(RouletteEngine.BetType.STRAIGHT, 0)));

        int gridX = leftPos + 44;
        for (int num = 1; num <= 36; num++) {
            final int n = num;
            int col = (num - 1) % 12;
            int row = (num - 1) / 12;
            boolean red = RouletteEngine.RED_NUMBERS.contains(num);
            addRenderableWidget(SpriteButton.colored(
                    gridX + col * 29, numY + row * 16, 27, 15,
                    String.valueOf(num), red ? SpriteButton.LABEL_RED : SpriteButton.LABEL_NORMAL,
                    b -> addBet(RouletteEngine.BetType.STRAIGHT, n)));
        }

        int actY = topPos + 148;
        addRenderableWidget(SpriteButton.of(leftPos + 10, actY, 100, 20, "Clear Bets", b -> pendingBets.clear()));
        addRenderableWidget(SpriteButton.primary(leftPos + 116, actY, 140, 20, "SPIN WHEEL", b -> doSpin()));
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
    protected void renderGameContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.drawString(this.font, "Chip: §e" + selectedChip, leftPos + 180, topPos + 42, COLOR_TEXT_WHITE, false);

        long totalBet = pendingBets.stream().mapToLong(b -> b.amount).sum();
        graphics.drawString(this.font, "Total Bet: §e" + totalBet + " Chips §7(" + pendingBets.size() + " bets)", leftPos + 10, topPos + 140, COLOR_TEXT_GOLD, false);

        // Roulette wheel sprite, parked in the free right-hand header space so it
        // never sits underneath the bet buttons.
        blitTexture(graphics, CasinoSprites.ROULETTE_WHEEL, leftPos + 318, topPos + 36, 56, 56, CasinoSprites.ROULETTE_WHEEL_W, CasinoSprites.ROULETTE_WHEEL_H);

        // Roulette Wheel winning result box
        int resBoxX = leftPos + 262;
        int resBoxY = topPos + 146;
        graphics.fill(resBoxX, resBoxY, resBoxX + 128, resBoxY + 36, 0xFF111111);
        graphics.fill(resBoxX + 1, resBoxY + 1, resBoxX + 127, resBoxY + 35, 0xFF222222);

        graphics.drawCenteredString(this.font, "LATEST RESULT", resBoxX + 64, resBoxY + 3, COLOR_TEXT_GOLD);

        if (winningNumber >= 0) {
            int numColor = isGreen ? 0xFF00FF00 : (isRed ? 0xFFFF4444 : 0xFFFFFFFF);
            String desc = isGreen ? "GREEN 0" : (isRed ? "RED " + winningNumber : "BLACK " + winningNumber);
            graphics.drawCenteredString(this.font, "§l" + desc, resBoxX + 64, resBoxY + 15, numColor);
            String wonText = (lastWon > 0) ? "§aWon: +" + lastWon + " Chips" : "§7No Win";
            graphics.drawCenteredString(this.font, wonText, resBoxX + 64, resBoxY + 26, COLOR_TEXT_WHITE);
        } else {
            graphics.drawCenteredString(this.font, "Place bets & spin", resBoxX + 64, resBoxY + 15, 0xFFAAAAAA);
        }
    }
}
