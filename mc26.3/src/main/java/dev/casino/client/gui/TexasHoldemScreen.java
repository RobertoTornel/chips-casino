package dev.casino.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.casino.game.TexasHoldemTable;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Six-seat Texas Hold'em table.
 *
 * <p>The table is laid out the way a real one is seen from the dealer's chair: the dealer's
 * button and the community board sit at the top of the felt, the six seats hang off a shallow
 * ellipse under them, and the player's controls are two rows at the bottom of the interior.
 * Every element is positioned from {@link #contentX()}/{@link #contentY()}, so the whole table
 * moves with the cabinet.
 *
 * <h2>Vertical budget (interior is 352 x 168)</h2>
 * <pre>
 *   +0   .. +16   header: pot centred, hand number and the dealer's line right, ticker left
 *   +18  .. +46   dealer's button at the left, the five community cards fanned in the middle,
 *                 the phase and the price to call on the right
 *   +48  .. +111  six seat blocks on the arc: two hole cards, name, stack, chips in front
 *   +116 .. +134  FOLD / CHECK-CALL / RAISE
 *   +140 .. +158  BOTS / SIT IN / LEAVE
 * </pre>
 *
 * <h2>Animation</h2>
 * Every card arrives from the server carrying the tick it landed on ({@code t} for the board,
 * {@code holeTicks[i]} for a seat), so the client can slide it out of the dealer's hand and
 * fade it in with no per-card packets. A local {@link #clientClock} runs at 20 Hz and is seeded
 * from the server's {@code animClock} the first time state arrives; the render clock is
 * {@code max(clientClock, serverAnimClock)} so a client that fell behind never shows a stale or
 * negative card age.
 */
public class TexasHoldemScreen extends BaseCasinoScreen {

    // ---------------------------------------------------------------- geometry
    private static final int SEAT_COUNT = TexasHoldemTable.MAX_SEATS;
    private static final int CARD_W = 20;
    private static final int CARD_H = 28;
    /** Horizontal offset between the two hole cards of one seat. */
    private static final int SEAT_STEP = 8;
    /** Horizontal offset between the community cards. */
    private static final int BOARD_STEP = 22;
    private static final int SLIDE_TICKS = 10;
    private static final int FLIP_TICKS = 6;
    private static final int RAISE_STEP = 50;
    private static final int BUY_IN = 500;

    /**
     * Seat arc: an ellipse under the board, 200 deg .. 340 deg in six steps. The horizontal
     * radius spreads the seats across the felt; the vertical radius is small, so the row dips
     * into a bowl instead of reading as a list.
     */
    private static final int ARC_CY = 88;
    private static final int ARC_RX = 144;
    private static final int ARC_RY = 11;
    private static final int ARC_FIRST_DEG = 200;
    private static final int ARC_STEP_DEG = 28;
    /** Lowest and highest the seat block's name line may sit, keeping it clear at both ends. */
    private static final int ARC_MIN_Y = 77;
    private static final int ARC_MAX_Y = 84;

    private static final int GOLD = 0xFFB8862C;
    private static final int GOLD_LIGHT = 0xFFFFD86B;
    private static final int GREY = 0xFF8A8A8A;
    private static final int BOT_GREY = 0xFF9AA7B1;
    private static final int FELT = 0x261B6B3F;
    private static final int FELT_DARK = 0x1A1B6B3F;

    // ------------------------------------------------------------------- model

    /** One card plus the server tick it landed on. {@code "??"} means "not for your eyes". */
    private static final class ClientCard {
        String c = "??";
        int t;
    }

    private static final class ClientSeat {
        int index;
        boolean occupied;
        String name = "";
        long stack;
        long currentBet;
        boolean bot;
        boolean folded;
        boolean allIn;
        String lastAction = "";
        final List<ClientCard> cards = new ArrayList<>();

        void reset() {
            occupied = false;
            name = "";
            stack = 0;
            currentBet = 0;
            bot = false;
            folded = false;
            allIn = false;
            lastAction = "";
            cards.clear();
        }

        /** True once the server has let this client see the hole cards. */
        boolean revealed() {
            if (cards.size() < 2) {
                return false;
            }
            for (ClientCard card : cards) {
                if ("??".equals(card.c)) {
                    return false;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------- state
    private String phase = "WAITING_FOR_PLAYERS";
    private long pot;
    private long currentHighestBet;
    private int turnSeat = -1;
    private String summary = "";
    private String dealerSay = "";
    private String ticker = "";
    private boolean autoFillBots = true;
    private boolean owner;
    private int handNumber;
    private int serverAnimClock;
    private int mySeat = -1;
    private long myBet;

    private int clientClock;
    private boolean clockSeeded;
    private int flipTicks;
    private String lastPhase = "";

    private final List<ClientCard> community = new ArrayList<>();
    private final ClientSeat[] seats = new ClientSeat[SEAT_COUNT];

    // --------------------------------------------------------------- widgets
    private SpriteButton foldButton;
    private SpriteButton checkButton;
    private SpriteButton raiseButton;
    private SpriteButton botsButton;
    private SpriteButton sitButton;
    private SpriteButton leaveButton;

    public TexasHoldemScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Texas Hold'em Poker Floor (Multiplayer)"), pos, initialStateJson, playerBalance);
        // 400 + the 8px frame overhang is 408 of the 427 a gui-scale-2 window gives, and
        // 232 + 8 is exactly the 240 it gives in height.
        this.bgWidth = 400;
        this.bgHeight = 232;
        for (int i = 0; i < SEAT_COUNT; i++) {
            seats[i] = new ClientSeat();
            seats[i].index = i;
        }
    }

    // ============================================================== widgets ===

    @Override
    protected void initGameWidgets() {
        onStateUpdated();

        int row1 = contentY() + 116;
        int row2 = contentY() + 140;

        foldButton = addRenderableWidget(SpriteButton.of(contentX() + 6, row1, 108, 18, "FOLD",
                SpriteButton.LABEL_RED, b -> sendAction("FOLD", "{}")));
        checkButton = addRenderableWidget(SpriteButton.of(contentX() + 122, row1, 108, 18, checkLabel(),
                SpriteButton.LABEL_GREEN, b -> sendAction(checkAction(), "{}")));
        raiseButton = addRenderableWidget(SpriteButton.primary(contentX() + 238, row1, 108, 18,
                "RAISE +" + RAISE_STEP, b -> sendAction("RAISE", raiseData())));

        botsButton = addRenderableWidget(SpriteButton.of(contentX() + 6, row2, 108, 18, botsLabel(),
                SpriteButton.LABEL_NORMAL, b -> sendAction("TOGGLE_BOTS", "{}")));
        sitButton = addRenderableWidget(SpriteButton.of(contentX() + 122, row2, 108, 18,
                "SIT IN " + BUY_IN, SpriteButton.LABEL_NORMAL, b -> joinFirstFreeSeat()));
        leaveButton = addRenderableWidget(SpriteButton.of(contentX() + 238, row2, 108, 18, "LEAVE",
                SpriteButton.LABEL_NORMAL, b -> sendAction("LEAVE", "{}")));

        applyEnablement();
    }

    /** The wire protocol only knows FOLD / CHECK / CALL / RAISE, so the verb is chosen here. */
    private String checkAction() {
        return currentHighestBet - myBet > 0 ? "CALL" : "CHECK";
    }

    private String checkLabel() {
        long call = currentHighestBet - myBet;
        return call > 0 ? "CALL " + call : "CHECK";
    }

    private String botsLabel() {
        return autoFillBots ? "BOTS ON" : "BOTS OFF";
    }

    private String raiseData() {
        JsonObject json = new JsonObject();
        json.addProperty("raise", RAISE_STEP);
        return GSON.toJson(json);
    }

    private void joinFirstFreeSeat() {
        int index = freeSeat();
        if (index < 0) {
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("seat", index);
        json.addProperty("buyIn", BUY_IN);
        sendAction("JOIN", GSON.toJson(json));
    }

    private int freeSeat() {
        for (ClientSeat seat : seats) {
            if (seat != null && !seat.occupied) {
                return seat.index;
            }
        }
        return -1;
    }

    /**
     * The table's controls are live only on this player's turn; the host's bot toggle only for
     * the host. JOIN and LEAVE stay available because the server rejects both harmlessly when
     * they do not apply, and the wire format does not tell an idle client which seat is his.
     */
    private void applyEnablement() {
        boolean turn = myTurn();
        setActive(foldButton, turn);
        setActive(checkButton, turn);
        setActive(raiseButton, turn);
        setActive(botsButton, owner);
        setActive(sitButton, mySeat < 0 && freeSeat() >= 0);
        setActive(leaveButton, true);
    }

    private static void setActive(SpriteButton button, boolean active) {
        if (button != null) {
            button.active = active;
        }
    }

    private boolean myTurn() {
        if (mySeat < 0 || mySeat >= SEAT_COUNT || turnSeat != mySeat) {
            return false;
        }
        ClientSeat mine = seats[mySeat];
        return mine != null && mine.occupied && !mine.folded && !mine.allIn;
    }

    // ================================================================= state ===

    @Override
    protected void onStateUpdated() {
        parseState(lastStateJson);
    }

    private void parseState(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JsonObject o = GSON.fromJson(json, JsonObject.class);
            if (o == null) {
                return;
            }
            phase = str(o, "phase", phase);
            pot = lng(o, "pot", pot);
            currentHighestBet = lng(o, "currentHighestBet", currentHighestBet);
            turnSeat = num(o, "turnSeat", turnSeat);
            summary = str(o, "summary", "");
            dealerSay = str(o, "dealerSay", "");
            autoFillBots = bool(o, "autoFillBots", autoFillBots);
            owner = bool(o, "isOwner", owner);
            handNumber = num(o, "handNumber", handNumber);
            serverAnimClock = num(o, "animClock", serverAnimClock);

            readCommunity(o);
            readSeats(o);
            readEvents(o);

            mySeat = deriveMySeat();
            myBet = mySeat >= 0 ? seats[mySeat].currentBet : 0;

            if (!clockSeeded) {
                clientClock = serverAnimClock;
                clockSeeded = true;
            }
            if (isShowdown() && !lastPhase.isEmpty() && !phaseIs(lastPhase, "SHOWDOWN")) {
                flipTicks = FLIP_TICKS;
            }
            lastPhase = phase;
        } catch (RuntimeException ignored) {
            // A malformed or half-written state must degrade, never take the screen down.
        }
    }

    private void readCommunity(JsonObject o) {
        community.clear();
        if (!o.has("communityCards") || !o.get("communityCards").isJsonArray()) {
            return;
        }
        for (JsonElement el : o.getAsJsonArray("communityCards")) {
            ClientCard card = readCard(el, 0);
            if (card != null) {
                community.add(card);
            }
        }
    }

    /**
     * Community cards are {@code {"c":"AS","t":1180}} objects; seat cards are still bare
     * strings. Both shapes are accepted so a half-ported server cannot blank the board.
     */
    private static ClientCard readCard(JsonElement el, int fallbackTick) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        ClientCard card = new ClientCard();
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            card.c = str(o, "c", "??");
            card.t = num(o, "t", fallbackTick);
        } else if (el.isJsonPrimitive()) {
            card.c = el.getAsString();
            card.t = fallbackTick;
        } else {
            return null;
        }
        if (card.c == null || card.c.isEmpty()) {
            card.c = "??";
        }
        return card;
    }

    private void readSeats(JsonObject o) {
        if (!o.has("seats") || !o.get("seats").isJsonArray()) {
            return;
        }
        for (JsonElement el : o.getAsJsonArray("seats")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject s = el.getAsJsonObject();
            int index = num(s, "index", -1);
            if (index < 0 || index >= SEAT_COUNT) {
                continue;
            }
            ClientSeat seat = seats[index];
            seat.reset();
            seat.index = index;
            seat.occupied = bool(s, "occupied", false);
            seat.name = str(s, "name", "");
            seat.stack = lng(s, "stack", 0);
            seat.currentBet = lng(s, "currentBet", 0);
            seat.bot = bool(s, "isBot", false);
            seat.folded = bool(s, "folded", false);
            seat.allIn = bool(s, "allIn", false);
            seat.lastAction = str(s, "lastAction", "");
            readHoleCards(s, seat);
        }
    }

    private static void readHoleCards(JsonObject s, ClientSeat seat) {
        seat.cards.clear();
        if (!s.has("cards") || !s.get("cards").isJsonArray()) {
            return;
        }
        List<Integer> ticks = new ArrayList<>();
        if (s.has("holeTicks") && s.get("holeTicks").isJsonArray()) {
            for (JsonElement t : s.getAsJsonArray("holeTicks")) {
                if (t.isJsonPrimitive() && t.getAsJsonPrimitive().isNumber()) {
                    ticks.add(t.getAsInt());
                }
            }
        }
        int i = 0;
        for (JsonElement el : s.getAsJsonArray("cards")) {
            ClientCard card = readCard(el, i < ticks.size() ? ticks.get(i) : 0);
            if (card != null) {
                seat.cards.add(card);
            }
            i++;
        }
    }

    private void readEvents(JsonObject o) {
        if (!o.has("events") || !o.get("events").isJsonArray()) {
            return;
        }
        var arr = o.getAsJsonArray("events");
        if (arr.size() == 0) {
            return;
        }
        // The ring buffer is oldest-first, so the last entry is the newest event.
        JsonElement last = arr.get(arr.size() - 1);
        if (last.isJsonObject()) {
            ticker = str(last.getAsJsonObject(), "x", ticker);
        }
    }

    /**
     * The wire format names no seat as "mine": the server simply reveals a seat's hole cards to
     * its owner, so during a hand the one seat whose cards are not {@code "??"} is this player's.
     * At showdown everything is revealed and nothing is left to act on, so the last known value
     * is kept.
     */
    private int deriveMySeat() {
        if (isShowdown()) {
            return mySeat;
        }
        for (ClientSeat seat : seats) {
            if (seat.occupied && seat.revealed()) {
                return seat.index;
            }
        }
        return mySeat;
    }

    // ==================================================================== tick

    @Override
    public void tick() {
        super.tick();
        clientClock++;
        if (flipTicks > 0) {
            flipTicks--;
        }
    }

    // ================================================================ render ===

    @Override
    protected void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        int clock = Math.max(clientClock, serverAnimClock);
        drawFelt(extractor);
        drawHeader(extractor);
        drawBoard(extractor, clock);
        drawSeats(extractor, clock);
    }

    /** Two baize bands: one behind the board, one behind the seats, split at the seat band. */
    private void drawFelt(GuiGraphicsExtractor e) {
        int x0 = contentX() + 5;
        int x1 = contentX() + contentW() - 5;
        e.fill(x0, contentY() + 17, x1, contentY() + 47, FELT);
        e.fill(x0, contentY() + 47, x1, contentY() + 113, FELT_DARK);
        e.fill(x0, contentY() + 17, x1, contentY() + 18, 0x30B8862C);
    }

    /**
     * Two 8px lines in {@code +0 .. +16}: the pot in the middle, the hand number and then the
     * dealer's own words at the right, and the newest table event at the left. At showdown the
     * dealer's slot carries the winning-hand summary instead, because that is the line a player
     * is actually reading.
     */
    private void drawHeader(GuiGraphicsExtractor e) {
        int cx = contentX() + contentW() / 2;
        // The close button owns the top-right corner of the panel, so right-aligned text stops
        // short of it exactly as the blackjack and baccarat headers do.
        int right = contentX() + contentW() - 34;

        e.centeredText(this.font, "POT " + pot, cx, contentY() + 1, GOLD_LIGHT);

        String hand = "Hand " + handNumber;
        e.text(this.font, hand, right - this.font.width(hand), contentY() + 1, GOLD);

        String say = isShowdown() && summary != null && !summary.isEmpty() ? summary : dealerSay;
        String spoken = fit(say == null ? "" : say.trim(), 190);
        if (!spoken.isEmpty()) {
            e.text(this.font, spoken, right - this.font.width(spoken), contentY() + 9,
                    isShowdown() ? COLOR_TEXT_WHITE : GREY);
        }

        String feed = fit(ticker == null ? "" : ticker, 140);
        if (!feed.isEmpty()) {
            e.text(this.font, feed, contentX() + 2, contentY() + 9, GREY);
        }
    }

    /**
     * The top of the felt: the dealer's button on the left, the five community cards fanned in
     * the middle, and the phase with the price to call on the right.
     */
    private void drawBoard(GuiGraphicsExtractor e, int clock) {
        int cx = contentX() + contentW() / 2;
        int fanX = cx - (CARD_W + (5 - 1) * BOARD_STEP) / 2;
        int fanY = contentY() + 18;

        drawDealer(e);

        for (int i = 0; i < 5; i++) {
            int x = fanX + i * BOARD_STEP;
            if (i < community.size()) {
                drawArrivingCard(e, community.get(i), x, fanY, clock);
            } else {
                drawSlot(e, x, fanY);
            }
        }
        // A felt shadow anchors the board to the table.
        e.fill(fanX - 6, fanY + CARD_H, fanX + CARD_W + 4 * BOARD_STEP + 6, fanY + CARD_H + 2, 0x30000000);

        drawPrice(e);
    }

    private void drawDealer(GuiGraphicsExtractor e) {
        int x = contentX() + 92;
        int y = contentY() + 18;
        e.fill(x, y, x + 24, y + 24, 0xFF140F0A);
        e.fill(x, y, x + 24, y + 1, GOLD);
        e.fill(x, y + 23, x + 24, y + 24, 0x60FFFFD8);
        e.fill(x, y, x + 1, y + 24, GOLD);
        e.fill(x + 23, y, x + 24, y + 24, GOLD);
        e.centeredText(this.font, "D", x + 12, y + 8, GOLD_LIGHT);

        String label = "DEALER";
        e.text(this.font, label, x - 4 - this.font.width(label), y + 8, GREY);
    }

    /** Phase on the first line, the price to call (or "check") on the second. */
    private void drawPrice(GuiGraphicsExtractor e) {
        int right = contentX() + contentW() - 4;
        String word = phaseWord();
        if (!word.isEmpty()) {
            e.text(this.font, word, right - this.font.width(word), contentY() + 20, GOLD);
        }
        long call = currentHighestBet - myBet;
        String hint = call > 0 ? "call " + call : "check";
        e.text(this.font, hint, right - this.font.width(hint), contentY() + 30,
                myTurn() ? COLOR_TEXT_GOLD : GREY);
    }

    private String phaseWord() {
        if (isShowdown()) {
            return "SHOWDOWN";
        }
        if (phaseIs(phase, "PREFLOP")) {
            return "PRE-FLOP";
        }
        if (phaseIs(phase, "WAITINGFORPLAYERS")) {
            return "WAITING";
        }
        if (phaseIs(phase, "DEALING")) {
            return "DEALING";
        }
        if (phaseIs(phase, "FLOP")) {
            return "FLOP";
        }
        if (phaseIs(phase, "TURN")) {
            return "TURN";
        }
        if (phaseIs(phase, "RIVER")) {
            return "RIVER";
        }
        return "";
    }

    // ---------------------------------------------------------------- seats ---

    private void drawSeats(GuiGraphicsExtractor e, int clock) {
        for (ClientSeat seat : seats) {
            if (seat == null) {
                continue;
            }
            int[] p = seatPoint(seat.index);
            if (!seat.occupied) {
                drawEmptySeat(e, p[0], p[1]);
                continue;
            }
            if (seat.index == turnSeat) {
                drawTurnRing(e, p[0], p[1], clock);
            }
            drawSeat(e, seat, p[0], p[1], clock);
        }
    }

    /**
     * Seat {@code index} on the arc under the board: 200 deg .. 340 deg in six steps, then
     * clamped so the outer seats never touch the frame and no block leaves the seat band
     * ({@code +48 .. +110}).
     */
    private int[] seatPoint(int index) {
        int cx = contentX() + contentW() / 2;
        double theta = Math.toRadians(ARC_FIRST_DEG + index * ARC_STEP_DEG);
        int x = cx + (int) Math.round(ARC_RX * Math.cos(theta));
        x = Math.max(contentX() + 18, Math.min(contentX() + contentW() - 18, x));
        int y = contentY() + ARC_CY + (int) Math.round(ARC_RY * Math.sin(theta));
        y = Math.max(contentY() + ARC_MIN_Y, Math.min(contentY() + ARC_MAX_Y, y));
        return new int[]{x, y};
    }

    /**
     * One seat block: the two hole cards arriving from the dealer, then the name, the stack and
     * the chips already pushed in ({@code sy-29 .. sy+27}).
     */
    private void drawSeat(GuiGraphicsExtractor e, ClientSeat seat, int sx, int sy, int clock) {
        int fanX = sx - (CARD_W + SEAT_STEP) / 2;
        boolean mine = seat.index == mySeat;

        for (int i = 0; i < 2; i++) {
            int x = fanX + i * SEAT_STEP;
            if (i >= seat.cards.size()) {
                drawSlot(e, x, sy - 29);
            } else if (flipTicks > 0 && isShowdown()) {
                drawFlip(e, x, sy - 29);
            } else {
                drawArrivingCard(e, seat.cards.get(i), x, sy - 29, clock);
            }
        }

        if (seat.folded) {
            // Folded: the cards are still there, dimmed out of the hand.
            e.fill(fanX, sy - 29, fanX + CARD_W + SEAT_STEP, sy - 1, 0x99000000);
        }

        int nameColor = mine ? COLOR_TEXT_GOLD : (seat.bot ? BOT_GREY : COLOR_TEXT_WHITE);
        e.centeredText(this.font, shortName(seat.name), sx, sy, seat.folded ? GREY : nameColor);

        e.centeredText(this.font, String.valueOf(seat.stack), sx, sy + 9,
                seat.folded ? GREY : (seat.allIn ? GOLD_LIGHT : COLOR_TEXT_WHITE));

        drawWager(e, seat, sx, sy + 18);
    }

    /** The chip in front of a seat, with the amount; a folded seat says so, an idle one shows its verb. */
    private void drawWager(GuiGraphicsExtractor e, ClientSeat seat, int sx, int y) {
        String text;
        int color;
        boolean chip = false;
        if (seat.folded) {
            text = "FOLD";
            color = GREY;
        } else if (seat.currentBet > 0) {
            text = String.valueOf(seat.currentBet);
            color = GOLD_LIGHT;
            chip = true;
        } else {
            text = fit(seat.lastAction == null ? "" : seat.lastAction, 34);
            color = GREY;
        }
        if (text.isEmpty()) {
            return;
        }
        int w = (chip ? 10 : 0) + this.font.width(text);
        int x = Math.max(contentX(), Math.min(contentX() + contentW() - w, sx - w / 2));
        if (chip) {
            drawChipPip(e, x, y + 1);
            e.text(this.font, text, x + 10, y + 1, color);
        } else {
            e.text(this.font, text, x, y + 1, color);
        }
    }

    private void drawEmptySeat(GuiGraphicsExtractor e, int sx, int sy) {
        int x0 = sx - 21;
        int y0 = sy - 29;
        int x1 = sx + 21;
        int y1 = sy + 14;
        for (int x = x0; x < x1; x += 6) {
            e.fill(x, y0, Math.min(x + 3, x1), y0 + 1, 0x50FFFFFF);
            e.fill(x, y1 - 1, Math.min(x + 3, x1), y1, 0x50FFFFFF);
        }
        for (int y = y0; y < y1; y += 6) {
            e.fill(x0, y, x0 + 1, Math.min(y + 3, y1), 0x50FFFFFF);
            e.fill(x1 - 1, y, x1, Math.min(y + 3, y1), 0x50FFFFFF);
        }
        e.centeredText(this.font, "empty", sx, sy - 6, 0xFF6F6F6F);
    }

    /** Pulsing gold ring around the seat to act: whose turn it is, at a glance. */
    private void drawTurnRing(GuiGraphicsExtractor e, int sx, int sy, int clock) {
        int pulse = (int) Math.round(110 + 90 * Math.sin(clock * 0.18));
        int alpha = Math.max(40, Math.min(220, pulse));
        int ring = (alpha << 24) | (GOLD_LIGHT & 0x00FFFFFF);

        int x0 = Math.max(contentX() + 1, sx - 23);
        int x1 = Math.min(contentX() + contentW() - 1, sx + 23);
        int y0 = Math.max(contentY() + 47, sy - 31);
        int y1 = Math.min(contentY() + 112, sy + 29);
        e.fill(x0, y0, x1, y0 + 1, ring);
        e.fill(x0, y1 - 1, x1, y1, ring);
        e.fill(x0, y0, x0 + 1, y1, ring);
        e.fill(x1 - 1, y0, x1, y1, ring);
    }

    // ------------------------------------------------------------- one card ---

    /**
     * Draws one card sliding out of the dealer's hand into its slot, with a dark veil that
     * lifts as it settles: the sprites cannot be drawn with an alpha tint, so the veil is how
     * a card fades in.
     */
    private void drawArrivingCard(GuiGraphicsExtractor e, ClientCard card, int slotX, int slotY, int clock) {
        double p = ease(progress(clock, card.t));
        int x = (int) Math.round(lerp(dealerX(), slotX, p));
        int y = (int) Math.round(lerp(dealerY(), slotY, p));
        drawCard(e, x, y, card.c);
        if (p < 1.0) {
            int veil = (int) ((1.0 - p) * 190.0);
            e.fill(x, y, x + CARD_W, y + CARD_H, veil << 24);
        }
    }

    /** The showdown turn-over: a gold bar where the card will be, instead of the face. */
    private void drawFlip(GuiGraphicsExtractor e, int slotX, int slotY) {
        int w = 6;
        int bx = slotX + (CARD_W - w) / 2;
        e.fill(bx, slotY + 3, bx + w, slotY + CARD_H - 3, GOLD);
        e.fill(bx, slotY + 3, bx + 2, slotY + CARD_H - 3, GOLD_LIGHT);
    }

    /** An empty card slot: a dim plate, so the table never looks unfinished. */
    private void drawSlot(GuiGraphicsExtractor e, int x, int y) {
        e.fill(x, y, x + CARD_W, y + CARD_H, 0x18FFFFFF);
        e.fill(x, y, x + CARD_W, y + 1, 0x28FFFFFF);
    }

    /** The 8px chip in front of a seat: dark rim, gold face. */
    private void drawChipPip(GuiGraphicsExtractor e, int x, int y) {
        e.fill(x, y, x + 8, y + 8, 0xFF7A1F24);
        e.fill(x + 1, y + 1, x + 7, y + 7, 0xFF1E1410);
        e.fill(x + 2, y + 2, x + 6, y + 6, GOLD);
    }

    private int dealerX() {
        return contentX() + 104;
    }

    private int dealerY() {
        return contentY() + 30;
    }

    // ------------------------------------------------------------- helpers ---

    private static double progress(int clock, int tick) {
        double age = Math.max(0, clock - tick);
        return Math.max(0.0, Math.min(1.0, age / (double) SLIDE_TICKS));
    }

    private static double ease(double k) {
        double inv = 1.0 - k;
        return 1.0 - inv * inv * inv;
    }

    private static double lerp(double from, double to, double k) {
        return from + (to - from) * k;
    }

    private static boolean phaseIs(String value, String name) {
        return value != null && value.replace("_", "").equalsIgnoreCase(name);
    }

    private boolean isShowdown() {
        return phaseIs(phase, "SHOWDOWN") || phaseIs(phase, "HANDENDED");
    }

    private static String shortName(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        String trimmed = name.trim();
        return trimmed.length() > 6 ? trimmed.substring(0, 6) + "\u2026" : trimmed;
    }

    private String fit(String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (this.font == null || this.font.width(text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && this.font.width(cut + "\u2026") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "\u2026";
    }

    private static String str(JsonObject o, String key, String fallback) {
        if (!o.has(key)) {
            return fallback;
        }
        JsonElement el = o.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : fallback;
    }

    private static int num(JsonObject o, String key, int fallback) {
        if (!o.has(key)) {
            return fallback;
        }
        JsonElement el = o.get(key);
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber() ? el.getAsInt() : fallback;
    }

    private static long lng(JsonObject o, String key, long fallback) {
        if (!o.has(key)) {
            return fallback;
        }
        JsonElement el = o.get(key);
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber() ? el.getAsLong() : fallback;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        if (!o.has(key)) {
            return fallback;
        }
        JsonElement el = o.get(key);
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() ? el.getAsBoolean() : fallback;
    }
}
