package dev.casino.client.gui;

import com.google.gson.JsonObject;
import dev.casino.game.PinballEngine;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public class PinballScreen extends BaseCasinoScreen {

    private static final int PLAYFIELD_TOP = 44;
    private static final int PLAYFIELD_HEIGHT = 76;

    private boolean inPlay = false;
    private String stateName = "READY";
    private long bet = 0;
    private long score = 0;
    private int bumpers = 0;
    private int bounce = 0;
    private long lastPayout = 0;
    private int lastBumpers = 0;
    private double rtpSkilled = 0.92;
    private double rtpCasual = 0.26;

    private float charge = 0f;
    private boolean charging = false;
    private int clientClock = 0;

    public PinballScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Pinball"), pos, initialStateJson, playerBalance);
        this.bgWidth = 340;
        this.bgHeight = 228;
        parse(initialStateJson);
    }

    @Override
    public void updateState(String json, long balance) {
        super.updateState(json, balance);
        parse(json);
    }

    private void parse(String json) {
        if (json == null || json.isBlank()) return;
        try {
            JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("inPlay")) inPlay = obj.get("inPlay").getAsBoolean();
            if (obj.has("state")) stateName = obj.get("state").getAsString();
            if (obj.has("bet")) bet = obj.get("bet").getAsLong();
            if (obj.has("score")) score = obj.get("score").getAsLong();
            if (obj.has("bumpers")) bumpers = obj.get("bumpers").getAsInt();
            if (obj.has("bounce")) bounce = obj.get("bounce").getAsInt();
            if (obj.has("lastPayout")) lastPayout = obj.get("lastPayout").getAsLong();
            if (obj.has("lastBumpers")) lastBumpers = obj.get("lastBumpers").getAsInt();
            if (obj.has("rtpSkilled")) rtpSkilled = obj.get("rtpSkilled").getAsDouble();
            if (obj.has("rtpCasual")) rtpCasual = obj.get("rtpCasual").getAsDouble();
        } catch (Exception ignored) {}
    }

    @Override
    protected void initGameWidgets() {
        int rowY = contentY() + 96;
        addRenderableWidget(SpriteButton.primary(contentX(), rowY, 92, 20, "LAUNCH", b -> {
            JsonObject json = new JsonObject();
            json.addProperty("bet", 100L);
            sendAction("PINBALL_LAUNCH", json.toString());
        }));
        addRenderableWidget(SpriteButton.of(contentX() + 96, rowY, 56, 20, "L FLIP",
                b -> sendFlip()));
        addRenderableWidget(SpriteButton.of(contentX() + 156, rowY, 56, 20, "R FLIP",
                b -> sendFlip()));
        addRenderableWidget(SpriteButton.of(contentX() + 216, rowY, 76, 20, "Drain", b -> {
            if (inPlay) sendAction("PINBALL_DRAIN", "{}");
        }));
    }

    private void sendFlip() {
        JsonObject json = new JsonObject();
        json.addProperty("power", Math.max(0.05, Math.min(1.0, charge)));
        sendAction("PINBALL_FLIP", json.toString());
        charge = 0f;
    }

    @Override
    public void tick() {
        super.tick();
        clientClock++;
        if (inPlay) {
            if (charging) {
                charge = Math.min(1.0f, charge + 0.05f);
            } else {
                charge = Math.max(0f, charge - 0.08f);
            }
        } else {
            charge = 0f;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        charging = true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        charging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    protected void renderGameContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        String stateTag = inPlay ? "\u00a7aBALL IN PLAY" : "\u00a77" + stateName;
        g.drawString(this.font, "PINBALL  \u00a77|  " + stateTag, contentX(), contentY() + 2, COLOR_PINBALL, false);
        g.drawString(this.font, "Stake: §e" + bet + " Chips", contentX() + 110, contentY() + 2, COLOR_TEXT_WHITE, false);
        g.drawString(this.font, "Score: §e" + score, contentX() + 200, contentY() + 2, COLOR_TEXT_WHITE, false);

        int px0 = contentX();
        int pw = bgWidth - 20;
        g.fill(px0, topPos + PLAYFIELD_TOP - 2, px0 + pw, topPos + PLAYFIELD_TOP + PLAYFIELD_HEIGHT + 2,
                0xFF0A0807);
        g.fill(px0 + 1, topPos + PLAYFIELD_TOP - 1, px0 + pw - 1,
                topPos + PLAYFIELD_TOP + PLAYFIELD_HEIGHT + 1, 0xFF14100C);

        int bumperY = topPos + PLAYFIELD_TOP + 18;
        for (int i = 0; i < PinballEngine.BUMPERS; i++) {
            int bx = px0 + 14 + i * 26;
            boolean hit = i < bumpers;
            int outer = hit ? 0xFFFFD86B : 0xFF3A2E20;
            int inner = hit ? 0xFFFFF0B0 : 0xFF221A12;
            g.fill(bx, bumperY, bx + 14, bumperY + 14, outer);
            g.fill(bx + 2, bumperY + 2, bx + 12, bumperY + 12, inner);
            g.drawCenteredString(this.font, String.valueOf(i + 1), bx + 7, bumperY + 4,
                    hit ? 0xFF2A1B10 : 0xFF6B5B45);
        }

        int ballX = px0 + 14 + Math.min(bounce, PinballEngine.BUMPERS - 1) * 26 + 7;
        int ballY = bumperY + 22;
        if (inPlay) {
            g.fill(ballX - 3, ballY - 3, ballX + 3, ballY + 3, 0xFFE0E0E0);
            g.fill(ballX - 1, ballY - 1, ballX + 1, ballY + 1, 0xFFFFFFFF);
        } else {
            g.fill(ballX - 3, ballY - 1, ballX + 3, ballY + 3, 0xFF2A2018);
        }

        int meterY = contentY() + 80;
        g.drawString(this.font, "FLIP POWER", contentX(), meterY, COLOR_TEXT_GOLD, false);
        int mx0 = contentX() + 46;
        int mw = 150;
        g.fill(mx0, meterY, mx0 + mw, meterY + 6, 0xFF1A1410);
        int filled = (int) (mw * charge);
        if (filled > 0) {
            int col = charge > 0.8f ? 0xFFFFE9A8 : (charge > 0.5f ? 0xFFFFD86B : 0xFF7DDE7D);
            g.fill(mx0, meterY, mx0 + filled, meterY + 6, col);
        }
        g.drawString(this.font, "\u00a77" + (int) (charge * 100) + "%",
                mx0 + mw + 6, meterY - 1, COLOR_TEXT_WHITE, false);

        String last = lastPayout > 0
                ? "§aLast: +§e" + lastPayout + " §aChips §7(" + lastBumpers + " bumpers)"
                : "§7No completed ball yet";
        g.drawString(this.font, last, contentX(), contentY() + 120, COLOR_TEXT_WHITE, false);

        String odds = String.format("§7Return: §e%.0f%%§7 perfect, §e%.0f%%§7 casual",
                rtpSkilled * 100, rtpCasual * 100);
        g.drawString(this.font, odds, contentX(), contentY() + 132, 0xFFAAAAAA, false);
    }
}
