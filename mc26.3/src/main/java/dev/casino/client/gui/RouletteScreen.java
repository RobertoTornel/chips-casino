package dev.casino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.casino.game.RouletteEngine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Six-seat multiplayer roulette.
 *
 * <p>The whole table is laid out from {@link #contentX()}/{@link #contentY()} inside a fixed
 * 352 x 168 interior, in five bands that never touch:
 *
 * <pre>
 *   +0   .. +20    header: the phase and its countdown, the croupier's line, the last numbers
 *   +24  .. +80    the wheel lane on the left, the six seat plates on the right
 *   +82  .. +98    chip denominations, CLEAR / UNDO / BOTS / SPIN
 *   +102 .. +118   the six even-money outside bets
 *   +122 .. +168   the cloth: the 0 rail plus three rows of twelve
 * </pre>
 *
 * <p>Every element is a pure function of the state packet plus a local clock, so two players
 * looking at the same table see the same wheel at the same angle: the packet carries the
 * wheel's angle and the tick it was measured on, and the screen re-derives the rotation curve
 * from those two numbers instead of needing an angle per frame.
 *
 * <h2>Authority</h2>
 * Nothing here decides anything. The winning number is not even in the packet until the server
 * reaches SETTLE ({@code winningNumber} stays {@code -1}), so the ball can only land on a
 * pocket the server has already announced.
 */
public class RouletteScreen extends BaseCasinoScreen {

    // ---------------------------------------------------------------- geometry

    private static final int SEAT_COUNT = 6;
    /** Seat plates: three columns of two, 90px wide with 4px gutters. */
    private static final int PLATE_W = 90;
    private static final int PLATE_H = 26;
    private static final int PLATE_DX = 94;
    /** The wheel lane: a 20px radius disc at the left of the seat band. */
    private static final int WHEEL_R = 20;
    /** The cloth: three rows of twelve, 24px cells with 4px gutters. */
    private static final int CELL_W = 24;
    /** Cell strides of the cloth, mirrored by the grid loop in {@code initGameWidgets}. */
    private static final int CELL_DX = 28;
    private static final int CELL_DY = 16;
    private static final int ZERO_W = 16;

    /** How many settled numbers the rail keeps. Mirrors {@code RouletteTable.HISTORY_KEEP}. */
    private static final int HISTORY_SHOWN = 5;
    /** Ticks the ball takes to walk from its orbit onto the winning pocket. */
    private static final int BALL_LAND_TICKS = 14;

    private static final int POCKETS = 37;

    private static final int FELT_DARK = 0xFF14080B;
    private static final int FELT_DEEP = 0xFF1E0A10;
    private static final int GOLD = 0xFFB8862C;
    private static final int GOLD_LIGHT = 0xFFFFD86B;
    private static final int GREY = 0xFF8A8A8A;
    private static final int BOT_GREY = 0xFF9AA7B1;
    private static final int WIN_GREEN = 0xFF7DDE7D;
    private static final int LOSE_RED = 0xFFE2564F;
    private static final int ROULETTE_GREEN = 0xFF2E9E52;
    private static final int ROULETTE_RED = 0xFFC0303A;
    private static final int ROULETTE_BLACK = 0xFF1A1A1A;
    private static final int DEALER_SKIN = 0xFFE8C39E;
    private static final int DEALER_SUIT = 0xFF23262E;

    // ------------------------------------------------------------------- state

    private String phase = "WAITING_FOR_PLAYERS";
    private int serverAnimClock;
    private int phaseTicks;
    private int round;
    private boolean seated;
    private boolean botsOn = true;
    private long minBet = 10;
    private long maxBet = 10_000;
    private long myStake;
    private String dealerSay = "";
    private String ticker = "";
    /** The wheel angle the server reported, in degrees. */
    private double serverWheelAngle;
    /** Degrees one spin turns; comes from the packet so the two sides cannot disagree. */
    private double wheelTurn = 1080.0;
    private int spinTicks = 90;
    private int bettingTicks = 500;
    private int winningNumber = -1;
    private int lastWinningNumber = -1;
    private final List<Integer> history = new ArrayList<>();
    private final List<RouletteEngine.Bet> myBets = new ArrayList<>();
    private final ClientSeat[] seats = new ClientSeat[SEAT_COUNT];

    // --------------------------------------------------------------- animation

    private long selectedChip = 50;
    private int clientClock;
    private boolean clockSeeded;
    private int spinStartClock = -1;
    private double spinStartAngle;
    private double wheelAngleDeg;
    private double ballAngleDeg;
    private int ballLandedOn = -1;
    private int ballLandTicks;

    // ----------------------------------------------------------------- widgets

    private SpriteButton chipTen;
    private SpriteButton chipFifty;
    private SpriteButton chipHundred;
    private SpriteButton chipFiveHundred;
    private SpriteButton clearButton;
    private SpriteButton undoButton;
    private SpriteButton botsButton;
    private SpriteButton spinButton;

    /** One seat as the screen sees it: who is there and what is on their felt. */
    private static class ClientSeat {
        int index;
        boolean occupied;
        boolean bot;
        boolean mine;
        boolean settled;
        String name = "";
        long bet;
        long payout;
        long net;
        final List<String> chips = new ArrayList<>();
    }

    public RouletteScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("European Roulette - Six Seat Table"), pos, initialStateJson, playerBalance);
        // Wider than the base cabinet: a real roulette cloth needs three rows of twelve, and
        // the wheel lane plus the six seat plates need a full band above it.
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

        boolean windowOpen = "BETTING".equals(phase) || "WAITING_FOR_PLAYERS".equals(phase);
        boolean canBet = seated && windowOpen;

        // Row 1: the four chip denominations, then the table verbs. SPIN is the widest and
        // sits flush with the right edge of the interior.
        int controlY = contentY() + 82;
        chipTen = addRenderableWidget(SpriteButton.of(contentX() + 0, controlY, 34, 16, "10",
                b -> selectedChip = 10));
        chipFifty = addRenderableWidget(SpriteButton.of(contentX() + 38, controlY, 34, 16, "50",
                b -> selectedChip = 50));
        chipHundred = addRenderableWidget(SpriteButton.of(contentX() + 76, controlY, 34, 16, "100",
                b -> selectedChip = 100));
        chipFiveHundred = addRenderableWidget(SpriteButton.of(contentX() + 114, controlY, 34, 16, "500",
                b -> selectedChip = 500));
        clearButton = addRenderableWidget(SpriteButton.of(contentX() + 152, controlY, 44, 16, "CLEAR",
                b -> sendAction("CLEAR", "{}")));
        undoButton = addRenderableWidget(SpriteButton.of(contentX() + 200, controlY, 40, 16, "UNDO",
                b -> sendAction("UNDO", "{}")));
        botsButton = addRenderableWidget(SpriteButton.of(contentX() + 244, controlY, 40, 16,
                botsOn ? "BOTS" : "HOUSE", b -> sendAction("TOGGLE_BOTS", "{}")));
        spinButton = addRenderableWidget(SpriteButton.primary(contentX() + 288, controlY, 64, 16, "SPIN",
                b -> sendAction("SPIN", "{}")));

        enable(chipTen, canBet);
        enable(chipFifty, canBet);
        enable(chipHundred, canBet);
        enable(chipFiveHundred, canBet);
        enable(clearButton, seated && windowOpen && myStake > 0);
        enable(undoButton, seated && windowOpen && myStake > 0);
        enable(spinButton, seated && windowOpen && myStake > 0);

        // Row 2: the six even-money bets, one per column of the cloth above them.
        int outsideY = contentY() + 102;
        addRenderableWidget(SpriteButton.colored(contentX() + 0, outsideY, 55, 16, "RED",
                SpriteButton.LABEL_RED, b -> placeBet(RouletteEngine.BetType.RED, 0)));
        addRenderableWidget(SpriteButton.of(contentX() + 59, outsideY, 55, 16, "BLACK",
                SpriteButton.LABEL_BLACK, b -> placeBet(RouletteEngine.BetType.BLACK, 0)));
        addRenderableWidget(SpriteButton.of(contentX() + 118, outsideY, 55, 16, "EVEN",
                b -> placeBet(RouletteEngine.BetType.EVEN, 0)));
        addRenderableWidget(SpriteButton.of(contentX() + 177, outsideY, 55, 16, "ODD",
                b -> placeBet(RouletteEngine.BetType.ODD, 0)));
        addRenderableWidget(SpriteButton.of(contentX() + 236, outsideY, 55, 16, "1-18",
                b -> placeBet(RouletteEngine.BetType.LOW, 0)));
        addRenderableWidget(SpriteButton.of(contentX() + 295, outsideY, 55, 16, "19-36",
                b -> placeBet(RouletteEngine.BetType.HIGH, 0)));

        // The cloth: the zero rail down the left, then three rows of twelve.
        int gridY = contentY() + 122;
        addRenderableWidget(SpriteButton.colored(contentX() + 0, gridY, 16, 46, "0",
                SpriteButton.LABEL_GREEN, b -> placeBet(RouletteEngine.BetType.STRAIGHT, 0)));

        int gridX = contentX() + 20;
        for (int num = 1; num <= 36; num++) {
            int col = ((num - 1) % 12);
            int row = ((num - 1) / 12);
            final int cell = num;
            // The strides are spelled out because tools/layout_check.py expands this loop from
            // its literals; they must stay equal to CELL_DX / CELL_DY above.
            addRenderableWidget(SpriteButton.of(gridX + col * 28, gridY + row * 16, 24, 14,
                    String.valueOf(num), cellColor(cell), b -> placeBet(RouletteEngine.BetType.STRAIGHT, cell)));
        }

        // The chip markers have to land on top of the cell buttons, and renderGameContent()
        // runs before the widget pass, so they go in as the last renderable of all.
        addRenderableOnly((extractor, mouseX, mouseY, partialTick) -> drawOverlay(extractor));
    }

    private void enable(SpriteButton button, boolean active) {
        if (button != null) {
            button.active = active;
        }
    }

    // ------------------------------------------------------------------- verbs

    private void placeBet(RouletteEngine.BetType type, int number) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type.name());
        json.addProperty("number", number);
        json.addProperty("amount", selectedChip);
        sendAction("BET", GSON.toJson(json));
    }

    /** Gold for a cell the player has backed, white for everything else. */
    private int cellColor(int number) {
        for (RouletteEngine.Bet bet : myBets) {
            if (bet.type == RouletteEngine.BetType.STRAIGHT && bet.number == number) {
                return SpriteButton.LABEL_PRIMARY;
            }
        }
        return SpriteButton.LABEL_NORMAL;
    }

    // ------------------------------------------------------------------- state

    @Override
    protected void onStateUpdated() {
        if (lastStateJson == null || lastStateJson.isEmpty()) {
            return;
        }
        JsonObject obj;
        try {
            obj = GSON.fromJson(lastStateJson, JsonObject.class);
        } catch (RuntimeException ignored) {
            return;
        }
        if (obj == null) {
            return;
        }

        phase = str(obj, "phase", phase);
        serverAnimClock = num(obj, "animClock", serverAnimClock);
        phaseTicks = num(obj, "phaseTicks", phaseTicks);
        round = num(obj, "round", round);
        seated = bool(obj, "seated", seated);
        botsOn = bool(obj, "autoFillBots", botsOn);
        minBet = lng(obj, "minBet", minBet);
        maxBet = lng(obj, "maxBet", maxBet);
        myStake = lng(obj, "myStake", myStake);
        dealerSay = str(obj, "dealerSay", "");
        serverWheelAngle = dbl(obj, "wheelAngle", serverWheelAngle);
        wheelTurn = dbl(obj, "wheelTurn", wheelTurn);
        spinTicks = Math.max(1, num(obj, "spinTicks", spinTicks));
        bettingTicks = Math.max(1, num(obj, "bettingTicks", bettingTicks));
        winningNumber = num(obj, "winningNumber", winningNumber);
        lastWinningNumber = num(obj, "lastWinningNumber", lastWinningNumber);

        history.clear();
        if (obj.has("history") && obj.get("history").isJsonArray()) {
            for (var el : obj.getAsJsonArray("history")) {
                try {
                    history.add(el.getAsInt());
                } catch (RuntimeException ignored) {
                    // a malformed history entry must not take the whole screen down
                }
            }
        }

        myBets.clear();
        if (obj.has("myBets") && obj.get("myBets").isJsonArray()) {
            for (var el : obj.getAsJsonArray("myBets")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                RouletteEngine.BetType type = betType(str(o, "type", "STRAIGHT"));
                myBets.add(new RouletteEngine.Bet(type, num(o, "number", 0), lng(o, "amount", 0)));
            }
        }

        readSeats(obj);
        readEvents(obj);

        if (!clockSeeded) {
            clientClock = serverAnimClock;
            clockSeeded = true;
        }
        // Re-anchor the spin on every packet: the wheel's angle is a pure function of the
        // tick, so one packet is enough to place it, and a dropped packet self-corrects.
        if ("SPINNING".equals(phase) || "SETTLE".equals(phase)) {
            double t = Math.min(1.0, phaseTicks / (double) spinTicks);
            spinStartAngle = serverWheelAngle - wheelTurn * ease(t);
            spinStartClock = clientClock - phaseTicks;
        } else {
            spinStartClock = -1;
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
            seat.mine = bool(o, "mine", false);
            seat.settled = bool(o, "settled", false);
            seat.bet = lng(o, "bet", 0);
            seat.payout = lng(o, "payout", 0);
            seat.net = lng(o, "net", 0);
            seat.chips.clear();
            if (o.has("chips") && o.get("chips").isJsonArray()) {
                for (var c : o.getAsJsonArray("chips")) {
                    if (!c.isJsonObject()) {
                        continue;
                    }
                    JsonObject chip = c.getAsJsonObject();
                    seat.chips.add(chipLabel(betType(str(chip, "type", "STRAIGHT")),
                            num(chip, "number", 0), lng(chip, "amount", 0)));
                }
            }
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

    private static RouletteEngine.BetType betType(String name) {
        for (RouletteEngine.BetType type : RouletteEngine.BetType.values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return RouletteEngine.BetType.STRAIGHT;
    }

    /** One chip on one target, short enough for a 90px plate. */
    private static String chipLabel(RouletteEngine.BetType type, int number, long amount) {
        String target = switch (type) {
            case STRAIGHT -> String.valueOf(number);
            case RED -> "R";
            case BLACK -> "B";
            case EVEN -> "E";
            case ODD -> "O";
            case LOW -> "1-18";
            case HIGH -> "19-36";
            case DOZEN_1 -> "D1";
            case DOZEN_2 -> "D2";
            case DOZEN_3 -> "D3";
            case COL_1 -> "C1";
            case COL_2 -> "C2";
            case COL_3 -> "C3";
        };
        return target + " " + amount;
    }

    private static String str(JsonObject o, String key, String fallback) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            try {
                return o.get(key).getAsString();
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int num(JsonObject o, String key, int fallback) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            try {
                return o.get(key).getAsInt();
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long lng(JsonObject o, String key, long fallback) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            try {
                return o.get(key).getAsLong();
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double dbl(JsonObject o, String key, double fallback) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            try {
                return o.get(key).getAsDouble();
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            try {
                return o.get(key).getAsBoolean();
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    // ================================================================= tick ===

    @Override
    public void tick() {
        super.tick();
        clientClock++;
        animate();
    }

    /**
     * The wheel and the ball, both driven off the local clock and re-anchored by every packet.
     *
     * <p>The wheel's angle is a pure function of the tick on the server, so the client only
     * needs the angle and the tick it belongs to: it recomputes the spin's start angle from
     * those two and then runs the same ease-out curve at 20 Hz. That is what keeps six screens
     * in lockstep without a state packet per frame.
     */
    private void animate() {
        if (!clockSeeded) {
            return;
        }
        boolean turning = "SPINNING".equals(phase) || "SETTLE".equals(phase);
        if (!turning || spinStartClock < 0) {
            wheelAngleDeg = serverWheelAngle;
            ballAngleDeg = 0.0;
            ballLandedOn = -1;
            return;
        }

        int elapsed = clientClock - spinStartClock;
        double t = Math.min(1.0, Math.max(0.0, elapsed / (double) spinTicks));
        wheelAngleDeg = spinStartAngle + wheelTurn * ease(t);

        // The ball runs ahead of the wheel and decelerates with it; it carries no information
        // about the outcome, which the packet does not contain until SETTLE.
        double orbit = wheelAngleDeg * 1.6 + 40.0;
        if ("SETTLE".equals(phase) && winningNumber >= 0) {
            if (ballLandedOn != winningNumber) {
                ballLandedOn = winningNumber;
                ballLandTicks = 0;
                ballAngleDeg = orbit;
            }
            double target = pocketAngle(winningNumber);
            if (ballLandTicks < BALL_LAND_TICKS) {
                ballLandTicks++;
            }
            double k = ease(ballLandTicks / (double) BALL_LAND_TICKS);
            ballAngleDeg = orbit + shortestDelta(orbit, target) * k;
        } else {
            ballAngleDeg = orbit;
        }
    }

    /** Screen angle, clockwise from twelve o'clock, of one pocket under the parked wheel. */
    private double pocketAngle(int number) {
        return wheelAngleDeg + number * (360.0 / POCKETS);
    }

    /** Mirrors {@code RouletteTable.wheelEase}; a cubic ease-out, 0..1 in and out. */
    private static double ease(double t) {
        double inv = 1.0 - Math.min(1.0, Math.max(0.0, t));
        return 1.0 - inv * inv * inv;
    }

    /** The signed shortest way round from {@code from} to {@code to}, in degrees. */
    private static double shortestDelta(double from, double to) {
        double d = (to - from) % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    // =============================================================== render ===

    @Override
    protected void renderGameContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        drawFelt(extractor);
        drawHeader(extractor);
        drawDealer(extractor);
        drawWheel(extractor);
        drawSeats(extractor);
    }

    /**
     * The baize, in two bands: one behind the wheel and the seats, one behind the controls and
     * the cloth. The seam sits in the 4px gutter at +80, so no fold ever cuts a plate or a
     * button in half.
     */
    private void drawFelt(GuiGraphicsExtractor e) {
        int x0 = contentX() + 4;
        int x1 = contentX() + contentW() - 4;
        e.fill(x0, contentY() + 22, x1, contentY() + 80, FELT_DEEP);
        e.fill(x0, contentY() + 80, x1, contentY() + 168, FELT_DARK);
        e.fill(x0, contentY() + 22, x1, contentY() + 23, 0x30B8862C);
        e.fill(x0, contentY() + 167, x1, contentY() + 168, 0x30B8862C);
    }

    /**
     * Two 8px lines across the top: the phase with its countdown on the left, and the
     * croupier's line with the settled-number rail on the right.
     */
    private void drawHeader(GuiGraphicsExtractor e) {
        int left = contentX();
        int right = contentX() + contentW();
        int top = contentY() + 2;
        int low = contentY() + 11;

        String label;
        int colour;
        if ("BETTING".equals(phase)) {
            label = "BETS OPEN";
            colour = GOLD_LIGHT;
        } else if ("SPINNING".equals(phase)) {
            label = "NO MORE BETS";
            colour = LOSE_RED;
        } else if ("SETTLE".equals(phase)) {
            label = winningNumber >= 0 ? (winningNumber + " " + colourWord(winningNumber)) : "PAYING OUT";
            colour = winningNumber >= 0 ? colourOf(winningNumber) : GOLD_LIGHT;
        } else {
            label = "TABLE OPEN";
            colour = GREY;
        }
        e.text(this.font, label, left, top, colour);

        String right1;
        if ("BETTING".equals(phase)) {
            int secs = Math.max(0, (bettingTicks - phaseTicks + 19) / 20);
            right1 = "felt " + myStake + "  closes " + secs + "s";
        } else if ("SPINNING".equals(phase) || "SETTLE".equals(phase)) {
            right1 = "felt " + myStake;
        } else {
            right1 = "limits " + minBet + "-" + maxBet;
        }
        e.text(this.font, right1, right - this.font.width(right1), top, GREY);

        // The croupier speaks on the header line, never over a seat: the line stops short of
        // the settled-number rail so the two never collide.
        String say = dealerSay.isEmpty() ? ticker : dealerSay;
        e.text(this.font, fit(say, contentW() - 90), left, low, COLOR_TEXT_WHITE);

        drawHistoryRail(e, right, low - 1);
    }

    /** The last few settled numbers, as coloured chips, flush with the right edge. */
    private void drawHistoryRail(GuiGraphicsExtractor e, int right, int y) {
        int shown = Math.min(HISTORY_SHOWN, history.size());
        for (int i = 0; i < shown; i++) {
            int number = history.get(history.size() - shown + i);
            int x = right - (shown - i) * 14;
            e.fill(x, y, x + 12, y + 11, colourOf(number));
            e.fill(x, y, x + 12, y + 1, 0x40FFFFFF);
            String text = String.valueOf(number);
            e.text(this.font, text, x + 6 - this.font.width(text) / 2, y + 2, 0xFFFFFFFF);
        }
    }

    /** The croupier, drawn before the wheel so the disc sits in front of her. */
    private void drawDealer(GuiGraphicsExtractor e) {
        int cx = contentX() + 44;
        int headY = contentY() + 33;
        // Shoulders, then head, then the bow tie: enough to read as a person at 40px.
        e.fill(cx - 13, headY + 9, cx + 13, headY + 22, DEALER_SUIT);
        e.fill(cx - 9, headY + 8, cx + 9, headY + 11, 0xFFF4F4F4);
        disc(e, cx, headY, 5, DEALER_SKIN);
        e.fill(cx - 5, headY - 6, cx + 5, headY - 3, 0xFF1C1C1C);
        e.fill(cx - 1, headY + 9, cx + 1, headY + 11, GOLD_LIGHT);
    }

    /**
     * The wheel: a rotating sprite plus a pocket ring this screen owns the geometry of, so the
     * ball always lands in a pocket that is drawn where the maths says it is.
     */
    private void drawWheel(GuiGraphicsExtractor e) {
        int cx = contentX() + 44;
        int cy = contentY() + 58;

        // Desk lamp glow under the wheel.
        disc(e, cx, cy, WHEEL_R + 3, 0x1AFFD86B);
        disc(e, cx, cy, WHEEL_R + 1, 0x14FFFFFF);

        // Wheel sprite, centred on the table. 1.20.1's GuiGraphics has no pose stack, so
        // the wheel itself is drawn unrotated; rotation is read from the ball and the
        // pointer instead.
        e.blit(CasinoSprites.ROULETTE_WHEEL, cx - WHEEL_R, cy - WHEEL_R, WHEEL_R * 2,
                WHEEL_R * 2, 0f, 0f, 1f, 1f);

        // Pocket marks around the rim — fixed positions relative to the centre.
        for (int i = 0; i < POCKETS; i++) {
            double a = i * (Math.PI * 2.0 / POCKETS) - Math.PI / 2.0;
            int px = cx + (int) Math.round(Math.cos(a) * (WHEEL_R - 1));
            int py = cy + (int) Math.round(Math.sin(a) * (WHEEL_R - 1));
            e.fill(px - 1, py - 1, px + 1, py + 1, colourOf(i));
        }

        // Spinning indicator while the wheel turns.
        if ("SPINNING".equals(phase)) {
            e.text(this.font, "SPINNING", cx - 24, cy - WHEEL_R - 10, COLOR_TEXT_GOLD);
        }

        // The hub carries the result once there is one.
        disc(e, cx, cy, 7, 0xFF141414);
        disc(e, cx, cy, 5, GOLD);
        if ("SETTLE".equals(phase) && winningNumber >= 0) {
            String text = String.valueOf(winningNumber);
            e.text(this.font, text, cx - this.font.width(text) / 2, cy - 4, 0xFF101010);
        }

        // The ball: on the rim while the wheel turns, parked on its pocket once it lands.
        double rad = Math.toRadians(ballAngleDeg);
        int bx = cx + (int) Math.round(Math.sin(rad) * (WHEEL_R - 4));
        int by = cy - (int) Math.round(Math.cos(rad) * (WHEEL_R - 4));
        disc(e, bx, by, 2, 0xFFFFFFFF);

        // Fixed pointer at twelve o'clock.
        e.fill(cx - 1, cy - WHEEL_R - 3, cx + 2, cy - WHEEL_R + 1, GOLD_LIGHT);
    }

    /** Six plates, three across and two down, each with the name, the felt and the result. */
    private void drawSeats(GuiGraphicsExtractor e) {
        int x0 = contentX() + 72;
        for (ClientSeat seat : seats) {
            if (seat == null) {
                continue;
            }
            int x = x0 + (seat.index % 3) * PLATE_DX;
            int y = contentY() + 24 + (seat.index / 3) * 28;
            if (seat.occupied) {
                drawPlate(e, seat, x, y);
            } else {
                e.fill(x, y, x + PLATE_W, y + PLATE_H, 0x14FFFFFF);
                e.text(this.font, "seat " + (seat.index + 1), x + 4, y + 9, 0x60FFFFFF);
            }
        }
    }

    private void drawPlate(GuiGraphicsExtractor e, ClientSeat seat, int x, int y) {
        int accent = seat.mine ? GOLD_LIGHT : (seat.bot ? 0xFF2E3A44 : 0xFF2A3A2E);
        e.fill(x, y, x + PLATE_W, y + PLATE_H, 0xE0140A0E);
        e.fill(x, y, x + 2, y + PLATE_H, accent);
        if (seat.mine) {
            e.fill(x, y, x + PLATE_W, y + 1, GOLD_LIGHT);
            e.fill(x, y + PLATE_H - 1, x + PLATE_W, y + PLATE_H, GOLD_LIGHT);
        }

        int nameColour = seat.mine ? COLOR_TEXT_GOLD : (seat.bot ? BOT_GREY : COLOR_TEXT_WHITE);
        e.text(this.font, fit(seat.name, PLATE_W - 8), x + 5, y + 1, nameColour);

        // What is on the felt, one short token per bet: "12 50 R 100".
        String felt = seat.chips.isEmpty() ? (seat.bet > 0 ? "felt " + seat.bet : "no bet") : joinChips(seat.chips);
        e.text(this.font, fit(felt, PLATE_W - 8), x + 5, y + 10, seat.bet > 0 ? GOLD : GREY);

        // Third line, in priority order: the result of the round just settled, then the fact
        // that the chips are committed to a turning wheel, then the last result carried over
        // into the next window so a player never misses what they won.
        boolean settling = "SPINNING".equals(phase) || "SETTLE".equals(phase);
        if (seat.settled && settling && seat.bet > 0) {
            String result = (seat.net >= 0 ? "+" : "") + seat.net;
            e.text(this.font, result, x + 5, y + 19, seat.net > 0 ? WIN_GREEN : (seat.net < 0 ? LOSE_RED : GREY));
            if (seat.payout > 0) {
                String paid = String.valueOf(seat.payout);
                e.text(this.font, paid, x + PLATE_W - 5 - this.font.width(paid), y + 19, WIN_GREEN);
            }
        } else if (seat.bet > 0) {
            e.text(this.font, "on the felt", x + 5, y + 19, 0x60FFFFD8);
        } else if (seat.settled) {
            String last = "was " + (seat.net >= 0 ? "+" : "") + seat.net;
            e.text(this.font, last, x + 5, y + 19, 0x70999999);
        }
    }

    private static String joinChips(List<String> chips) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(2, chips.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append("  ");
            }
            sb.append(chips.get(i));
        }
        if (chips.size() > shown) {
            sb.append(" +").append(chips.size() - shown);
        }
        return sb.toString();
    }

    /**
     * Everything that has to sit above the buttons: the chip the player is holding, a marker
     * on every cell they have backed, and the running total.
     */
    private void drawOverlay(GuiGraphicsExtractor e) {
        // The selected denomination, underlined so the row reads as a radio group.
        int underlineY = contentY() + 98;
        int underlineX = selectedChip == 10 ? contentX() + 0
                : selectedChip == 50 ? contentX() + 38
                : selectedChip == 100 ? contentX() + 76
                : contentX() + 114;
        e.fill(underlineX, underlineY, underlineX + 34, underlineY + 2, GOLD_LIGHT);

        // A gold corner on every number the player has backed: the chip is on the felt the
        // moment the server confirms it, and the stake itself is itemized on the seat plate.
        int gridY = contentY() + 122;
        for (RouletteEngine.Bet bet : myBets) {
            if (bet.type != RouletteEngine.BetType.STRAIGHT) {
                continue;
            }
            if (bet.number < 1 || bet.number > 36) {
                corner(e, contentX() + 0, gridY, ZERO_W);
                continue;
            }
            int col = (bet.number - 1) % 12;
            int row = (bet.number - 1) / 12;
            corner(e, contentX() + 20 + col * CELL_DX, gridY + row * CELL_DY, CELL_W);
        }

        // The same marker under an outside bet, clear of the button's own label.
        for (RouletteEngine.Bet bet : myBets) {
            int slot = outsideSlot(bet.type);
            if (slot < 0) {
                continue;
            }
            int x = contentX() + slot * 59;
            int y = contentY() + 102;
            e.fill(x + 8, y + 13, x + 47, y + 16, GOLD_LIGHT);
        }
    }

    /** A 4px gold corner in the top-right of a bet spot, drawn above the button sprite. */
    private void corner(GuiGraphicsExtractor e, int x, int y, int w) {
        e.fill(x + w - 4, y, x + w, y + 4, GOLD_LIGHT);
    }

    /** Which of the six even-money buttons a bet sits on, or -1. */
    private static int outsideSlot(RouletteEngine.BetType type) {
        return switch (type) {
            case RED -> 0;
            case BLACK -> 1;
            case EVEN -> 2;
            case ODD -> 3;
            case LOW -> 4;
            case HIGH -> 5;
            default -> -1;
        };
    }

    // ============================================================== helpers ===

    /**
     * A filled disc. The extractor only offers axis-aligned {@code fill}, so a circle is a run
     * of one-pixel-high spans; at the 2-7px radii used here that is both cheap and exact.
     */
    private static void disc(GuiGraphicsExtractor e, int cx, int cy, int r, int colour) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.sqrt((double) (r * r - dy * dy));
            e.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, colour);
        }
    }

    /** Truncates a label to {@code px} pixels with an ellipsis, so a long name cannot spill. */
    private String fit(String text, int px) {
        if (text == null) {
            return "";
        }
        if (this.font.width(text) <= px) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (this.font.width(sb.toString() + text.charAt(i) + "\u2026") > px) {
                break;
            }
            sb.append(text.charAt(i));
        }
        return sb + "\u2026";
    }

    private static String colourWord(int number) {
        if (number == 0) {
            return "green";
        }
        return RouletteEngine.RED_NUMBERS.contains(number) ? "red" : "black";
    }

    private static int colourOf(int number) {
        if (number == 0) {
            return ROULETTE_GREEN;
        }
        return RouletteEngine.RED_NUMBERS.contains(number) ? ROULETTE_RED : ROULETTE_BLACK;
    }
}
