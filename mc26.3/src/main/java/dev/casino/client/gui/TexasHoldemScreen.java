package dev.casino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.casino.game.TexasHoldemTable;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class TexasHoldemScreen extends BaseCasinoScreen {

    private static final int MAX_SEATS = TexasHoldemTable.MAX_SEATS;

    private static final int ARC_FIRST_DEG = 200;
    private static final int ARC_STEP_DEG = 28;
    private static final int ARC_CY = 88;
    private static final int ARC_RX = 144;
    private static final int ARC_RY = 11;

    private static final int CARD_W = 20;
    private static final int CARD_H = 28;
    private static final int SEAT_STEP = 8;
    private static final int BOARD_STEP = 22;
    private static final int SLIDE_TICKS = 10;
    private static final int FLIP_TICKS = 6;
    private static final int RAISE_STEP = 50;
    private static final int BUY_IN = 500;

    private static final int GOLD = 0xFFB8862C;
    private static final int GOLD_LIGHT = 0xFFFFD86B;
    private static final int GREY = 0xFF8A8A8A;
    private static final int BOT_GREY = 0xFF9AA7B1;
    private static final int FELT = 0x261B6B3F;
    private static final int FELT_DARK = 0x1A1B6B3F;

    public static class ClientCard {
        public final String card;
        public final int tick;
        public ClientCard(String card, int tick) {
            this.card = card;
            this.tick = tick;
        }
    }

    public static class ClientSeat {
        public int index;
        public boolean occupied;
        public String name = "";
        public long stack = 0;
        public long currentBet = 0;
        public boolean isBot = false;
        public boolean folded = false;
        public boolean allIn = false;
        public String lastAction = "";
        public final List<ClientCard> cards = new ArrayList<>();
        public final List<Integer> holeTicks = new ArrayList<>();
    }

    private String phase = "WAITING_FOR_PLAYERS";
    private long pot = 0;
    private long currentHighestBet = 0;
    private int turnSeat = -1;
    private String summary = "";
    private String dealerSay = "";
    private String ticker = "";
    private boolean autoFillBots = true;
    private boolean isOwner = false;
    private int animClock = 0;
    private int handNumber = 0;
    private int mySeat = -1;
    private long myBet = 0;

    private int clientClock = 0;

    private final List<ClientCard> communityCards = new ArrayList<>();
    private final ClientSeat[] clientSeats = new ClientSeat[MAX_SEATS];

    private SpriteButton foldButton;
    private SpriteButton checkButton;
    private SpriteButton raiseButton;
    private SpriteButton botsButton;
    private SpriteButton sitButton;
    private SpriteButton leaveButton;

    public TexasHoldemScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Texas Hold'em Poker Floor (Multiplayer)"), pos, initialStateJson, playerBalance);
        this.bgWidth = 400;
        this.bgHeight = 232;
        for (int i = 0; i < MAX_SEATS; i++) {
            clientSeats[i] = new ClientSeat();
            clientSeats[i].index = i;
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
        int row1Y = contentY() + 122;
        int row2Y = contentY() + 148;

        addRenderableWidget(SpriteButton.of(contentX() + 4, row1Y, 60, 16, "SIT OUT",
                b -> sendAction("LEAVE", "{}")));
        if (isOwner) {
            addRenderableWidget(SpriteButton.of(contentX() + 68, row1Y, 60, 16,
                    autoFillBots ? "§aBots: ON" : "§cBots: OFF",
                    b -> sendAction("TOGGLE_BOTS", "{}")));
        }
        addRenderableWidget(SpriteButton.of(contentX() + 132, row1Y, 44, 16, "LEAVE",
                b -> sendAction("LEAVE", "{}")));

        boolean myTurn = myTurn();
        addRenderableWidget(SpriteButton.of(contentX() + 4, row2Y, 56, 16, "FOLD",
                b -> sendAction("FOLD", "{}")));
        addRenderableWidget(SpriteButton.of(contentX() + 64, row2Y, 68, 16, "CHECK / CALL",
                b -> sendAction(myTurn ? "CHECK" : "CALL", "{}")));
        addRenderableWidget(SpriteButton.of(contentX() + 136, row2Y, 60, 16, "RAISE +50",
                b -> { JsonObject json = new JsonObject();
                    json.addProperty("raise", 50);
                    sendAction("RAISE", GSON.toJson(json)); }));
        addRenderableWidget(SpriteButton.of(contentX() + 200, row2Y, 56, 16, "DEAL",
                b -> sendAction("DEAL", "{}")));
    }

    private boolean myTurn() {
        if (mySeat < 0 || mySeat >= MAX_SEATS || turnSeat != mySeat) {
            return false;
        }
        ClientSeat mine = clientSeats[mySeat];
        return mine != null && !mine.folded;
    }

    @Override
    protected void onStateUpdated() {
        try {
            JsonObject obj = GSON.fromJson(lastStateJson, JsonObject.class);
            if (obj == null) return;
            phase = str(obj, "phase", phase);
            pot = lng(obj, "pot", pot);
            currentHighestBet = lng(obj, "currentHighestBet", currentHighestBet);
            turnSeat = num(obj, "turnSeat", turnSeat);
            summary = str(obj, "summary", summary);
            autoFillBots = bool(obj, "autoFillBots", autoFillBots);
            isOwner = bool(obj, "isOwner", isOwner);
            animClock = num(obj, "animClock", animClock);
            handNumber = num(obj, "handNumber", handNumber);
            dealerSay = str(obj, "dealerSay", dealerSay);

            communityCards.clear();
            if (obj.has("communityCards")) {
                JsonArray arr = obj.getAsJsonArray("communityCards");
                for (var el : arr) {
                    JsonObject c = el.getAsJsonObject();
                    communityCards.add(new ClientCard(c.get("c").getAsString(),
                            c.get("t").getAsInt()));
                }
            }

            if (obj.has("seats")) {
                JsonArray seatsArr = obj.getAsJsonArray("seats");
                for (int i = 0; i < seatsArr.size() && i < MAX_SEATS; i++) {
                    JsonObject so = seatsArr.get(i).getAsJsonObject();
                    ClientSeat cs = clientSeats[i];
                    cs.index = i;
                    cs.occupied = so.get("occupied").getAsBoolean();
                    if (cs.occupied) {
                        cs.name = so.get("name").getAsString();
                        cs.stack = so.get("stack").getAsLong();
                        cs.currentBet = so.get("currentBet").getAsLong();
                        cs.isBot = so.get("isBot").getAsBoolean();
                        cs.folded = so.get("folded").getAsBoolean();
                        cs.allIn = so.get("allIn").getAsBoolean();
                        cs.lastAction = so.get("lastAction").getAsString();
                        cs.cards.clear();
                        cs.holeTicks.clear();
                        if (so.has("cards")) {
                            JsonArray cardArr = so.getAsJsonArray("cards");
                            for (var c : cardArr) cs.cards.add(new ClientCard(c.getAsString(), 0));
                        }
                        if (so.has("holeTicks")) {
                            JsonArray ticks = so.getAsJsonArray("holeTicks");
                            for (var t : ticks) cs.holeTicks.add(t.getAsInt());
                        }
                    } else {
                        cs.name = ""; cs.stack = 0; cs.currentBet = 0;
                        cs.cards.clear(); cs.holeTicks.clear();
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        applyEnablement();
    }

    @Override
    protected void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        int cx = contentX() + contentW() / 2;

        extractor.centeredText(this.font, "POT: §e" + pot + " §7(call: " + currentHighestBet + ")",
                cx, contentY() + 3, COLOR_TEXT_GOLD);
        String handLine = "Hand #" + handNumber + "   " + dealerSay;
        extractor.text(this.font, fitTable(handLine, contentW() - 10),
                contentX(), contentY() + 14, COLOR_TEXT_WHITE);

        int dealerX = cx;
        int dealerY = contentY() + 22;

        int boardX = cx - (communityCards.size() * 14) / 2;
        int boardY = contentY() + 50;
        for (int i = 0; i < communityCards.size(); i++) {
            ClientCard dc = communityCards.get(i);
            drawArrivingCard(extractor, boardX + i * 28, boardY, dc.card, dc.tick, clientClock);
        }

        for (int i = 0; i < MAX_SEATS; i++) {
            int[] p = seatPoint(i);
            ClientSeat s = clientSeats[i];
            if (s.occupied) {
                drawSeat(extractor, s, p[0], p[1], i == turnSeat);
            } else {
                drawEmptySeat(extractor, p[0], p[1]);
            }
        }

        if (!summary.isEmpty()) {
            extractor.centeredText(this.font, "§e" + summary, cx, contentY() + 136, COLOR_TEXT_GOLD);
        }
    }

    private int[] seatPoint(int index) {
        int cx = contentX() + contentW() / 2;
        int cy = contentY() + 98;
        double theta = Math.toRadians(ARC_FIRST_DEG + index * ARC_STEP_DEG);
        int x = cx + (int) (140 * Math.cos(theta));
        int y = cy + (int) (14 * Math.sin(theta));
        x = Math.max(contentX() + 30, Math.min(contentX() + contentW() - 70, x));
        y = Math.max(contentY() + 82, Math.min(contentY() + 106, y));
        return new int[]{x, y};
    }

    private void drawSeat(GuiGraphicsExtractor e, ClientSeat s, int sx, int sy, boolean active) {
        int border = active ? 0xFFFFD700 : 0xFF333333;
        e.fill(sx - 1, sy - 1, sx + 74, sy + 24, border);
        e.fill(sx, sy, sx + 73, sy + 23, 0xFF181C18);

        String prefix = s.isBot ? "§7[BOT] " : (s.allIn ? "§c" : "");
        e.text(this.font, prefix + shortName(s.name), sx + 2, sy + 2, COLOR_TEXT_WHITE);
        e.text(this.font, "§e" + s.stack + " Chips", sx + 2, sy + 12, COLOR_TEXT_GOLD);

        if (s.folded) {
            e.text(this.font, "§7Folded", sx + 40, sy + 2, 0xFFAAAAAA);
        } else if (s.allIn) {
            e.text(this.font, "§cALL-IN", sx + 40, sy + 2, COLOR_TEXT_RED);
        } else if (!s.lastAction.isEmpty()) {
            e.text(this.font, "§f" + s.lastAction, sx + 40, sy + 2, COLOR_TEXT_WHITE);
        }

        drawChip(e, sx + 2, sy + 14);
        if (s.currentBet > 0) {
            e.text(this.font, "§e" + s.currentBet, sx + 18, sy + 15, COLOR_TEXT_GOLD);
        }

        int cardX = sx + 40;
        for (int c = 0; c < s.cards.size(); c++) {
            int tick = c < s.holeTicks.size() ? s.holeTicks.get(c) : -1;
            drawArrivingCard(e, cardX + c * 20, sy + 5, s.cards.get(c).card, tick, clientClock);
        }
    }

    private void drawEmptySeat(GuiGraphicsExtractor e, int sx, int sy) {
        int x0 = sx - 20, y0 = sy - 12, x1 = sx + 53, y1 = sy + 12;
        for (int x = x0; x < x1; x += 6) {
            e.fill(x, y0, Math.min(x + 3, x1), y0 + 1, 0x40FFFFFF);
            e.fill(x, y1 - 1, Math.min(x + 3, x1), y1, 0x40FFFFFF);
        }
        for (int y = y0; y < y1; y += 6) {
            e.fill(x0, y, x0 + 1, Math.min(y + 3, y1), 0x30FFFFFF);
            e.fill(x1 - 1, y, x1, Math.min(y + 3, y1), 0x30FFFFFF);
        }
        e.centeredText(this.font, "seat " + (sx - (contentX() + contentW() / 2) > 0 ? "right" : "left"),
                sx, sy, 0x60FFFFFF);
    }

    private void drawSeatPlate(GuiGraphicsExtractor e, int sx, int sy, String name, String stack,
                               long bet, boolean active, boolean mine, String[] cards,
                               List<Integer> ticks) {
    }

    private void drawArrivingCard(GuiGraphicsExtractor e, int dx, int dy, String cardStr,
                                  int tick, int now) {
        if (tick < 0) {
            drawCard(e, dx, dy, cardStr);
            return;
        }
        int age = now - tick;
        double k = Math.min(1.0, age / 10.0);
        double ease = 1.0 - Math.pow(1.0 - k, 3.0);
        int startX = contentX() + contentW() / 2;
        int startY = contentY() + 22;
        int x = startX + (int) ((dx - startX) * ease);
        int y = startY + (int) ((dy - startY) * ease);
        drawCard(e, x, y, cardStr);
        if (ease < 1.0) {
            int veil = (int) ((1.0 - ease) * 120.0);
            e.fill(x, y, x + CARD_W, y + CARD_H, veil << 24);
        }
    }

    private static String shortName(String name) {
        return name.length() > 8 ? name.substring(0, 7) + "…" : name;
    }

    private static String fitTable(String text, int maxW) {
        return text;
    }

    private int myTurnIndex() {
        return turnSeat;
    }

    private void applyEnablement() {
        boolean myTurn = myTurn();
        foldButton.active = myTurn;
        checkButton.active = myTurn;
        raiseButton.active = myTurn;
        botsButton.active = isOwner;
        sitButton.active = mySeat < 0 && findOpenSeat() >= 0;
        leaveButton.active = true;
    }

    private int findOpenSeat() {
        for (ClientSeat seat : clientSeats) {
            if (seat != null && !seat.occupied) {
                return seat.index;
            }
        }
        return -1;
    }
}
