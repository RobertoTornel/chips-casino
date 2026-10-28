package dev.casino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Six-seat multiplayer blackjack table.
 *
 * <p>The table is seen from the dealer's side of the shoe: the dealer's hand and shoe sit
 * at the top of the felt, six seats fan across the middle, and betting/table verbs sit in two
 * compact rows at the bottom. Every element is positioned from {@code contentX()}/{@code
 * contentY()}, so the whole table moves with the cabinet inside a fixed {@code 352 x 168}
 * interior.
 *
 * <h2>Vertical budget (interior 352 x 168)</h2>
 * <pre>
 *   +0   .. +14   header: pot, hand #, dealer's line, ticker
 *   +18  .. +46   dealer's hand + shoe (left)
 *   +48  .. +82   six seats on a shallow arc
 *   +88  .. +104  four chip denomination buttons + DEAL
 *   +106 .. +122  HIT / STAND / DOUBLE / SPLIT / SIT OUT / BOTS
216:  * </pre>
 *
 * <h2>Animation</h2>
 * Every card carries the server tick it landed on ({@code t}), so the client can slide each one
 * out of the shoe and fade it in with no per-card packets. A local {@link #clientClock} runs at
 * 20 Hz and is seeded from the server's {@code animClock} the first time state arrives; the
 * render clock is {@code max(clientClock, serverAnimClock)} so a client that fell behind
 * (a lag spike, a resize) never shows a stale or negative card age.
 */
public class BlackjackScreen extends BaseCasinoScreen {

    // ---------------------------------------------------------------- geometry
    private static final int SEAT_COUNT = 6;
    private static final int SHOE_SIZE = 52;
    private static final int CARD_W = 20;
    private static final int CARD_H = 28;
    /** Horizontal offset between the dealer's fanned cards. */
    private static final int DEALER_STEP = 14;
    /** Horizontal offset between a seat's fanned cards. */
    private static final int SEAT_STEP = 8;
    private static final int SLIDE_TICKS = 10;
    private static final int FLIP_TICKS = 6;
    private static final int CHIP_TICKS = 6;

    // Seat arc: an ellipse under the dealer. Angles run 200 deg .. 340 deg in six steps.
    private static final int ARC_RX = 150;
    private static final int ARC_RY = 28;
    private static final int ARC_STEP_DEG = 28;
    private static final int ARC_FIRST_DEG = 200;

    private static final int FELT = 0xFF17603A;
    private static final int FELT_DARK = 0xFF0B2C1C;
    private static final int GOLD = 0xFFB8862C;
    private static final int GOLD_LIGHT = 0xFFFFD86B;
    private static final int BUST_RED = 0xFFB32630;
    private static final int GREY = 0xFF8A8A8A;

    // ------------------------------------------------------------------- model
    private static final class ClientCard {
        String c = "??";
        int t;
        boolean hidden;
    }

    private static final class ClientHand {
        long bet;
        boolean doubled;
        String state = "ACTIVE";
        String outcome = "NONE";
        int value;
        boolean soft;
        int index;
        final List<ClientCard> cards = new ArrayList<>();
    }

    private static final class Seat {
        int index;
        boolean occupied;
        String name = "";
        boolean bot;
        long bet;
        long net;
        boolean active;
        boolean finished;
        boolean mine;
        final List<ClientHand> hands = new ArrayList<>();

        void reset() {
            occupied = false;
            name = "";
            bot = false;
            bet = 0;
            net = 0;
            active = false;
            finished = false;
            mine = false;
            hands.clear();
        }
    }

    // ------------------------------------------------------------------- state
    private String phase = "WAITING_FOR_PLAYERS";
    private int serverAnimClock;
    private int phaseTicks;
    private int activeSeat = -1;
    private boolean dealerRevealed;
    private String dealerSay = "";
    private int round;
    private boolean seated;
    private int mySeat = -1;
    private boolean botsOn = true;
    private boolean owner;
    private long minBet = 10;
    private long maxBet = 10_000;
    private int shoe;
    private int dealerShown;
    private int dealerValue;
    private String ticker = "";
    private final List<ClientCard> dealerCards = new ArrayList<>();
    private final Seat[] seats = new Seat[SEAT_COUNT];

    // --------------------------------------------------------------- animation
    private int clientClock;
    private boolean clockSeeded;
    private int flipTicks;
    private boolean sawReveal;
    private boolean stateSeen;

    // ----------------------------------------------------------------- widgets
    private SpriteButton betTen;
    private SpriteButton betFifty;
    private SpriteButton betHundred;
    private SpriteButton betFiveHundred;
    private SpriteButton dealButton;
    private SpriteButton hitButton;
    private SpriteButton standButton;
    private SpriteButton doubleButton;
    private SpriteButton splitButton;
    private SpriteButton leaveButton;
    private SpriteButton botsButton;

    public BlackjackScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Blackjack - Six Seat Table"), pos, initialStateJson, playerBalance);
        this.bgWidth = 400;
        this.bgHeight = 232;
        for (int i = 0; i < SEAT_COUNT; i++) {
            seats[i] = new Seat();
            seats[i].index = i;
        }
    }

    // ============================================================== widgets ===

    @Override
    protected void initGameWidgets() {
        // Row 1 (betting): four chip denominations then DEAL, centred in the interior.
        int row1Y = contentY() + 130;
        int row1CenterX = contentX() + contentW() / 2 - 2 * 30 - 3 * 10 - 44 - 10;
        betTen = addRenderableWidget(SpriteButton.of(contentX() + 92, row1Y, 30, 16, "10", b -> placeBet(10)));
        betFifty = addRenderableWidget(SpriteButton.of(contentX() + 126, row1Y, 30, 16, "50", b -> placeBet(50)));
        betHundred = addRenderableWidget(SpriteButton.of(contentX() + 160, row1Y, 30, 16, "100", b -> placeBet(100)));
        betFiveHundred = addRenderableWidget(SpriteButton.of(contentX() + 194, row1Y, 30, 16, "500", b -> placeBet(500)));
        dealButton = addRenderableWidget(SpriteButton.primary(contentX() + 232, row1Y, 44, 16, "DEAL",
                b -> sendAction("DEAL", "{}")));

        // Row 2 (play): the five table verbs plus the house's bot toggle.
        int row2Y = contentY() + 152;
        hitButton = addRenderableWidget(SpriteButton.colored(contentX() + 42, row2Y, 44, 16, "HIT",
                SpriteButton.LABEL_GREEN, b -> sendAction("HIT", "{}")));
        standButton = addRenderableWidget(SpriteButton.colored(contentX() + 90, row2Y, 44, 16, "STAND",
                SpriteButton.LABEL_NORMAL, b -> sendAction("STAND", "{}")));
        doubleButton = addRenderableWidget(SpriteButton.colored(contentX() + 138, row2Y, 44, 16, "DOUBLE",
                SpriteButton.LABEL_PRIMARY, b -> sendAction("DOUBLE", "{}")));
        splitButton = addRenderableWidget(SpriteButton.colored(contentX() + 186, row2Y, 44, 16, "SPLIT",
                SpriteButton.LABEL_PRIMARY, b -> sendAction("SPLIT", "{}")));
        leaveButton = addRenderableWidget(SpriteButton.colored(contentX() + 234, row2Y, 44, 16, "SIT OUT",
                SpriteButton.LABEL_RED, b -> sendAction("LEAVE", "{}")));
        botsButton = addRenderableWidget(SpriteButton.colored(contentX() + 282, row2Y, 44, 16, "BOTS",
                botsOn ? SpriteButton.LABEL_GREEN : SpriteButton.LABEL_BLACK,
                b -> sendAction("TOGGLE_BOTS", "{}")));

        applyEnablement();
    }

    private void placeBet(long amount) {
        JsonObject json = new JsonObject();
        json.addProperty("bet", amount);
        sendAction("BET", GSON.toJson(json));
    }

    private void enable(SpriteButton button, boolean active) {
        if (button != null) {
            button.active = active;
        }
    }

    /**
     * Enables exactly the verbs the table would accept right now: betting only between rounds,
     * play actions only on your own live hand, DOUBLE only on a fresh two-card hand and SPLIT
     * only on a genuine pair.
     */
    private void applyEnablement() {
        boolean betting = seated && ("BETTING".equals(phase) || "WAITING_FOR_PLAYERS".equals(phase));
        ClientHand hand = activeHand();
        boolean acting = "PLAYER_TURNS".equals(phase) && mySeat >= 0 && activeSeat == mySeat
                && hand != null && "ACTIVE".equals(hand.state);

        enable(betTen, betting && 10 >= minBet && 10 <= maxBet);
        enable(betFifty, betting && 50 >= minBet && 50 <= maxBet);
        enable(betHundred, betting && 100 >= minBet && 100 <= maxBet);
        enable(betFiveHundred, betting && 500 >= minBet && 500 <= maxBet);
        enable(dealButton, betting);

        enable(hitButton, acting);
        enable(standButton, acting);
        enable(doubleButton, acting && hand.cards.size() == 2 && !hand.doubled);
        enable(splitButton, acting && canSplit(hand));
        enable(leaveButton, seated);
        enable(botsButton, owner);
    }

    private boolean canSplit(ClientHand hand) {
        if (hand.cards.size() != 2) {
            return false;
        }
        String a = rankOf(hand.cards.get(0).c);
        String b = rankOf(hand.cards.get(1).c);
        return !a.isEmpty() && a.equals(b);
    }

    /** Rank portion of a card string, used for the split comparison; empty for a hidden card. */
    private static String rankOf(String card) {
        if (card == null || "??".equals(card)) {
            return "";
        }
        return card.replaceAll("[^0-9JQKA]", "");
    }

    // ================================================================ state ===

    @Override
    protected void onStateUpdated() {
        try {
            JsonObject obj = GSON.fromJson(lastStateJson, JsonObject.class);
            if (obj != null) {
                if (obj.has("phase")) {
                    phase = obj.get("phase").getAsString();
                }
                if (obj.has("animClock")) {
                    serverAnimClock = obj.get("animClock").getAsInt();
                }
                if (obj.has("phaseTicks")) {
                    phaseTicks = obj.get("phaseTicks").getAsInt();
                }
                if (obj.has("activeSeat")) {
                    activeSeat = obj.get("activeSeat").getAsInt();
                }
                if (obj.has("dealerRevealed")) {
                    dealerRevealed = obj.get("dealerRevealed").getAsBoolean();
                }
                if (obj.has("dealerSay")) {
                    dealerSay = obj.get("dealerSay").getAsString();
                }
                if (obj.has("round")) {
                    round = obj.get("round").getAsInt();
                }
                if (obj.has("seated")) {
                    seated = obj.get("seated").getAsBoolean();
                }
                if (obj.has("mySeat")) {
                    mySeat = obj.get("mySeat").getAsInt();
                }
                if (obj.has("autoFillBots")) {
                    botsOn = obj.get("autoFillBots").getAsBoolean();
                }
                if (obj.has("isOwner")) {
                    owner = obj.get("isOwner").getAsBoolean();
                }
                if (obj.has("minBet")) {
                    minBet = obj.get("minBet").getAsLong();
                }
                if (obj.has("maxBet")) {
                    maxBet = obj.get("maxBet").getAsLong();
                }
                if (obj.has("shoe")) {
                    shoe = obj.get("shoe").getAsInt();
                }
                readDealer(obj);
                readSeats(obj);
                readEvents(obj);
                noteReveal(dealerRevealed);
            }
        } catch (RuntimeException ignored) {
            // A malformed packet must never kill the screen: keep the last good table.
        }
        applyEnablement();
    }

    private void readDealer(JsonObject obj) {
        dealerCards.clear();
        if (!obj.has("dealer") || !obj.get("dealer").isJsonObject()) {
            return;
        }
        JsonObject d = obj.getAsJsonObject("dealer");
        if (d.has("cards") && d.get("cards").isJsonArray()) {
            dealerCards.addAll(readCards(d.getAsJsonArray("cards")));
        }
        if (d.has("shown")) {
            dealerShown = d.get("shown").getAsInt();
        }
        if (d.has("value")) {
            dealerValue = d.get("value").getAsInt();
        }
    }

    private void readSeats(JsonObject obj) {
        for (Seat s : seats) {
            s.reset();
        }
        if (!obj.has("seats") || !obj.get("seats").isJsonArray()) {
            return;
        }
        for (var el : obj.getAsJsonArray("seats")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (!o.has("index")) {
                continue;
            }
            int index = o.get("index").getAsInt();
            if (index < 0 || index >= SEAT_COUNT) {
                continue;
            }
            Seat s = seats[index];
            s.index = index;
            if (o.has("occupied")) {
                s.occupied = o.get("occupied").getAsBoolean();
            }
            if (o.has("name")) {
                s.name = o.get("name").getAsString();
            }
            if (o.has("bot")) {
                s.bot = o.get("bot").getAsBoolean();
            }
            if (o.has("bet")) {
                s.bet = o.get("bet").getAsLong();
            }
            if (o.has("net")) {
                s.net = o.get("net").getAsLong();
            }
            if (o.has("active")) {
                s.active = o.get("active").getAsBoolean();
            }
            if (o.has("finished")) {
                s.finished = o.get("finished").getAsBoolean();
            }
            if (o.has("mine")) {
                s.mine = o.get("mine").getAsBoolean();
            }
            if (o.has("hands") && o.get("hands").isJsonArray()) {
                for (var he : o.getAsJsonArray("hands")) {
                    if (he.isJsonObject()) {
                        s.hands.add(readHand(he.getAsJsonObject()));
                    }
                }
            }
        }
    }

    private ClientHand readHand(JsonObject o) {
        ClientHand h = new ClientHand();
        if (o.has("bet")) {
            h.bet = o.get("bet").getAsLong();
        }
        if (o.has("doubled")) {
            h.doubled = o.get("doubled").getAsBoolean();
        }
        if (o.has("state")) {
            h.state = o.get("state").getAsString();
        }
        if (o.has("outcome")) {
            h.outcome = o.get("outcome").getAsString();
        }
        if (o.has("value")) {
            h.value = o.get("value").getAsInt();
        }
        if (o.has("soft")) {
            h.soft = o.get("soft").getAsBoolean();
        }
        if (o.has("index")) {
            h.index = o.get("index").getAsInt();
        }
        if (o.has("cards") && o.get("cards").isJsonArray()) {
            h.cards.addAll(readCards(o.getAsJsonArray("cards")));
        }
        return h;
    }

    private static List<ClientCard> readCards(JsonArray arr) {
        List<ClientCard> out = new ArrayList<>();
        for (var el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            ClientCard c = new ClientCard();
            if (o.has("c")) {
                c.c = o.get("c").getAsString();
            }
            if (o.has("t")) {
                c.t = o.get("t").getAsInt();
            }
            if (o.has("hidden")) {
                c.hidden = o.get("hidden").getAsBoolean();
            }
            out.add(c);
        }
        return out;
    }

    private void readEvents(JsonObject obj) {
        if (!obj.has("events") || !obj.get("events").isJsonArray()) {
            return;
        }
        JsonArray arr = obj.getAsJsonArray("events");
        if (arr.size() == 0) {
            return;
        }
        var last = arr.get(arr.size() - 1);
        if (last.isJsonObject() && last.getAsJsonObject().has("x")) {
            ticker = last.getAsJsonObject().get("x").getAsString();
        }
    }

    /** First state seeds the local clock; a reveal edge starts the hole-card flip. */
    private void noteReveal(boolean revealed) {
        if (!clockSeeded) {
            clientClock = serverAnimClock;
            clockSeeded = true;
        }
        if (stateSeen && revealed && !sawReveal) {
            flipTicks = FLIP_TICKS;
        }
        sawReveal = revealed;
        stateSeen = true;
    }

    private ClientHand activeHand() {
        if (mySeat < 0 || mySeat >= SEAT_COUNT) {
            return null;
        }
        Seat mine = seats[mySeat];
        if (mine == null || mine.hands.isEmpty()) {
            return null;
        }
        for (ClientHand h : mine.hands) {
            if ("ACTIVE".equals(h.state)) {
                return h;
            }
        }
        return mine.hands.get(0);
    }

    // ================================================================= tick ===

    @Override
    public void tick() {
        super.tick();
        clientClock++;
        if (flipTicks > 0) {
            flipTicks--;
        }
    }

    // =============================================================== render ===

    @Override
    protected void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        int clock = Math.max(clientClock, serverAnimClock);
        drawFelt(extractor);
        drawDealerZone(extractor, clock);
        drawSeats(extractor, clock);
        drawTicker(extractor);
    }

    /** Soft green table surface under the arc, so the seats read as sitting at a table. */
    private void drawFelt(GuiGraphicsExtractor e) {
        int x0 = contentX() + 14;
        int x1 = contentX() + contentW() - 14;
        int y0 = contentY() + 46;
        int y1 = contentY() + 129;
        e.fill(x0 + 12, y0, x1 - 12, y1 - 10, 0x2A0B2C1C);
        e.fill(x0, y0 + 10, x1, y1 - 22, 0x2A0B2C1C);
        e.fill(x0 + 12, y1 - 10, x1 - 12, y1, 0x2A0B2C1C);
    }

    private void drawDealerZone(GuiGraphicsExtractor e, int clock) {
        int cx = contentX() + contentW() / 2;
        int fanY = contentY() + 2;
        int count = dealerCards.size();
        int fanW = count <= 0 ? CARD_W : CARD_W + (count - 1) * DEALER_STEP;
        int fanX = cx - fanW / 2;

        drawShoe(e, contentX() + contentW() - 40, contentY(), 38, 28);

        // A shallow felt shadow anchors the dealer's fan to the table.
        e.fill(fanX - 8, fanY + CARD_H - 3, fanX + fanW + 8, fanY + CARD_H, 0x30000000);

        for (int i = 0; i < count; i++) {
            ClientCard card = dealerCards.get(i);
            int slotX = fanX + i * DEALER_STEP;
            boolean flipping = flipTicks > 0 && (i == (count > 1 ? 1 : 0));
            if (flipping) {
                drawFlip(e, slotX, fanY);
            } else {
                drawArrivingCard(e, card, slotX, fanY, clock);
            }
        }

        // Dealer total: the visible part while the hole card is down, the real total after.
        int badgeX = Math.max(contentX() + 2, fanX - 44);
        String total = String.valueOf(dealerRevealed ? dealerValue : dealerShown);
        drawBadge(e, badgeX, fanY + 8, 36, 12, total, GOLD, GOLD_LIGHT);

        drawSpeechBubble(e, cx);
    }

    private void drawShoe(GuiGraphicsExtractor e, int x, int y, int w, int h) {
        e.fill(x, y, x + w, y + h, 0xFF140F0A);
        e.fill(x, y, x + w, y + 1, GOLD);
        e.fill(x, y + h - 1, x + w, y + h, 0x60FFFFD8);
        e.fill(x, y, x + 1, y + h, GOLD);
        e.fill(x + w - 1, y, x + w, y + h, GOLD);

        // The stack of card backs left in the shoe.
        for (int i = 2; i >= 0; i--) {
            int bx = x + 4 + i * 3;
            e.fill(bx, y + 4, bx + 14, y + 22, 0xFF5B1D1D);
            e.fill(bx, y + 4, bx + 14, y + 5, 0xFF8C3B3B);
        }

        // Depth gauge: fills from the bottom as the shoe empties.
        int gx = x + w - 10;
        e.fill(gx, y + 4, gx + 6, y + 24, 0xFF241A10);
        double depth = Math.max(0.0, Math.min(1.0, shoe / (double) SHOE_SIZE));
        int filled = (int) Math.round(20 * depth);
        if (filled > 0) {
            e.fill(gx + 1, y + 24 - filled, gx + 5, y + 24, GOLD_LIGHT);
        }
    }

    private void drawSpeechBubble(GuiGraphicsExtractor e, int cx) {
        int bx = cx - 84;
        int by = contentY() + 32;
        int bw = 168;
        int bh = 14;
        e.fill(bx, by, bx + bw, by + bh, 0xCC0B1A12);
        e.fill(bx, by, bx + bw, by + 1, FELT);
        e.fill(bx, by + bh - 1, bx + bw, by + bh, FELT);
        e.fill(bx, by, bx + 1, by + bh, FELT);
        e.fill(bx + bw - 1, by, bx + bw, by + bh, FELT);
        e.text(this.font, "R" + round, bx + 3, by + 3, GOLD_LIGHT);
        String say = dealerSay == null ? "" : dealerSay.trim();
        if (say.isEmpty()) {
            say = "Dealer shuffles the shoe...";
            e.centeredText(this.font, fit(say, bw - 40), cx + 6, by + 3, GREY);
        } else {
            e.text(this.font, say, bx + 3, by + 3, COLOR_TEXT_WHITE);
        }
    }

    private void drawTicker(GuiGraphicsExtractor e) {
        if (ticker == null || ticker.isEmpty()) {
            return;
        }
        e.text(this.font, fit(ticker, 84), contentX() + 2, contentY() + 34, GREY);
    }

    private void drawSeats(GuiGraphicsExtractor e, int clock) {
        for (Seat seat : seats) {
            if (seat != null && seat.occupied && seat.active) {
                int[] p = seatPoint(seat.index);
                drawActiveRing(e, p[0], p[1], clock);
            }
        }
        for (Seat seat : seats) {
            if (seat == null) {
                continue;
            }
            int[] p = seatPoint(seat.index);
            drawSeat(e, seat, p[0], p[1], clock);
        }
    }

    /** Pulsing gold ring plus a pointing marker: whose turn it is, at a glance. */
    private void drawActiveRing(GuiGraphicsExtractor e, int sx, int sy, int clock) {
        int pulse = (int) Math.round(110 + 90 * Math.sin(clock * 0.18));
        int alpha = Math.max(40, Math.min(220, pulse));
        int ring = (alpha << 24) | (GOLD_LIGHT & 0x00FFFFFF);

        int x0 = Math.max(contentX() + 1, sx - 32);
        int x1 = Math.min(contentX() + contentW() - 1, sx + 32);
        int y0 = Math.max(contentY() + 1, sy - 33);
        int y1 = Math.min(contentY() + 130, sy + 34);
        e.fill(x0, y0, x1, y0 + 1, ring);
        e.fill(x0, y1 - 1, x1, y1, ring);
        e.fill(x0, y0, x0 + 1, y1, ring);
        e.fill(x1 - 1, y0, x1, y1, ring);

        // Small triangle pointing at the seat from the left of its value badge.
        int ty = sy + 16;
        e.fill(sx - 20, ty, sx - 13, ty + 1, 0xFFFFD86B);
        e.fill(sx - 20, ty + 1, sx - 15, ty + 2, 0xFFFFD86B);
        e.fill(sx - 20, ty + 2, sx - 17, ty + 3, 0xFFFFD86B);
    }

    private void drawSeat(GuiGraphicsExtractor e, Seat seat, int sx, int sy, int clock) {
        if (!seat.occupied) {
            drawEmptySeat(e, sx, sy);
            return;
        }
        ClientHand primary = seat.hands.isEmpty() ? null : seat.hands.get(0);
        int handCount = Math.min(2, seat.hands.size());
        int fanW = primary == null ? CARD_W : CARD_W + (primary.cards.size() - 1) * SEAT_STEP;
        if (fanW < CARD_W) {
            fanW = CARD_W;
        }

        drawChips(e, seat, sx, sy, fanW);
        for (int h = 0; h < handCount; h++) {
            ClientHand hand = seat.hands.get(h);
            int hx = sx + (handCount > 1 ? (h == 0 ? -16 : 16) : 0);
            int step = handCount > 1 ? 8 : SEAT_STEP;
            int width = hand.cards.isEmpty() ? CARD_W : CARD_W + (hand.cards.size() - 1) * step;
            int fanX = clampX(hx - width / 2);
            for (int i = 0; i < hand.cards.size(); i++) {
                drawArrivingCard(e, hand.cards.get(i), fanX + i * step, sy - 30, clock);
            }
        }
        drawPlate(e, seat, primary, sx, sy);
    }

    private void drawChips(GuiGraphicsExtractor e, Seat seat, int sx, int sy, int fanW) {
        if (seat.bet <= 0) {
            return;
        }
        double land = 1.0;
        if ("BETTING".equals(phase)) {
            land = ease(Math.max(0.0, Math.min(1.0, phaseTicks / (double) CHIP_TICKS)));
        }
        int chipX = Math.max(contentX() + 1, sx - fanW / 2 - 20);
        int chipY = sy - 28;
        if (land > 0.35) {
            drawChip(e, chipX, chipY);
        }
        if (land < 1.0) {
            // The stack "lands": a gold plate that grows into the chip's footprint.
            int size = Math.max(2, (int) Math.round(16 * land));
            int gx = chipX + (16 - size) / 2;
            int gy = chipY + (16 - size) / 2;
            e.fill(gx, gy, gx + size, gy + size, 0x55FFD86B);
        }
        e.text(this.font, String.valueOf(seat.bet), chipX + 1, sy - 11, GOLD_LIGHT);
    }

    private void drawPlate(GuiGraphicsExtractor e, Seat seat, ClientHand hand, int sx, int sy) {
        int nameColor = seat.mine ? COLOR_TEXT_GOLD : (seat.bot ? 0xFF9AA7B1 : COLOR_TEXT_WHITE);
        e.centeredText(this.font, shortName(seat.name), sx, sy + 1, nameColor);

        int value = hand == null ? 0 : hand.value;
        String label = hand == null ? "-" : (hand.soft && value < 21 ? value + "s" : String.valueOf(value));
        drawBadge(e, sx - 11, sy + 11, 22, 11, label, badgeColor(hand), COLOR_TEXT_WHITE);

        String outcome = outcomeWord(hand);
        if (!outcome.isEmpty()) {
            e.centeredText(this.font, outcome, sx - 13, sy + 23, outcomeColor(hand));
        }
        if (seat.net != 0) {
            String net = (seat.net > 0 ? "+" : "") + seat.net;
            e.centeredText(this.font, net, sx + 15, sy + 23, seat.net > 0 ? COLOR_TEXT_GREEN : COLOR_TEXT_RED);
        }
    }

    private void drawEmptySeat(GuiGraphicsExtractor e, int sx, int sy) {
        int x0 = sx - 21;
        int y0 = sy - 16;
        int x1 = sx + 21;
        int y1 = sy + 14;
        // Dashed outline: four sides drawn as 3px dashes with 3px gaps.
        for (int x = x0; x < x1; x += 6) {
            e.fill(x, y0, Math.min(x + 3, x1), y0 + 1, 0x50FFFFFF);
            e.fill(x, y1 - 1, Math.min(x + 3, x1), y1, 0x50FFFFFF);
        }
        for (int y = y0; y < y1; y += 6) {
            e.fill(x0, y, x0 + 1, Math.min(y + 3, y1), 0x50FFFFFF);
            e.fill(x1 - 1, y, x1, Math.min(y + 3, y1), 0x50FFFFFF);
        }
        e.centeredText(this.font, "empty", sx, sy - 4, 0xFF6F6F6F);
    }

    // ------------------------------------------------------------- one card ---

    /**
     * Draws one card sliding out of the shoe into its slot, with a dark veil that lifts as it
     * settles so the card appears to fade in (sprites cannot be drawn with an alpha tint).
     */
    private void drawArrivingCard(GuiGraphicsExtractor e, ClientCard card, int slotX, int slotY,
                                  int clock) {
        double p = ease(progress(clock, card.t));
        int x = (int) Math.round(lerp(shoeX(), slotX, p));
        int y = (int) Math.round(lerp(shoeY(), slotY, p));
        drawCard(e, x, y, card.c);
        if (p < 1.0) {
            int veil = (int) ((1.0 - p) * 190.0);
            e.fill(x, y, x + CARD_W, y + CARD_H, veil << 24);
        }
    }

    /** The hole card turning over: a gold bar whose width follows |cos| of the flip arc. */
    private void drawFlip(GuiGraphicsExtractor e, int slotX, int slotY) {
        double t = 1.0 - flipTicks / (double) FLIP_TICKS;
        int width = Math.max(2, (int) Math.round(CARD_W * Math.abs(Math.cos(Math.PI * t))));
        int x = slotX + (CARD_W - width) / 2;
        e.fill(x, slotY, x + width, slotY + CARD_H, GOLD);
        if (width > 3) {
            e.fill(x + 1, slotY + 1, x + width - 1, slotY + CARD_H - 1, GOLD_LIGHT);
        }
    }

    // -------------------------------------------------------------- chrome ----

    private void drawBadge(GuiGraphicsExtractor e, int x, int y, int w, int h, String text, int bg, int fg) {
        e.fill(x, y, x + w, y + h, darken(bg, 0.55));
        e.fill(x, y, x + w, y + 1, bg);
        e.fill(x, y + h - 1, x + w, y + h, darken(bg, 0.4));
        e.centeredText(this.font, text, x + w / 2, y + (h - 8) / 2, fg);
    }

    // ------------------------------------------------------------ geometry ---

    /**
     * Seat {@code index} on the ellipse under the dealer: 200 deg .. 340 deg in six steps, then
     * clamped into the interior so the outer seats never touch the frame.
     */
    private int[] seatPoint(int index) {
        int cx = contentX() + contentW() / 2;
        int cy = contentY() + 104;
        double theta = Math.toRadians(ARC_FIRST_DEG + index * ARC_STEP_DEG);
        int x = cx + (int) (ARC_RX * Math.cos(theta));
        int y = cy + (int) (ARC_RY * Math.sin(theta));
        x = Math.max(contentX() + 34, Math.min(contentX() + contentW() - 58, x));
        y = Math.max(contentY() + 72, Math.min(contentY() + 124, y));
        return new int[]{x, y};
    }

    private int shoeX() {
        return contentX() + contentW() - 28;
    }

    private int shoeY() {
        return contentY() + 8;
    }

    private int clampX(int x) {
        return Math.max(contentX() + 1, Math.min(contentX() + contentW() - CARD_W - 1, x));
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

    private static int darken(int argb, double factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static String shortName(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        String trimmed = name.trim();
        return trimmed.length() > 8 ? trimmed.substring(0, 8) + "…" : trimmed;
    }

    private static int badgeColor(ClientHand hand) {
        if (hand == null) {
            return FELT_DARK;
        }
        if ("BUST".equals(hand.state) || hand.value > 21) {
            return BUST_RED;
        }
        if ("BLACKJACK".equals(hand.state) || (hand.value == 21 && hand.cards.size() == 2)) {
            return GOLD;
        }
        return FELT;
    }

    private static String outcomeWord(ClientHand hand) {
        if (hand == null || hand.outcome == null) {
            return "";
        }
        return switch (hand.outcome) {
            case "WIN", "DEALER_BUST" -> "WIN";
            case "BLACKJACK_WIN" -> "BLACKJACK";
            case "LOSE" -> "LOSE";
            case "BUST" -> "BUST";
            case "PUSH" -> "PUSH";
            default -> "";
        };
    }

    private static int outcomeColor(ClientHand hand) {
        if (hand == null || hand.outcome == null) {
            return GREY;
        }
        return switch (hand.outcome) {
            case "WIN", "DEALER_BUST", "BLACKJACK_WIN" -> COLOR_TEXT_GREEN;
            case "LOSE", "BUST" -> COLOR_TEXT_RED;
            case "PUSH" -> COLOR_TEXT_WHITE;
            default -> GREY;
        };
    }
}
