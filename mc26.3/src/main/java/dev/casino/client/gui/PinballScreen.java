package dev.casino.client.gui;

import com.google.gson.JsonObject;
import dev.casino.game.PinballEngine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Pinball playfield screen.
 *
 * <p>The layout follows a real machine, top to bottom: a score readout and tilt meter under
 * the header, the bumpers down the playfield, then the flippers and the launch button at the
 * bottom where the player's hands would be.
 *
 * <p>The flipper buttons send the ball's skill input as {@code power} (0..1). A wider
 * "TIMING" bar sits beside them and fills while a flip is held, so the player can see the
 * input they are about to send rather than guessing.
 */
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

    /** Rises while the player holds a flipper; this becomes the next flip's power. */
    private float charge = 0f;
    private boolean charging = false;

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
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void initGameWidgets() {
        int rowY = topPos + 132;

        // Launch: only meaningful when no ball is in play.
        addRenderableWidget(SpriteButton.primary(leftPos + 10, rowY, 96, 20, "LAUNCH", b -> {
            JsonObject json = new JsonObject();
            json.addProperty("bet", 100L);
            sendAction("PINBALL_LAUNCH", json.toString());
        }));

        // Left / right flipper. Power is taken from the charge meter, so the player is
        // rewarded for holding and releasing rather than mashing.
        addRenderableWidget(SpriteButton.of(leftPos + 112, rowY, 52, 20, "L FLIP",
                b -> sendFlip()));
        addRenderableWidget(SpriteButton.of(leftPos + 168, rowY, 52, 20, "R FLIP",
                b -> sendFlip()));

        addRenderableWidget(SpriteButton.of(leftPos + 226, rowY, 100, 20, "Drain", b -> {
            if (inPlay) sendAction("PINBALL_DRAIN", "{}");
        }));
    }

    private void sendFlip() {
        JsonObject json = new JsonObject();
        // A held flipper builds power; an instant click is a weak, mistimed flip.
        json.addProperty("power", Math.max(0.05, Math.min(1.0, charge)));
        sendAction("PINBALL_FLIP", json.toString());
        charge = 0f;
    }

    @Override
    public void tick() {
        super.tick();
        // Charge the flipper while the mouse is down over the machine, so holding builds
        // a stronger flip. Decays when released.
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

    // 26.3 passes a MouseButtonEvent rather than raw (x, y, button); the coordinates are
    // available from the record's accessors.
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        charging = true;
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        charging = false;
        return super.mouseReleased(event);
    }

    @Override
    protected void renderGameContent(GuiGraphicsExtractor ex, int mouseX, int mouseY, float partialTick) {
        // ---- readouts -------------------------------------------------------
        ex.text(this.font, "Stake: §e" + bet + " Chips", leftPos + 10, topPos + 38, COLOR_TEXT_WHITE);
        ex.text(this.font, "Score: §e" + score, leftPos + 120, topPos + 38, COLOR_TEXT_WHITE);
        String st = inPlay ? "§aBALL IN PLAY" : "§7" + stateName;
        ex.text(this.font, st, leftPos + 220, topPos + 38, COLOR_TEXT_GOLD);

        // ---- playfield ------------------------------------------------------
        int px0 = leftPos + 10;
        int pw = bgWidth - 20;
        ex.fill(px0, topPos + PLAYFIELD_TOP - 2, px0 + pw, topPos + PLAYFIELD_TOP + PLAYFIELD_HEIGHT + 2,
                0xFF0A0807);
        ex.fill(px0 + 1, topPos + PLAYFIELD_TOP - 1, px0 + pw - 1,
                topPos + PLAYFIELD_TOP + PLAYFIELD_HEIGHT + 1, 0xFF14100C);

        // Bumpers across the playfield. A lit bumper is one the ball has already struck in
        // this run, so the player can read their progress.
        int bumperY = topPos + PLAYFIELD_TOP + 18;
        for (int i = 0; i < PinballEngine.BUMPERS; i++) {
            int bx = px0 + 14 + i * 26;
            boolean hit = i < bumpers;
            int outer = hit ? 0xFFFFD86B : 0xFF3A2E20;
            int inner = hit ? 0xFFFFF0B0 : 0xFF221A12;
            ex.fill(bx, bumperY, bx + 14, bumperY + 14, outer);
            ex.fill(bx + 2, bumperY + 2, bx + 12, bumperY + 12, inner);
            ex.centeredText(this.font, String.valueOf(i + 1), bx + 7, bumperY + 4,
                    hit ? 0xFF2A1B10 : 0xFF6B5B45);
        }

        // Ball: its horizontal position across the playfield maps to the engine's bounce index.
        int ballX = px0 + 14 + Math.min(bounce, PinballEngine.BUMPERS - 1) * 26 + 7;
        int ballY = bumperY + 22;
        if (inPlay) {
            ex.fill(ballX - 3, ballY - 3, ballX + 3, ballY + 3, 0xFFE8E8E8);
            ex.fill(ballX - 1, ballY - 1, ballX + 1, ballY + 1, 0xFFFFFFFF);
        } else {
            // Drained: draw an empty cradle where the ball would be.
            ex.fill(ballX - 3, ballY - 1, ballX + 3, ballY + 3, 0xFF2A2018);
        }

        // ---- timing meter ---------------------------------------------------
        int meterY = topPos + 116;
        ex.text(this.font, "TIMING", leftPos + 10, meterY, COLOR_TEXT_GOLD);
        int mx0 = leftPos + 56;
        int mw = 150;
        ex.fill(mx0, meterY, mx0 + mw, meterY + 6, 0xFF1A1410);
        int filled = (int) (mw * charge);
        if (filled > 0) {
            // Green through gold as the meter fills, so a "perfect" release reads instantly.
            int col = charge > 0.8f ? 0xFFFFE9A8 : (charge > 0.5f ? 0xFFFFD86B : 0xFF7DDE7D);
            ex.fill(mx0, meterY, mx0 + filled, meterY + 6, col);
        }
        ex.text(this.font, String.format("§7%d%%", (int) (charge * 100)),
                mx0 + mw + 6, meterY - 1, COLOR_TEXT_WHITE);

        // ---- last ball / odds ----------------------------------------------
        String last = lastPayout > 0
                ? "§aLast: +" + lastPayout + " Chips §7(" + lastBumpers + " bumpers)"
                : "§7No completed ball yet";
        ex.text(this.font, last, leftPos + 10, topPos + 156, COLOR_TEXT_WHITE);

        // The machine's tuned return, shown honestly rather than hidden.
        String odds = String.format("§7Return: §e%.0f%%§7 at perfect timing, §e%.0f%%§7 casual",
                rtpSkilled * 100, rtpCasual * 100);
        ex.text(this.font, odds, leftPos + 10, topPos + 168, 0xFFAAAAAA);
    }
}
