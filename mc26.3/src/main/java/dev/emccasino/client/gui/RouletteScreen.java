package dev.emccasino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.emccasino.game.RouletteEngine;
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
        this.bgWidth = 360;
        this.bgHeight = 230;
    }

    @Override
    protected void initGameWidgets() {
        pendingBets.clear();

        // Chip size selector
        int chipY = topPos + 38;
        addRenderableWidget(Button.builder(Component.literal("10"), b -> selectedChip = 10).bounds(leftPos + 10, chipY, 26, 16).build());
        addRenderableWidget(Button.builder(Component.literal("50"), b -> selectedChip = 50).bounds(leftPos + 40, chipY, 26, 16).build());
        addRenderableWidget(Button.builder(Component.literal("100"), b -> selectedChip = 100).bounds(leftPos + 70, chipY, 32, 16).build());
        addRenderableWidget(Button.builder(Component.literal("500"), b -> selectedChip = 500).bounds(leftPos + 106, chipY, 32, 16).build());

        // Quick outside bets
        int betY = topPos + 60;
        addRenderableWidget(Button.builder(Component.literal("§cRed (1:1)"), b -> addBet(RouletteEngine.BetType.RED, 0)).bounds(leftPos + 10, betY, 78, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§8Black (1:1)"), b -> addBet(RouletteEngine.BetType.BLACK, 0)).bounds(leftPos + 92, betY, 78, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Even (1:1)"), b -> addBet(RouletteEngine.BetType.EVEN, 0)).bounds(leftPos + 174, betY, 78, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Odd (1:1)"), b -> addBet(RouletteEngine.BetType.ODD, 0)).bounds(leftPos + 256, betY, 78, 18).build());

        int betY2 = topPos + 82;
        addRenderableWidget(Button.builder(Component.literal("1-18 (1:1)"), b -> addBet(RouletteEngine.BetType.LOW, 0)).bounds(leftPos + 10, betY2, 78, 18).build());
        addRenderableWidget(Button.builder(Component.literal("19-36 (1:1)"), b -> addBet(RouletteEngine.BetType.HIGH, 0)).bounds(leftPos + 92, betY2, 78, 18).build());
        addRenderableWidget(Button.builder(Component.literal("1st 12 (2:1)"), b -> addBet(RouletteEngine.BetType.DOZEN_1, 0)).bounds(leftPos + 174, betY2, 78, 18).build());
        addRenderableWidget(Button.builder(Component.literal("2nd 12 (2:1)"), b -> addBet(RouletteEngine.BetType.DOZEN_2, 0)).bounds(leftPos + 256, betY2, 78, 18).build());

        // Single number 0
        int numY = topPos + 106;
        addRenderableWidget(Button.builder(Component.literal("§a0"), b -> addBet(RouletteEngine.BetType.STRAIGHT, 0)).bounds(leftPos + 10, numY, 26, 16).build());

        // Quick straight number buttons 1..12
        for (int i = 1; i <= 10; i++) {
            final int num = i;
            boolean red = RouletteEngine.RED_NUMBERS.contains(num);
            String label = (red ? "§c" : "§f") + num;
            addRenderableWidget(Button.builder(Component.literal(label), b -> addBet(RouletteEngine.BetType.STRAIGHT, num))
                    .bounds(leftPos + 10 + (i * 30), numY, 28, 16).build());
        }

        // Action controls
        int actY = topPos + 160;
        addRenderableWidget(Button.builder(Component.literal("§eClear Bets"), b -> pendingBets.clear()).bounds(leftPos + 10, actY, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§a§lSPIN WHEEL"), b -> doSpin()).bounds(leftPos + 100, actY, 120, 20).build());
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
        extractor.text(this.font, "Chip: §e" + selectedChip + " EMC", leftPos + 150, topPos + 42, COLOR_TEXT_WHITE);

        long totalBet = pendingBets.stream().mapToLong(b -> b.amount).sum();
        extractor.text(this.font, "Total Bet: §e" + totalBet + " EMC §7(" + pendingBets.size() + " bets)", leftPos + 10, topPos + 130, COLOR_TEXT_GOLD);

        // Roulette wheel sprite (resource-pack overridable)
        extractor.blit(CasinoSprites.ROULETTE_WHEEL, leftPos + 10, topPos + 58, 64, 64, 0f, 0f, 1f, 1f);

        // Roulette Wheel winning result box
        int resBoxX = leftPos + 230;
        int resBoxY = topPos + 130;
        extractor.fill(resBoxX, resBoxY, resBoxX + 115, resBoxY + 50, 0xFF111111);
        extractor.fill(resBoxX + 1, resBoxY + 1, resBoxX + 114, resBoxY + 49, 0xFF222222);

        extractor.centeredText(this.font, "LATEST RESULT", resBoxX + 57, resBoxY + 4, COLOR_TEXT_GOLD);

        if (winningNumber >= 0) {
            int numColor = isGreen ? 0xFF00FF00 : (isRed ? 0xFFFF4444 : 0xFFFFFFFF);
            String desc = isGreen ? "GREEN 0" : (isRed ? "RED " + winningNumber : "BLACK " + winningNumber);
            extractor.centeredText(this.font, "§l" + desc, resBoxX + 57, resBoxY + 18, numColor);
            String wonText = (lastWon > 0) ? "§aWon: +" + lastWon + " EMC" : "§7No Win";
            extractor.centeredText(this.font, wonText, resBoxX + 57, resBoxY + 34, COLOR_TEXT_WHITE);
        } else {
            extractor.centeredText(this.font, "Place bets & spin", resBoxX + 57, resBoxY + 24, 0xFFAAAAAA);
        }
    }
}
