package dev.casino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Six-seat multiplayer baccarat table.
 *
 * <p>The tableau is the same table as {@link BlackjackScreen}, seen from the same side of the
 * shoe: the dealer's tray sits at the top right, the two hands are fanned down the middle of
 * the felt, and the six bettors share one row of plates across it under them. The croupier has
 * no speech bubble of her own: what she says sits on the header line, clear of the seats.
 * Every element is placed from {@link #contentX()}/{@link #contentY()}, so the whole table
 * travels with the cabinet inside a fixed {@code 352 x 168} interior.
 *
 * <h2>Vertical budget (interior 352 x 168)</h2>
 * <pre>
 *   +0   .. +14   header: totals, stake, round, the croupier's line, the ticker
 *   +18  .. +46   banker fan
 *   +50  .. +78   player fan
 *   +82  .. +106  six seat plates (24px each)
 *   +110 .. +126  stake buttons
 *   +132 .. +148  line / verb buttons
 * </pre>
 *
 * <h2>Animation</h2>
 * Every card carries the server tick it landed on ({@code t}), so the client can slide each one
 * out of the shoe and fade it in with no per-card packets. A local {@link #clientClock} runs at
 * 20 Hz and is seeded from the server's {@code animClock} the first time state arrives; the
 * render clock is {@code max(clientClock, serverAnimClock)} so a client that fell behind
 * (a lag spike, a resize) never shows a stale or negative card age.
 *
 * <p>Unlike blackjack there are no decisions after the deal — a bettor picks PLAYER, BANKER or
 * TIE and watches — so the only controls are the line, the stake, and the house's own buttons.
 */
public class BaccaratScreen extends BaseCasinoScreen {

    // ---------------------------------------------------------------- geometry
    private static final int SEAT_COUNT = 6;
    /** The shoe is a single 52-card deck; the gauge is drawn against this. */
    private static final int SHOE_SIZE = 52;
    private static final int CARD_W = 20;
    private static final int CARD_H = 28;
    /** Horizontal offset between two fanned cards of one hand. */
    private static final int FAN_STEP = 14;
    private static final int SLIDE_TICKS = 10;
    /** Mirrors {@code BaccaratTable.BETTING_TICKS}: the door shuts 20 s after it opens. */
    private static final int BET_WINDOW_TICKS = 400;

    // Seat arc: six plates spread across the felt, 200 deg .. 340 deg in steps. A plate is 24px
    // tall and the seat band is exactly 24px, so the arc keeps only its horizontal spread: the
    // row shares one baseline and every block sits inside +82 .. +106.
    private static final int ARC_CY = 98;
    private static final int ARC_RY = 8;
    private static final int ARC_RX = 140;
    private static final int ARC_FIRST_DEG = 200;
    private static final int ARC_STEP_DEG = 28;

    private static final int FELT_DARK = 0xFF0A1A30;
    private static final int GOLD = 0xFFB8862C;
    private static final int GOLD_LIGHT = 0xFFFFD86B;
    private static final int GREY = 0xFF8A8A8A;
    private static final int BOT_GREY = 0xFF9AA7B1;
    private static final int WIN_GREEN = 0xFF7DDE7D;
    private static final int LOSE_RED = 0xFFE2564F;
    private static final int PLAYER_BLUE = 0xFF6FA8FF;
    private static final int BANKER_CORAL = 0xFFFF9A8A;
    private static final int TIE_MINT = 0xFF9BE8A6;

    // ------------------------------------------------------------------- model
    private static final class ClientCard {
        String c = "??";
        int t;
    }

    private static final class ClientSeat {
        int index;
        boolean occupied;
        String name = "";
        boolean bot;
        long bet;
        long net;
        String target = "PLAYER";
        String outcome = "NONE";
        boolean mine;

        boolean settled() {
            return outcome != null && !"NONE".equals(outcome);
        }
    }

    // ------------------------------------------------------------------- state
    private String phase = "WAITING_FOR_PLAYERS";
    private int serverAnimClock;
    private int phaseTicks;
    private int round;
    private String dealerSay = "";
    private String outcome = "NONE";
    private String ticker = "";
    private int playerTotal;
    private int bankerTotal;
    private boolean seated;
    private boolean botsOn = true;
    private boolean owner;
    private long minBet = 10;
    private long maxBet = 10_000;
    private int shoe = 52;
    private final List<Integer> history = new ArrayList<>();
    private final List<ClientCard> playerHand = new ArrayList<>();
    private final List<ClientCard> bankerHand = new ArrayList<>();
    private final ClientSeat[] seats = new ClientSeat[SEAT_COUNT];

    // ------------------------------------------------------------------- local
    /** The line this client is backing: "PLAYER", "BANKER" or "TIE". */
    private String line = "PLAYER";
    private long stake = 100;
    private int clientClock;
    private boolean clockSeeded;

    // ----------------------------------------------------------------- widgets
    private SpriteButton stakeTen;
    private SpriteButton stakeFifty;
    private SpriteButton stakeHundred;
    private SpriteButton stakeFiveHundred;
    private SpriteButton linePlayer;
    private SpriteButton lineBanker;
    private SpriteButton lineTie;
    private SpriteButton dealButton;
    private SpriteButton botsButton;
    private SpriteButton sitOutButton;

    public BaccaratScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Baccarat - Six Seat Punto Banco"), pos, initialStateJson, playerBalance);
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
        // The widgets read the enablement flags, so the state must be parsed before they are
        // built. updateState() reparses first and then rebuilds, so this is idempotent.
        onStateUpdated();

        // Row 1: the four chip denominations. The line itself lives on row 2, so a stake is
        // always sent against the line button the player pressed last.
        int row1Y = contentY() + 126;
        stakeTen = addRenderableWidget(SpriteButton.of(contentX() + 204, row1Y, 28, 16, "10",
                b -> placeBet(10)));
        stakeFifty = addRenderableWidget(SpriteButton.of(contentX() + 236, row1Y, 28, 16, "50",
                b -> placeBet(50)));
        stakeHundred = addRenderableWidget(SpriteButton.of(contentX() + 268, row1Y, 28, 16, "100",
                b -> placeBet(100)));
        stakeFiveHundred = addRenderableWidget(SpriteButton.of(contentX() + 300, row1Y, 28, 16, "500",
                b -> placeBet(500)));

        // Row 2: the three lines, then the table verbs.
        int row2Y = contentY() + 146;
        linePlayer = addRenderableWidget(SpriteButton.colored(contentX() + 4, row2Y, 60, 16, "PLAYER",
                SpriteButton.LABEL_NORMAL, b -> selectLine("PLAYER")));
        lineBanker = addRenderableWidget(SpriteButton.colored(contentX() + 68, row2Y, 60, 16, "BANKER",
                SpriteButton.LABEL_RED, b -> selectLine("BANKER")));
        lineTie = addRenderableWidget(SpriteButton.colored(contentX() + 132, row2Y, 44, 16, "TIE",
                SpriteButton.LABEL_GREEN, b -> selectLine("TIE")));
        dealButton = addRenderableWidget(SpriteButton.primary(contentX() + 180, row2Y, 52, 16, "DEAL",
                b -> sendAction("DEAL", "{}")));
        botsButton = addRenderableWidget(SpriteButton.colored(contentX() + 236, row2Y, 44, 16, "BOTS",
                botsOn ? SpriteButton.LABEL_GREEN : SpriteButton.LABEL_BLACK,
                b -> sendAction("TOGGLE_BOTS", "{}")));
        sitOutButton = addRenderableWidget(SpriteButton.colored(contentX() + 284, row2Y, 56, 16, "SIT OUT",
                SpriteButton.LABEL_RED, b -> sendAction("LEAVE", "{}")));

        applyEnablement();
    }

    /** A stake button is also the send: the bet goes out against the selected line at once. */
    private void placeBet(long amount) {
        this.stake = amount;
        JsonObject json = new JsonObject();
        json.addProperty("bet", amount);
        json.addProperty("target", this.line);
        sendAction("BET", GSON.toJson(json));
    }

    private void selectLine(String target) {
        this.line = target;
    }

    private void enable(SpriteButton button, boolean active) {
        if (button != null) {
            button.active = active;
        }
    }

    /**
     * Every control mirrors what the table would accept right now: the lines and stakes only in
     * a betting window while seated, the shoe only for a seated bettor, the bots only for the
     * owner, and sitting out only once you are in a seat.
     */
    private void applyEnablement() {
        boolean betting = seated && ("BETTING".equals(phase) || "WAITING_FOR_PLAYERS".equals(phase));

        enable(linePlayer, betting);
        enable(lineBanker, betting);
        enable(lineTie, betting);
        enable(stakeTen, betting && 10 >= minBet && 10 <= maxBet);
        enable(stakeFifty, betting && 50 >= minBet && 50 <= maxBet);
        enable(stakeHundred, betting && 100 >= minBet && 100 <= maxBet);
        enable(stakeFiveHundred, betting && 500 >= minBet && 500 <= maxBet);
        enable(dealButton, betting);
        enable(botsButton, owner);
        enable(sitOutButton, seated);
    }

    // ================================================================ state ===

    @Override
    protected void onStateUpdated() {
        if (lastStateJson == null || lastStateJson.isEmpty()) {
            return;
        }
        try {
            parseState(GSON.fromJson(lastStateJson, JsonObject.class));
        } catch (RuntimeException ignored) {
            // A truncated or half-written payload leaves the previous tableau on screen; it is
            // strictly better than a screen that throws in the middle of a deal.
        }
    }

    private void parseState(JsonObject obj) {
        if (obj == null) {
            return;
        }
        phase = str(obj, "phase", phase);
        serverAnimClock = num(obj, "animClock", serverAnimClock);
        phaseTicks = num(obj, "phaseTicks", phaseTicks);
        dealerSay = str(obj, "dealerSay", "");
        round = num(obj, "round", round);
        seated = bool(obj, "seated", seated);
        botsOn = bool(obj, "autoFillBots", botsOn);
        owner = bool(obj, "isOwner", owner);
        minBet = lng(obj, "minBet", minBet);
        maxBet = lng(obj, "maxBet", maxBet);
        shoe = num(obj, "shoe", shoe);
        playerTotal = num(obj, "playerTotal", playerTotal);
        bankerTotal = num(obj, "bankerTotal", bankerTotal);
        outcome = str(obj, "outcome", outcome);

        readCards(obj, "playerHand", playerHand);
        readCards(obj, "bankerHand", bankerHand);
        readSeats(obj);
        readEvents(obj);

        if (!clockSeeded) {
            clientClock = serverAnimClock;
            clockSeeded = true;
        }
    }

    private static void readCards(JsonObject obj, String key, List<ClientCard> into) {
        into.clear();
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return;
        }
        for (var el : obj.getAsJsonArray(key)) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            ClientCard card = new ClientCard();
            card.c = str(o, "c", "??");
            card.t = num(o, "t", 0);
            into.add(card);
        }
    }

    private void readSeats(JsonObject obj) {
        if (!obj.has("seats") || !obj.get("seats").isJsonArray()) {
            return;
        }
        for (var el : obj.getAsJsonArray("seats")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            int index = num(o, "index", -1);
            if (index < 0 || index >= SEAT_COUNT) {
                continue;
            }
            ClientSeat seat = seats[index];
            seat.index = index;
            seat.occupied = bool(o, "occupied", false);
            seat.name = str(o, "name", "");
            seat.bot = bool(o, "bot", false);
            seat.bet = lng(o, "bet", 0);
            seat.net = lng(o, "net", 0);
            seat.target = str(o, "target", "PLAYER");
            seat.outcome = str(o, "outcome", "NONE");
            seat.mine = bool(o, "mine", false);
        }
    }

    /** The ticker is the newest table event; older ones are already baked into the tableau. */
    private void readEvents(JsonObject obj) {
        if (!obj.has("events") || !obj.get("events").isJsonArray()) {
            return;
        }
        JsonArray events = obj.getAsJsonArray("events");
        if (events.size() == 0) {
            return;
        }
        var last = events.get(events.size() - 1);
        if (last.isJsonObject()) {
            ticker = str(last.getAsJsonObject(), "x", ticker);
        }
    }

    // ================================================================= tick ===

    @Override
    public void tick() {
        super.tick();
        clientClock++;
    }

    // =============================================================== render ===

    @Override
    protected void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        int clock = Math.max(clientClock, serverAnimClock);
        drawFelt(extractor);
        drawTopStrip(extractor);
        drawHand(extractor, bankerHand, contentY() + 18, "BANKER", bankerTotal, BANKER_CORAL, clock);
        drawHand(extractor, playerHand, contentY() + 50, "PLAYER", playerTotal, PLAYER_BLUE, clock);
        drawSeats(extractor);
    }

    /**
     * The navy baize, in two bands: one behind the two fans, one behind the seat plates
     * and the buttons. The seam sits at +82, the top of the seat band, so no fold ever cuts
     * through a plate.
     */
    private void drawFelt(GuiGraphicsExtractor e) {
        int x0 = contentX() + 6;
        int x1 = contentX() + contentW() - 6;
        e.fill(x0, contentY() + 17, x1, contentY() + 82, 0x24102440);
        e.fill(x0, contentY() + 82, x1, contentY() + 148, 0x18102440);
        e.fill(x0, contentY() + 17, x1, contentY() + 18, 0x30B8862C);
    }

    /**
     * The header, two 8px lines in {@code +0 .. +16}: the totals in the middle, the local stake
     * at the left, and at the right the round, the closing countdown or the winner, and the
     * croupier's line — which used to be a bubble drawn straight across the seats.
     */
    private void drawTopStrip(GuiGraphicsExtractor e) {
        int cx = contentX() + contentW() / 2;
        // The shoe owns the last 22px of the interior, so the header stops 4px short of it.
        int right = contentX() + contentW() - 34;
        int top = contentY() + 2;
        int low = contentY() + 10;

        String totals = "P " + playerTotal + " \u00b7 B " + bankerTotal;
        e.centeredText(this.font, totals, cx, top, GOLD_LIGHT);

        String stakeLine = "STAKE " + stake + " \u00b7 " + line;
        e.text(this.font, stakeLine, contentX() + 2, top, GOLD);

        String roundTag = "R" + round;
        int tagX = right - this.font.width(roundTag);
        e.text(this.font, roundTag, tagX, top, GOLD_LIGHT);

        // The countdown while the door is open, the winner once it shuts: the slot the old strip
        // gave the same two strings, now hard against the round tag.
        String status = "";
        int statusColor = GREY;
        if ("BETTING".equals(phase)) {
            int left = Math.max(0, BET_WINDOW_TICKS - phaseTicks);
            status = "closes " + ((left + 19) / 20) + "s";
        } else if ("SETTLE".equals(phase)) {
            status = winnerWord();
            statusColor = winnerColor();
        }
        if (!status.isEmpty()) {
            e.text(this.font, status, tagX - 6 - this.font.width(status), top, statusColor);
        }

        // The croupier's line, right-aligned and fitted so a long sentence cannot reach the shoe.
        String say = dealerSay == null ? "" : dealerSay.trim();
        boolean silent = say.isEmpty();
        if (silent) {
            say = "Place your bets - player, banker or tie.";
        }
        String spoken = fit(say, 190);
        e.text(this.font, spoken, right - this.font.width(spoken), low,
                silent ? GREY : COLOR_TEXT_WHITE);

        // The newest table event, in the corner the totals do not reach.
        if (ticker != null && !ticker.isEmpty()) {
            e.text(this.font, fit(ticker, 100), contentX() + 2, low, GREY);
        }

        // The shoe: card backs plus a gauge that empties as the shoe empties.
        drawShoe(e, shoeX() - 1, contentY() + 4, 22, 30);
    }

    /** The last few settled numbers, as coloured chips, flush with the right edge. */
    private void drawHistoryRail(GuiGraphicsExtractor e, int right, int y) {
        int shown = Math.min(5, history.size());
        for (int i = 0; i < shown; i++) {
            int number = history.get(history.size() - shown + i);
            int x = right - (shown - i) * 14;
            e.fill(x, y, x + 12, y + 11, colourOf(number));
            e.fill(x, y, x + 12, y + 1, 0x40FFFFFF);
            String text = String.valueOf(number);
            e.text(this.font, text, x + 6 - this.font.width(text) / 2, y + 2, 0xFFFFFFFF);
        }
    }

    /** One hand: a fan of cards stepping right from the centre, its name to the left of the fan
     *  and its total in a gold badge to the right — the label sits outside the fan so the cards
     *  never cover it. */
    private void drawHand(GuiGraphicsExtractor e, List<ClientCard> cards, int fanY, String label,
                          int total, int accent, int clock) {
        int cx = contentX() + contentW() / 2;
        int count = cards.size();
        int fanW = count <= 0 ? CARD_W : CARD_W + (count - 1) * FAN_STEP;
        int fanX = cx - fanW / 2;

        // A shallow felt shadow anchors the fan to the table.
        e.fill(fanX - 6, fanY + CARD_H - 2, fanX + fanW + 6, fanY + CARD_H + 2, 0x30000000);

        if (count == 0) {
            e.fill(fanX, fanY, fanX + CARD_W, fanY + CARD_H, 0x18FFFFFF);
            e.fill(fanX, fanY, fanX + CARD_W, fanY + 1, accent);
        }
        for (int i = 0; i < count; i++) {
            drawArrivingCard(e, cards.get(i), fanX + i * FAN_STEP, fanY, clock);
        }

        int lx = Math.max(contentX() + 2, fanX - 8 - this.font.width(label));
        e.text(this.font, label, lx, fanY + 10, accent);

        int badgeX = Math.min(fanX + fanW + 8, contentX() + contentW() - 34);
        drawBadge(e, badgeX, fanY + 7, 30, 14, String.valueOf(total), accent);
    }

    /**
     * Draws one card sliding out of the shoe into its slot, with a dark veil that lifts as it
     * settles: the sprites cannot be drawn with an alpha tint, so the fade is faked by covering
     * the destination and shrinking the cover.
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

    // ---------------------------------------------------------------- seats ---

    private void drawSeats(GuiGraphicsExtractor e) {
        for (ClientSeat seat : seats) {
            if (seat == null) {
                continue;
            }
            int[] p = seatPoint(seat.index);
            if (seat.occupied) {
                drawSeat(e, seat, p[0], p[1]);
            } else {
                drawEmptySeat(e, seat.index, p[0], p[1]);
            }
        }
    }

    /**
     * Seat {@code index} on the arc under the shoe: 200 deg .. 340 deg in six steps, then
     * clamped into the interior so the outer seats never touch the frame.
     *
     * <p>The arc is horizontal only. A plate is 24px tall and the seat band is exactly 24px
     * ({@code +82 .. +106}), so there is a single baseline that keeps every block inside it.
     */
    private int[] seatPoint(int index) {
        int cx = contentX() + contentW() / 2;
        double theta = Math.toRadians(ARC_FIRST_DEG + index * ARC_STEP_DEG);
        int x = cx + (int) Math.round(ARC_RX * Math.cos(theta));
        x = Math.max(contentX() + 30, Math.min(contentX() + contentW() - 70, x));
        int y = contentY() + ARC_CY + (int) Math.round(ARC_RY * Math.sin(theta));
        // Keep every 24px plate inside the seat band (+82..+114).
        y = Math.max(contentY() + 98, Math.min(contentY() + 114, y));
        return new int[]{x, y};
    }

    /**
     * One seat plate, 24px tall ({@code sy-16 .. sy+8}): the name with the line it backs as a
     * coloured dot, then a single line carrying the chip, the stake and — once the hand is
     * settled — the result word and the signed net.
     */
    private void drawSeat(GuiGraphicsExtractor e, ClientSeat seat, int sx, int sy) {
        int nameColor = seat.mine ? COLOR_TEXT_GOLD : (seat.bot ? BOT_GREY : COLOR_TEXT_WHITE);
        String name = shortName(seat.name);
        e.centeredText(this.font, name, sx, sy - 9, nameColor);
        // The backed line used to be a row of its own; a coloured dot beside the name costs three pixels.
        int dotX = sx + this.font.width(name) / 2 + 3;
        e.fill(dotX, sy - 7, dotX + 3, sy - 4, lineColor(seat.target));

        String stakeText = String.valueOf(seat.bet);
        String result = resultWord(seat);
        String net = result.isEmpty() || seat.net == 0 ? "" : (seat.net > 0 ? "+" : "") + seat.net;

        // The chip, the stake, the verdict and the net as one centred run, kept inside the
        // interior however wide the numbers get.
        int w = 16 + 3 + this.font.width(stakeText);
        if (!result.isEmpty()) {
            w += 5 + this.font.width(result);
        }
        if (!net.isEmpty()) {
            w += 2 + this.font.width(net);
        }
        int x = Math.max(contentX(), Math.min(contentX() + contentW() - w, sx - w / 2));

        drawChip(e, x, sy - 8);
        int tx = x + 19;
        e.text(this.font, stakeText, tx, sy, seat.bet > 0 ? GOLD_LIGHT : GREY);
        tx += this.font.width(stakeText) + 5;
        if (!result.isEmpty()) {
            e.text(this.font, result, tx, sy, resultColor(seat.net));
            tx += this.font.width(result) + 5;
        }
        if (!net.isEmpty()) {
            e.text(this.font, net, tx, sy, seat.net > 0 ? WIN_GREEN : LOSE_RED);
        }
    }

    private void drawEmptySeat(GuiGraphicsExtractor e, int index, int sx, int sy) {
        int x0 = sx - 20;
        int y0 = sy - 16;
        int x1 = sx + 20;
        int y1 = sy + 8;
        // Dashed outline: 3px dashes with 3px gaps.
        for (int x = x0; x < x1; x += 6) {
            e.fill(x, y0, Math.min(x + 3, x1), y0 + 1, 0x40FFFFFF);
            e.fill(x, y1 - 1, Math.min(x + 3, x1), y1, 0x40FFFFFF);
        }
        for (int y = y0; y < y1; y += 6) {
            e.fill(x0, y, x0 + 1, Math.min(y + 3, y1), 0x40FFFFFF);
            e.fill(x1 - 1, y, x1, Math.min(y + 3, y1), 0x40FFFFFF);
        }
        e.centeredText(this.font, "seat " + (index + 1), sx, sy - 8, 0x60FFFFFF);
    }

    private static String resultWord(ClientSeat seat) {
        if (!seat.settled()) {
            return "";
        }
        if (seat.net > 0) {
            return "WIN";
        }
        if (seat.net < 0) {
            return "LOSE";
        }
        return "PUSH";
    }

    private static int resultColor(long net) {
        if (net > 0) {
            return WIN_GREEN;
        }
        if (net < 0) {
            return LOSE_RED;
        }
        return GREY;
    }

    private static int lineColor(String target) {
        if ("BANKER".equals(target)) {
            return BANKER_CORAL;
        }
        if ("TIE".equals(target)) {
            return TIE_MINT;
        }
        return PLAYER_BLUE;
    }

    // --------------------------------------------------------------- chrome ---

    private void drawBadge(GuiGraphicsExtractor e, int x, int y, int w, int h, String text, int accent) {
        e.fill(x, y, x + w, y + h, FELT_DARK);
        e.fill(x, y, x + w, y + 1, accent);
        e.fill(x, y + h - 1, x + w, y + h, accent);
        e.fill(x, y, x + 1, y + h, accent);
        e.fill(x + w - 1, y, x + w, y + h, accent);
        e.centeredText(this.font, text, x + w / 2, y + (h - 8) / 2, GOLD_LIGHT);
    }

    // ---------------------------------------------------------------- geometry ---

    private int shoeX() {
        return contentX() + contentW() - 29;
    }

    private int shoeY() {
        return contentY() + 5;
    }

    // -------------------------------------------------------------- helpers ---

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

    private static String shortName(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        String trimmed = name.trim();
        return trimmed.length() > 8 ? trimmed.substring(0, 8) + "\u2026" : trimmed;
    }

    private String winnerWord() {
        return switch (outcome) {
            case "PLAYER" -> "PLAYER WINS";
            case "BANKER" -> "BANKER WINS";
            case "TIE" -> "TIE";
            default -> "SETTLED";
        };
    }

    private int winnerColor() {
        return switch (outcome) {
            case "PLAYER" -> PLAYER_BLUE;
            case "BANKER" -> BANKER_CORAL;
            case "TIE" -> TIE_MINT;
            default -> GREY;
        };
    }

    private void drawShoe(GuiGraphicsExtractor e, int x, int y, int w, int h) {
        e.fill(x, y, x + w, y + h, 0xFF140F0A);
        e.fill(x, y, x + w, y + 1, GOLD);
        e.fill(x, y + h - 1, x + w, y + h, 0x60FFFFD8);
        e.fill(x, y, x + 1, y + h, GOLD);
        e.fill(x + w - 1, y, x + w, y + h, GOLD);

        int depth = Math.max(0, Math.min(SHOE_SIZE, shoe));
        int filled = (int) Math.round(h * depth / (double) SHOE_SIZE);
        if (filled > 0) {
            e.fill(x + 2, y + h - filled, x + w - 2, y + h - 2, GOLD_LIGHT);
        }
    }

    private static int colourOf(int number) {
        if (number == 0) return 0xFF00FF00;
        return (number % 2 == 0) ? 0xFFFF4444 : 0xFFFFFFFF;
    }
}
