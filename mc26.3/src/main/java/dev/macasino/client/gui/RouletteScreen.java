package dev.macasino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.macasino.game.RouletteEngine;
import dev.macasino.game.RouletteTable;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class RouletteScreen extends BaseCasinoScreen {

    private static final int SEAT_COUNT = 6;

    private static final int WHEEL_W = 64;
    private static final int WHEEL_H = 64;

    private String phase = "BETTING";
    private long pot = 0;
    private int roundNumber = 0;
    private String dealerSay = "";
    private int winningNumber = -1;
    private int lastWinningNumber = -1;
    private double wheelAngle = 0.0;
    private boolean spinning = false;
    private long myStake = 0;
    private int mySeat = -1;
    private long selectedChip = 50;

    private final List<RouletteEngine.Bet> myBets = new ArrayList<>();
    private final ClientSeat[] seats = new ClientSeat[SEAT_COUNT];
    private final List<Integer> history = new ArrayList<>();

    private int clientClock = 0;

    public static class ClientSeat {
        public int index;
        public boolean occupied;
        public String name = "";
        public long stake = 0;
        public long payout = 0;
        public long net = 0;
        public boolean isBot = false;
        public boolean settled = false;
        public boolean mine = false;
    }

    public RouletteScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("European Roulette"), pos, initialStateJson, playerBalance);
        this.bgWidth = 400;
        this.bgHeight = 232;
        for (int i = 0; i < SEAT_COUNT; i++) {
            seats[i] = new ClientSeat();
            seats[i].index = i;
        }
    }

    @Override
    public void tick() {
        super.tick();
        clientClock++;
    }

    @Override
    protected void initGameWidgets() {
        onStateUpdated();
        addGameButtons();
    }

    private void addGameButtons() {
        int top = contentY() + 14;

        // Chip selector row - compact, left side
        int cx = contentX() + 4;
        addRenderableWidget(SpriteButton.of(cx, top, 28, 16, "10", b -> selectedChip = 10));
        addRenderableWidget(SpriteButton.of(cx + 32, top, 28, 16, "50", b -> selectedChip = 50));
        addRenderableWidget(SpriteButton.of(cx + 64, top, 32, 16, "100", b -> selectedChip = 100));
        addRenderableWidget(SpriteButton.of(cx + 100, top, 32, 16, "500", b -> selectedChip = 500));

        // Table controls - right side
        boolean canBet = !spinning && myStake < 10_000;
        addRenderableWidget(SpriteButton.of(contentX() + 204, top, 44, 16, "CLEAR",
                b -> sendAction("CLEAR", "{}")));
        addRenderableWidget(SpriteButton.of(contentX() + 252, top, 44, 16, "UNDO",
                b -> sendAction("UNDO", "{}")));
        addRenderableWidget(SpriteButton.primary(contentX() + 300, top, 48, 16, "SPIN",
                b -> {
                    JsonObject json = new JsonObject();
                    json.addProperty("amount", selectedChip);
                    sendAction("BET", GSON.toJson(json));
                }));
    }

    @Override
    protected void onStateUpdated() {
        try {
            JsonObject obj = GSON.fromJson(lastStateJson, JsonObject.class);
            if (obj == null) {
                return;
            }
            if (obj.has("phase")) phase = obj.get("phase").getAsString();
            if (obj.has("pot")) pot = obj.get("pot").getAsLong();
            if (obj.has("round")) roundNumber = obj.get("round").getAsInt();
            if (obj.has("dealerSay")) dealerSay = obj.get("dealerSay").getAsString();
            if (obj.has("winningNumber")) winningNumber = obj.get("winningNumber").getAsInt();
            if (obj.has("lastWinningNumber")) lastWinningNumber = obj.get("lastWinningNumber").getAsInt();
            if (obj.has("wheelAngle")) wheelAngle = obj.get("wheelAngle").getAsDouble();
            if (obj.has("spinning")) spinning = obj.get("spinning").getAsBoolean();
            if (obj.has("myStake")) myStake = obj.get("myStake").getAsLong();
            if (obj.has("mySeat")) mySeat = obj.get("mySeat").getAsInt();
            if (obj.has("autoFillBots")) {
                // autoFillBots handled by base screen
            }
            if (obj.has("isOwner")) {
                // isOwner handled by base screen
            }

            myBets.clear();
            if (obj.has("myBets")) {
                JsonArray arr = obj.getAsJsonArray("myBets");
                for (var el : arr) {
                    JsonObject b = el.getAsJsonObject();
                    myBets.add(new RouletteEngine.Bet(
                            RouletteEngine.BetType.valueOf(b.get("type").getAsString()),
                            b.get("number").getAsInt(), b.get("amount").getAsLong()));
                }
            }

            history.clear();
            if (obj.has("history")) {
                for (var el : obj.getAsJsonArray("history")) {
                    history.add(el.getAsInt());
                }
            }

            if (obj.has("seats")) {
                JsonArray arr = obj.getAsJsonArray("seats");
                for (int i = 0; i < arr.size() && i < SEAT_COUNT; i++) {
                    JsonObject s = arr.get(i).getAsJsonObject();
                    ClientSeat cs = seats[i];
                    cs.index = i;
                    cs.occupied = s.get("occupied").getAsBoolean();
                    cs.name = s.get("name").getAsString();
                    cs.stake = s.get("bet").getAsLong();
                    cs.payout = s.get("payout").getAsLong();
                    cs.net = s.get("net").getAsLong();
                    cs.isBot = s.get("bot").getAsBoolean();
                    cs.settled = s.get("settled").getAsBoolean();
                    cs.mine = s.get("mine").getAsBoolean();
                }
            } else {
                for (ClientSeat s : seats) {
                    s.occupied = false; s.stake = 0; s.payout = 0; s.net = 0;
                }
            }
        } catch (RuntimeException ignored) {}
    }

    @Override
    protected void renderGameContent(GuiGraphicsExtractor e, int mouseX, int mouseY, float partialTick) {
        int cx = contentX() + contentW() / 2;

        // ----- header: phase indicator -----
        String phaseTag = switch (phase) {
            case "BETTING" -> "§aBETTING";
            case "SPINNING" -> "§eSPINNING";
            case "SETTLE" -> "§cSETTLED";
            default -> "§7" + phase;
        };
        e.text(this.font, "EUROPEAN ROULETTE  §7|  " + phaseTag, contentX(), contentY() + 2, COLOR_ROULETTE);
        e.text(this.font, "Round " + roundNumber, cx - 24, contentY() + 2, COLOR_TEXT_WHITE);
        e.text(this.font, fit(dealerSay, 200), contentX() + 236, contentY() + 2, COLOR_TEXT_GOLD);

        // ----- chip selector -----
        e.text(this.font, "Chip §e" + selectedChip, contentX(), contentY() + 18, COLOR_TEXT_WHITE);

        // ----- wheel, left -----
        int wheelX = contentX() + 8;
        int wheelY = contentY() + 32;
        drawWheel(e, wheelX, wheelY, wheelAngle);

        // Dealer plate under the wheel.
        String dealer = "SETTLE".equals(phase) ? "Pays " + lastWinningNumber : "Croupier";
        e.centeredText(this.font, "§7" + dealer, wheelX + WHEEL_W / 2, wheelY + WHEEL_H + 2,
                COLOR_TEXT_WHITE);

        // ----- winning rail, right -----
        int railX = contentX() + contentW() - 88;
        int railY = contentY() + 34;
        drawResultRail(e, railX, railY);

        // ----- number grid, bottom-left -----
        drawGrid(e);

        // ----- seats, bottom-right -----
        drawSeats(e);

        // ----- summary banner -----
        if (winningNumber >= 0 && "SETTLE".equals(phase)) {
            String res = winningNumber + " (" +
                    (RouletteTable.isGreen(winningNumber) ? "green" :
                            RouletteTable.isRed(winningNumber) ? "red" : "black") + ")";
            e.centeredText(this.font, "§e" + res + " - " + dealerSay, cx, contentY() + 158, COLOR_TEXT_GOLD);
        }
    }

    /** Wheel sprite spinning at the server's angle, with the ball at the winning pocket. */
    private void drawWheel(GuiGraphicsExtractor e, int x, int y, double angle) {
        e.blit(CasinoSprites.ROULETTE_WHEEL, x - WHEEL_W / 2, y - WHEEL_H / 2, WHEEL_W, WHEEL_H,
                0f, 0f, WHEEL_W * 2f, WHEEL_H * 2f);

        int ccx = x + WHEEL_W / 2;
        int ccy = y + WHEEL_H / 2;
        double rad = Math.toRadians(angle - 90.0);
        int r = 24;
        int bx = ccx + (int) Math.cos(rad);
        int by = ccy + (int) Math.sin(rad);
        e.fill(bx - 2, by - 2, bx + 2, by + 2, 0xFFFFD86B);
        e.fill(bx - 1, by - 1, bx + 1, by + 1, 0xFFFFFFFF);
    }

    /** Last winning number, shown big once the wheel settles. */
    private void drawResultRail(GuiGraphicsExtractor e, int x, int y) {
        e.fill(x, y, x + 80, y + 56, 0xFF111111);
        e.fill(x + 1, y + 1, x + 79, y + 55, 0xFF1A1616);
        if (winningNumber >= 0 && "SETTLE".equals(phase)) {
            int colour = RouletteTable.isGreen(winningNumber) ? 0xFF00FF00
                    : (RouletteTable.isRed(winningNumber) ? 0xFFFF4444 : 0xFFFFFFFF);
            e.centeredText(this.font, String.valueOf(winningNumber), x + 40, y + 12, colour);
            e.centeredText(this.font, dealerSay, x + 40, y + 30, COLOR_TEXT_GOLD);
        } else {
            e.centeredText(this.font, "Place bets", x + 40, y + 20, 0xFFAAAAAA);
            e.centeredText(this.font, "last: " + (lastWinningNumber < 0 ? "-" : lastWinningNumber),
                    x + 40, y + 38, 0xFF888888);
        }
    }

    /** The cloth: 0 down the left rail, then three rows of twelve. Covered cells get a chip. */
    private void drawGrid(GuiGraphicsExtractor e) {
        int numY = contentY() + 104;
        int cellW = 20;
        int cellH = 10;

        e.fill(contentX(), numY, contentX() + 22, numY + cellH * 3 + 4, 0xFF004400);
        e.text(this.font, "0", contentX() + 8, numY + 2, 0xFFFFFFFF);
        if (isCovered(RouletteEngine.BetType.STRAIGHT, 0)) {
            drawChip(e, contentX() + 4, numY + cellH * 3 + 2);
        }

        int gridX = contentX() + 28;
        for (int num = 1; num <= 36; num++) {
            int col = (num - 1) % 12;
            int row = (num - 1) / 12;
            int gx = gridX + col * cellW;
            int gy = numY + row * cellH;
            boolean red = RouletteTable.isRed(num);
            e.fill(gx, gy, gx + cellW - 1, gy + cellH - 1,
                    red ? 0xFF600000 : (num % 2 == 0 ? 0xFF000060 : 0xFF111111));
            e.text(this.font, String.valueOf(num), gx + 3, gy + 1,
                    red ? 0xFFFF4444 : 0xFFFFFFFF);
            if (isCovered(RouletteEngine.BetType.STRAIGHT, num)) {
                drawChip(e, gx + 2, gy + 2);
            }
        }
    }

    private boolean isCovered(RouletteEngine.BetType type, int number) {
        for (RouletteEngine.Bet b : myBets) {
            if (b.type == type && b.number == number) {
                return true;
            }
        }
        return false;
    }

    /** Six seat plates along the bottom rail: name, stake, result. */
    private void drawSeats(GuiGraphicsExtractor e) {
        int sx = contentX() + 4;
        int sy = contentY() + 144;
        int pw = 56;
        int gap = 4;
        for (int i = 0; i < SEAT_COUNT; i++) {
            ClientSeat s = seats[i];
            int x = sx + i * (pw + gap);
            e.fill(x - 1, sy - 1, x + pw, sy + 14, s.mine ? 0xFFFFD700 : 0xFF333333);
            e.fill(x, sy, x + pw - 1, sy + 13, 0xFF181C18);
            if (s.occupied) {
                String tag = s.isBot ? "§7[BOT] " : "";
                e.text(this.font, tag + shortName(s.name), sx + 2, sy + 1, COLOR_TEXT_WHITE);
                e.text(this.font, "§e" + s.stake + " Chips", sx + 2, sy + 9, COLOR_TEXT_GOLD);
                if (s.settled) {
                    String res = s.net > 0 ? "§a+" + s.net : (s.net < 0 ? "§c" + s.net : "§7PUSH");
                    e.text(this.font, res, sx + pw - 14, sy + 1,
                            s.net > 0 ? COLOR_TEXT_GREEN : COLOR_TEXT_RED);
                }
            } else {
                e.text(this.font, "§8open", sx + 2, sy + 6, 0xFF888888);
            }
        }
    }

    private static String shortName(String name) {
        return name.length() > 8 ? name.substring(0, 7) + "…" : name;
    }

    private static String colourWord(int number) {
        if (number == 0) return "green";
        return RouletteTable.isRed(number) ? "red" : "black";
    }

    private static int colourOf(int number) {
        if (number == 0) return 0xFF00FF00;
        return RouletteTable.isRed(number) ? 0xFFFF4444 : 0xFFFFFFFF;
    }
}
