package dev.casino.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Multiplayer roulette table: six bettors on a semicircle around one wheel.
 *
 * <p>Pure Java, no Minecraft types, so the whole tableau — the layout of chips on the felt,
 * the winning number, the payout table and the wheel's animation clock — is unit-testable on
 * a bare JVM.
 *
 * <h2>Authority</h2>
 * The server owns the spin. {@link #startSpin()} draws the number from a
 * {@link SecureRandom} mixed with a per-spin nonce, exactly the way
 * {@code SlotMachineBlockEntity} owns its seed: nothing the client sends can reach the draw.
 * Until the wheel has finished ({@link Phase#SETTLE}) the number is not even serialized —
 * {@link #getWinningNumber()} answers {@code -1} and {@link #toJson(UUID)} writes that,
 * so a modified client cannot read the result out of the packet stream either.
 *
 * <h2>Animation clock</h2>
 * {@link #tickServer()} runs 20 times a second. Unlike baccarat there is no deal to pace;
 * the beats are the betting window ({@value #BETTING_TICKS} ticks), the wheel
 * ({@value #SPIN_TICKS} ticks of deceleration) and the hold ({@value #SETTLE_HOLD} ticks).
 * The wheel's angle is a pure function of {@link #animClock}, so every viewer computes the
 * same rotation from the same two numbers ({@code wheelAngle} and {@code phaseTicks}) instead
 * of receiving an angle 20 times a second.
 *
 * <h2>Money</h2>
 * This class never touches a balance. It reports what a seat has on the felt
 * ({@link #stakeOn}, {@link #clearBets}, {@link #undoLastBet}) and queues what the house owes
 * ({@link #drainPayouts}); the block entity moves the chips. Bets are refundable while the
 * window is open and committed once the wheel is turning — refunding a spinning layout would
 * let a player cover all 37 numbers and walk away even after the result was visible.
 */
public class RouletteTable {
    public static final int MAX_SEATS = 6;

    /** The betting window before the croupier calls no more bets. */
    public static final int BETTING_TICKS = 500;
    /** The wheel decelerates over this many ticks (5 s at 20 tps). */
    public static final int SPIN_TICKS = 100;
    /** How long the number stays up before the felt is cleared for the next window. */
    public static final int SETTLE_HOLD = 80;
    /** Ticks between two chips from the same house player. */
    public static final int BOT_THINK = 18;
    /** One seat cannot paint more targets than this on a single layout. */
    public static final int MAX_BETS_PER_SEAT = 24;

    public static final long MIN_BET = 10;
    public static final long MAX_BET = 10_000;

    /** Degrees the wheel turns in one spin: three full revolutions, then it creeps to rest. */
    public static final double WHEEL_TURN_DEGREES = 1080.0;

    /** European single-zero wheel. */
    public static final int POCKETS = 37;

    /** Numbers kept for the rail under the header. */
    private static final int HISTORY_KEEP = 8;

    /** Bot ids live in their own UUID space so they can never collide with a real account. */
    private static final long BOT_ID_NAMESPACE = 0x8A11L;

    /** What a house player reaches for most of the time; straights are the exception. */
    private static final RouletteEngine.BetType[] BOT_OUTSIDE = {
            RouletteEngine.BetType.RED,
            RouletteEngine.BetType.BLACK,
            RouletteEngine.BetType.EVEN,
            RouletteEngine.BetType.ODD,
            RouletteEngine.BetType.LOW,
            RouletteEngine.BetType.HIGH,
            RouletteEngine.BetType.DOZEN_1,
            RouletteEngine.BetType.DOZEN_2,
            RouletteEngine.BetType.DOZEN_3,
            RouletteEngine.BetType.COL_1,
            RouletteEngine.BetType.COL_2,
            RouletteEngine.BetType.COL_3
    };

    public enum Phase {
        WAITING_FOR_PLAYERS,
        BETTING,
        SPINNING,
        SETTLE
    }

    /** One bettor's place at the table: who it is and what it has on the felt. */
    public static class Seat {
        public final int index;
        public UUID playerId;
        public String name = "";
        public boolean isBot;
        /** The chips this seat has on the felt, one entry per target it has backed. */
        public final List<RouletteEngine.Bet> bets = new ArrayList<>();
        public long payout;
        public long netChange;
        public boolean settled;
        public int sinceAction;

        public Seat(int index) {
            this.index = index;
        }

        public boolean isOccupied() {
            return playerId != null;
        }

        public boolean hasBet() {
            return !bets.isEmpty();
        }

        /** The seat's total exposure on the felt. */
        public long stake() {
            long total = 0;
            for (RouletteEngine.Bet bet : bets) {
                total += bet.amount;
            }
            return total;
        }
    }

    public static class TableEvent {
        public final int tick;
        public final String kind;
        public final int seat;
        public final String text;

        public TableEvent(int tick, String kind, int seat, String text) {
            this.tick = tick;
            this.kind = kind;
            this.seat = seat;
            this.text = text;
        }
    }

    // ---------------------------------------------------------------- wheel maths

    /** Green is pocket zero, the only one that beats every outside bet. */
    public static boolean isGreen(int number) {
        return number == 0;
    }

    public static boolean isRed(int number) {
        return number > 0 && RouletteEngine.RED_NUMBERS.contains(number);
    }

    public static boolean isBlack(int number) {
        return number > 0 && !RouletteEngine.RED_NUMBERS.contains(number);
    }

    /**
     * Payout for one seat's layout on a given number, reusing
     * {@link RouletteEngine#isWinningBet} and the multipliers on
     * {@link RouletteEngine.BetType} so the multiplayer table and the single-player engine
     * cannot quote different odds.
     *
     * <p>A winning bet returns its stake plus stake x {@code payoutMultiplier}, which is the
     * same expression {@link RouletteEngine#spin(List)} accumulates into
     * {@code SpinResult.totalWon}.
     */
    public static long payoutFor(List<RouletteEngine.Bet> bets, int number) {
        boolean red = isRed(number);
        boolean black = isBlack(number);
        long total = 0;
        for (RouletteEngine.Bet bet : bets) {
            if (RouletteEngine.isWinningBet(bet, number, red, black)) {
                total += bet.amount + (bet.amount * bet.type.payoutMultiplier);
            }
        }
        return total;
    }

    /**
     * Cubic ease-out. The wheel leaves at full speed and spends the last third of the spin
     * crawling, which is what makes the ball look like it is being dragged into a pocket.
     */
    public static double wheelEase(double t) {
        double inv = 1.0 - Math.min(1.0, Math.max(0.0, t));
        return 1.0 - inv * inv * inv;
    }

    // ---------------------------------------------------------------- state

    private final Seat[] seats = new Seat[MAX_SEATS];
    private final List<TableEvent> events = new ArrayList<>();
    private final List<Integer> history = new ArrayList<>();
    private final Map<UUID, Long> pendingPayouts = new LinkedHashMap<>();

    /** Server-owned CSPRNG. The client never sees, seeds or predicts this. */
    private final SecureRandom rng = new SecureRandom();
    private final Random botRandom = new Random();

    private Phase phase = Phase.WAITING_FOR_PLAYERS;
    private int animClock = 0;
    private int phaseTicks = 0;
    private int roundNumber = 0;

    private boolean autoFillBots = true;
    private boolean spinRequested = false;
    private UUID tableOwner;
    private String dealerSay = "";

    /** The number this round pays on. Secret until {@link Phase#SETTLE}. */
    private int winningNumber = -1;
    private int lastWinningNumber = -1;
    /** Test hook: when set, the next spins land here instead of on the CSPRNG. */
    private int forcedNumber = -1;
    private long spinNonce;

    /** The angle the wheel parked at after the last spin. */
    private double parkedAngle = 0.0;
    /** The angle the current spin started from, and the tick it started on. */
    private double spinFromAngle = 0.0;
    private int spinStartTick = 0;

    public RouletteTable() {
        for (int i = 0; i < MAX_SEATS; i++) {
            seats[i] = new Seat(i);
        }
    }

    // ---------------------------------------------------------------- seats

    public int seatOf(UUID playerId) {
        if (playerId == null) {
            return -1;
        }
        for (Seat s : seats) {
            if (playerId.equals(s.playerId)) {
                return s.index;
            }
        }
        return -1;
    }

    public Seat[] getSeats() {
        return seats;
    }

    public Seat seat(UUID playerId) {
        int i = seatOf(playerId);
        return i < 0 ? null : seats[i];
    }

    public int join(UUID playerId, String name) {
        int existing = seatOf(playerId);
        if (existing >= 0) {
            return existing;
        }
        for (Seat s : seats) {
            if (!s.isOccupied()) {
                s.playerId = playerId;
                s.name = name == null ? "" : name;
                s.isBot = false;
                s.bets.clear();
                s.payout = 0;
                s.netChange = 0;
                s.settled = false;
                s.sinceAction = 0;
                if (tableOwner == null) {
                    tableOwner = playerId;
                }
                pushEvent("JOIN", s.index, s.name + " sits down");
                if (phase == Phase.WAITING_FOR_PLAYERS) {
                    enterBetting();
                }
                return s.index;
            }
        }
        return -1;
    }

    public void leave(UUID playerId) {
        int i = seatOf(playerId);
        if (i < 0) {
            return;
        }
        Seat s = seats[i];
        String who = s.name;
        // Clear bets first (the caller refunds them through clearBets) so a seat that walks
        // away mid-spin does not settle into a queue that has nobody to pay.
        s.bets.clear();
        s.playerId = null;
        s.name = "";
        s.isBot = false;
        s.payout = 0;
        s.netChange = 0;
        s.settled = false;
        pushEvent("LEAVE", i, who + " leaves");
        if (tableOwner != null && tableOwner.equals(playerId)) {
            tableOwner = null;
            for (Seat other : seats) {
                if (other.isOccupied() && !other.isBot) {
                    tableOwner = other.playerId;
                    break;
                }
            }
        }
        if (countSeatedHumans() == 0 && countSeatedBots() == 0 && pendingPayouts.isEmpty()) {
            resetToWaiting();
        }
    }

    public void toggleAutoFillBots() {
        autoFillBots = !autoFillBots;
        if (!autoFillBots) {
            clearBots();
        }
    }

    public boolean isAutoFillBots() {
        return autoFillBots;
    }

    public UUID getTableOwner() {
        return tableOwner;
    }

    private int countSeatedHumans() {
        int n = 0;
        for (Seat s : seats) {
            if (s.isOccupied() && !s.isBot) {
                n++;
            }
        }
        return n;
    }

    private int countSeatedBots() {
        int n = 0;
        for (Seat s : seats) {
            if (s.isOccupied() && s.isBot) {
                n++;
            }
        }
        return n;
    }

    private void fillBots() {
        if (!autoFillBots) {
            return;
        }
        String[] names = {"Ada", "Boris", "Cleo", "Dmitri", "Esme", "Frank"};
        for (Seat s : seats) {
            if (!s.isOccupied()) {
                s.playerId = botId(s.index);
                s.name = names[s.index % names.length];
                s.isBot = true;
                s.bets.clear();
                s.payout = 0;
                s.netChange = 0;
                s.settled = false;
                s.sinceAction = 0;
                pushEvent("JOIN", s.index, s.name + " (house) sits down");
            }
        }
    }

    private void clearBots() {
        for (Seat s : seats) {
            if (s.isOccupied() && s.isBot) {
                s.playerId = null;
                s.name = "";
                s.isBot = false;
                s.bets.clear();
                s.payout = 0;
                s.netChange = 0;
                s.settled = false;
            }
        }
    }

    private static UUID botId(int seat) {
        return new UUID(BOT_ID_NAMESPACE, seat);
    }

    // ---------------------------------------------------------------- betting

    /** True while chips may still be put down, changed or taken back. */
    public boolean isWindowOpen() {
        return phase == Phase.BETTING || phase == Phase.WAITING_FOR_PLAYERS;
    }

    /**
     * Puts a chip on one target.
     *
     * <p>The bet is on the felt the moment this returns true — there is no confirmation step.
     * Betting a target the seat already covers replaces that chip rather than stacking a
     * second one, so a layout never carries two chips on the same cell.
     *
     * @return false when the seat is unknown, the window has closed, the stake is outside
     *         {@link #MIN_BET}..{@link #MAX_BET}, or the seat already has
     *         {@value #MAX_BETS_PER_SEAT} targets covered
     */
    public boolean placeBet(UUID playerId, RouletteEngine.BetType type, int number, long amount) {
        if (type == null || !isWindowOpen()) {
            return false;
        }
        int i = seatOf(playerId);
        if (i < 0) {
            return false;
        }
        if (amount < MIN_BET || amount > MAX_BET) {
            return false;
        }
        // A straight bet on a number that is not on the wheel is refused rather than clamped:
        // clamping would quietly move somebody's chips onto a neighbouring pocket.
        if (type == RouletteEngine.BetType.STRAIGHT && !onWheel(number)) {
            return false;
        }
        int target = type == RouletteEngine.BetType.STRAIGHT ? number : 0;
        Seat s = seats[i];
        int existing = indexOfBet(s, type, target);
        if (existing >= 0) {
            s.bets.set(existing, new RouletteEngine.Bet(type, target, amount));
        } else {
            if (s.bets.size() >= MAX_BETS_PER_SEAT) {
                return false;
            }
            s.bets.add(new RouletteEngine.Bet(type, target, amount));
        }
        pushEvent("BET", i, s.name + " puts " + amount + " on " + describe(type, target));
        return true;
    }

    /** What a seat already has on one target, so the caller can refund it before re-charging. */
    public long stakeOn(UUID playerId, RouletteEngine.BetType type, int number) {
        int i = seatOf(playerId);
        if (i < 0 || type == null) {
            return 0;
        }
        if (type == RouletteEngine.BetType.STRAIGHT && !onWheel(number)) {
            return 0;
        }
        int target = type == RouletteEngine.BetType.STRAIGHT ? number : 0;
        int at = indexOfBet(seats[i], type, target);
        return at < 0 ? 0 : seats[i].bets.get(at).amount;
    }

    /** Everything a seat has on the felt, as a total. */
    public long stakeOf(UUID playerId) {
        int i = seatOf(playerId);
        return i < 0 ? 0 : seats[i].stake();
    }

    /**
     * Takes every chip off the felt for one seat.
     *
     * @return the stake the caller must refund, or 0 once the wheel is turning
     */
    public long clearBets(UUID playerId) {
        if (!isWindowOpen()) {
            return 0;
        }
        int i = seatOf(playerId);
        if (i < 0) {
            return 0;
        }
        Seat s = seats[i];
        long refund = s.stake();
        if (refund <= 0) {
            return 0;
        }
        s.bets.clear();
        pushEvent("CLEAR", i, s.name + " takes " + refund + " back");
        return refund;
    }

    /**
     * Takes back the last chip of one seat.
     *
     * @return the stake the caller must refund, or 0 when the felt is empty or the wheel is
     *         already turning
     */
    public long undoLastBet(UUID playerId) {
        if (!isWindowOpen()) {
            return 0;
        }
        int i = seatOf(playerId);
        if (i < 0) {
            return 0;
        }
        Seat s = seats[i];
        if (s.bets.isEmpty()) {
            return 0;
        }
        RouletteEngine.Bet removed = s.bets.remove(s.bets.size() - 1);
        pushEvent("UNDO", i, s.name + " takes back " + removed.amount);
        return removed.amount;
    }

    private static int indexOfBet(Seat seat, RouletteEngine.BetType type, int number) {
        for (int i = 0; i < seat.bets.size(); i++) {
            RouletteEngine.Bet bet = seat.bets.get(i);
            if (bet.type == type && bet.number == number) {
                return i;
            }
        }
        return -1;
    }

    private static boolean onWheel(int number) {
        return number >= 0 && number < POCKETS;
    }

    /** A human name for a target, for the ticker and the seat plates. */
    public static String describe(RouletteEngine.BetType type, int number) {
        return switch (type) {
            case STRAIGHT -> String.valueOf(number);
            case RED -> "RED";
            case BLACK -> "BLACK";
            case EVEN -> "EVEN";
            case ODD -> "ODD";
            case LOW -> "1-18";
            case HIGH -> "19-36";
            case DOZEN_1 -> "1st 12";
            case DOZEN_2 -> "2nd 12";
            case DOZEN_3 -> "3rd 12";
            case COL_1 -> "COL 1";
            case COL_2 -> "COL 2";
            case COL_3 -> "COL 3";
        };
    }

    // ---------------------------------------------------------------- round flow

    /** Closes the window and turns the wheel now, without waiting out the timer. */
    public boolean requestSpin(UUID playerId) {
        if (!isWindowOpen() || seatOf(playerId) < 0 || !anyBetPlaced()) {
            return false;
        }
        spinRequested = true;
        return true;
    }

    private boolean anyBetPlaced() {
        for (Seat s : seats) {
            if (s.isOccupied() && s.hasBet()) {
                return true;
            }
        }
        return false;
    }

    private void enterBetting() {
        phase = Phase.BETTING;
        phaseTicks = 0;
        spinRequested = false;
        winningNumber = -1;
        dealerSay = "Place your bets";
        for (Seat s : seats) {
            // The felt is raked clean, but the last result stays on the plate: a player who
            // walked away for the four-second hold would otherwise never see what they won.
            // It is cleared when the wheel next turns (startSpin) or when the seat changes.
            s.bets.clear();
        }
    }

    private void resetToWaiting() {
        clearBots();
        phase = Phase.WAITING_FOR_PLAYERS;
        phaseTicks = 0;
        dealerSay = "";
        winningNumber = -1;
        for (Seat s : seats) {
            s.bets.clear();
            s.payout = 0;
            s.netChange = 0;
            s.settled = false;
        }
    }

    private void startSpin() {
        fillBots();
        phase = Phase.SPINNING;
        phaseTicks = 0;
        roundNumber++;
        spinRequested = false;
        // The wheel starts where it parked, so the angle never jumps backwards between spins.
        spinFromAngle = parkedAngle;
        spinStartTick = animClock;
        winningNumber = drawNumber(lastSpinner());
        for (Seat s : seats) {
            s.payout = 0;
            s.netChange = 0;
            s.settled = false;
        }
        dealerSay = "No more bets";
        pushEvent("SPIN", -1, "The wheel is turning");
    }

    /** Whoever asked for the spin, or the house if the timer ran the window out. */
    private UUID lastSpinner() {
        for (Seat s : seats) {
            if (s.isOccupied() && !s.isBot && s.hasBet()) {
                return s.playerId;
            }
        }
        return null;
    }

    private void settle() {
        phase = Phase.SETTLE;
        phaseTicks = 0;
        parkedAngle = spinFromAngle + WHEEL_TURN_DEGREES;
        boolean red = isRed(winningNumber);
        for (Seat s : seats) {
            if (!s.isOccupied() || !s.hasBet()) {
                continue;
            }
            long stake = s.stake();
            long payout = payoutFor(s.bets, winningNumber);
            s.payout = payout;
            s.netChange = payout - stake;
            s.settled = true;
            // House players have no account; crediting their synthetic id would grow a
            // phantom balance every round.
            if (s.playerId != null && !s.isBot && payout > 0) {
                pendingPayouts.merge(s.playerId, payout, Long::sum);
            }
        }
        lastWinningNumber = winningNumber;
        history.add(winningNumber);
        while (history.size() > HISTORY_KEEP) {
            history.remove(0);
        }
        String colour = isGreen(winningNumber) ? "green" : (red ? "red" : "black");
        dealerSay = winningNumber + " " + colour;
        pushEvent("SETTLE", -1, winningNumber + " " + colour + " pays");
    }

    private void finishRound() {
        enterBetting();
    }

    /**
     * Draws the winning pocket.
     *
     * <p>The draw is server-side: a CSPRNG value mixed with the spinning player's id and a
     * per-spin nonce, so no packet a client can send reaches the number. Nothing derived from
     * the result leaves this class before {@link #settle()}.
     */
    private int drawNumber(UUID spinner) {
        if (forcedNumber >= 0) {
            return forcedNumber;
        }
        long seed = rng.nextLong();
        if (spinner != null) {
            seed ^= spinner.getMostSignificantBits() ^ spinner.getLeastSignificantBits();
        }
        seed ^= (++spinNonce * 0x9E3779B97F4A7C15L);
        return (int) Math.floorMod(seed, (long) POCKETS);
    }

    // ---------------------------------------------------------------- hooks

    /**
     * Test hook: pin the wheel to {@code number} until {@link #clearForcedNumber()} is called,
     * the roulette equivalent of baccarat's pinned shoe.
     */
    public void forceWinningNumber(int number) {
        if (number >= 0 && number < POCKETS) {
            forcedNumber = number;
        }
    }

    public void clearForcedNumber() {
        forcedNumber = -1;
    }

    public boolean isForced() {
        return forcedNumber >= 0;
    }

    // ---------------------------------------------------------------- ticking

    public void tickServer() {
        animClock++;
        phaseTicks++;
        for (Seat s : seats) {
            if (s.isOccupied()) {
                s.sinceAction++;
            }
        }

        switch (phase) {
            case WAITING_FOR_PLAYERS -> {
                if (countSeatedHumans() > 0) {
                    enterBetting();
                }
            }
            case BETTING -> {
                botBetting();
                if (spinRequested || phaseTicks >= BETTING_TICKS) {
                    if (anyBetPlaced()) {
                        startSpin();
                    } else {
                        // Nobody put anything down: keep the window open rather than
                        // spinning for an empty table.
                        spinRequested = false;
                        phaseTicks = 0;
                    }
                }
            }
            case SPINNING -> {
                if (phaseTicks >= SPIN_TICKS) {
                    settle();
                }
            }
            case SETTLE -> {
                if (phaseTicks >= SETTLE_HOLD) {
                    finishRound();
                }
            }
        }
        while (events.size() > 8) {
            events.remove(0);
        }
    }

    /**
     * House players lay one chip every {@value #BOT_THINK} ticks, up to two targets, so the
     * felt fills while a human is still deciding instead of appearing all at once.
     */
    private void botBetting() {
        if (!autoFillBots) {
            return;
        }
        if (countSeatedHumans() > 0 && countSeatedBots() == 0) {
            fillBots();
        }
        long[] stakes = {25, 50, 100, 200};
        for (Seat s : seats) {
            if (!s.isOccupied() || !s.isBot) {
                continue;
            }
            if (s.sinceAction < BOT_THINK || s.bets.size() >= 2) {
                continue;
            }
            s.sinceAction = 0;
            long amount = stakes[botRandom.nextInt(stakes.length)];
            RouletteEngine.BetType type;
            int number = 0;
            if (botRandom.nextInt(4) == 0) {
                type = RouletteEngine.BetType.STRAIGHT;
                number = botRandom.nextInt(POCKETS);
            } else {
                type = BOT_OUTSIDE[botRandom.nextInt(BOT_OUTSIDE.length)];
            }
            if (indexOfBet(s, type, number) >= 0) {
                continue;
            }
            s.bets.add(new RouletteEngine.Bet(type, number, amount));
            pushEvent("BET", s.index, s.name + " puts " + amount + " on " + describe(type, number));
        }
    }

    // ---------------------------------------------------------------- view

    public Phase getPhase() {
        return phase;
    }

    public int getAnimClock() {
        return animClock;
    }

    public int getPhaseTicks() {
        return phaseTicks;
    }

    public String getDealerSay() {
        return dealerSay;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public long getMinBet() {
        return MIN_BET;
    }

    public long getMaxBet() {
        return MAX_BET;
    }

    public List<TableEvent> getRecentEvents() {
        return events;
    }

    public List<Integer> getHistory() {
        return history;
    }

    public int getLastWinningNumber() {
        return lastWinningNumber;
    }

    /**
     * The number the wheel is stopping on, or {@code -1} until the phase reaches
     * {@link Phase#SETTLE}. There is deliberately no other accessor: a client that can ask
     * early is a client that can cheat.
     */
    public int getWinningNumber() {
        return phase == Phase.SETTLE ? winningNumber : -1;
    }

    /** How far the current spin has run, 0..1, derived from {@link #animClock}. */
    public double spinProgress() {
        if (phase != Phase.SPINNING && phase != Phase.SETTLE) {
            return 0.0;
        }
        int elapsed = animClock - spinStartTick;
        if (elapsed <= 0) {
            return 0.0;
        }
        if (elapsed >= SPIN_TICKS) {
            return 1.0;
        }
        return elapsed / (double) SPIN_TICKS;
    }

    /**
     * The wheel's rotation in degrees, clockwise from the top.
     *
     * <p>A pure function of {@link #animClock}: monotonic inside a spin (the ease-out only
     * ever adds) and continuous across spins, because each spin starts from the angle the
     * previous one parked at. Every viewer therefore draws the same wheel from the same
     * {@code wheelAngle}/{@code phaseTicks} pair.
     */
    public double getWheelAngleDegrees() {
        return spinFromAngle + WHEEL_TURN_DEGREES * wheelEase(spinProgress());
    }

    /** Pulls every payout the server still owes and clears the queue. */
    public Map<UUID, Long> drainPayouts() {
        if (pendingPayouts.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> copy = new LinkedHashMap<>(pendingPayouts);
        pendingPayouts.clear();
        return copy;
    }

    /**
     * A cheap token that changes whenever anything a viewer can see changes.
     *
     * <p>The block entity compares it against the last one it broadcast instead of diffing
     * every field, which is what keeps a six-seat table at a handful of packets per second.
     */
    public int stateToken() {
        int h = 17;
        h = h * 31 + phase.ordinal();
        h = h * 31 + roundNumber;
        h = h * 31 + (int) Math.round(getWheelAngleDegrees() / 2.0);
        for (Seat s : seats) {
            h = h * 31 + (s.playerId == null ? 0 : s.playerId.hashCode());
            h = h * 31 + s.bets.size();
            h = h * 31 + (int) s.stake();
            h = h * 31 + (s.settled ? 1 : 0);
        }
        return h;
    }

    /**
     * The table as one viewer sees it. Mirrors {@code BaccaratTable.toJson}: the shared table
     * facts, then one block per seat, then the ticker, plus the wheel for roulette.
     *
     * <p>{@code winningNumber} is {@code -1} until SETTLE, and the draw itself is never
     * serialized at any other phase.
     */
    public JsonObject toJson(UUID viewer) {
        JsonObject obj = new JsonObject();
        obj.addProperty("phase", phase.name());
        obj.addProperty("animClock", animClock);
        obj.addProperty("phaseTicks", phaseTicks);
        obj.addProperty("dealerSay", dealerSay);
        obj.addProperty("round", roundNumber);
        obj.addProperty("seated", seatOf(viewer) >= 0);
        obj.addProperty("mySeat", seatOf(viewer));
        obj.addProperty("autoFillBots", autoFillBots);
        obj.addProperty("isOwner", viewer != null && viewer.equals(tableOwner));
        obj.addProperty("minBet", MIN_BET);
        obj.addProperty("maxBet", MAX_BET);
        obj.addProperty("maxSeats", MAX_SEATS);
        obj.addProperty("maxBets", MAX_BETS_PER_SEAT);
        obj.addProperty("bettingTicks", BETTING_TICKS);
        obj.addProperty("spinTicks", SPIN_TICKS);
        obj.addProperty("wheelAngle", Math.round(getWheelAngleDegrees() * 100.0) / 100.0);
        // Degrees the wheel turns over a spin. Sent so the client can rebuild the rotation
        // curve from (angle, tick) alone instead of hard-coding the same constant.
        obj.addProperty("wheelTurn", WHEEL_TURN_DEGREES);
        obj.addProperty("spinning", phase == Phase.SPINNING);
        obj.addProperty("winningNumber", getWinningNumber());
        obj.addProperty("lastWinningNumber", lastWinningNumber);
        obj.addProperty("myStake", stakeOf(viewer));

        JsonArray hist = new JsonArray();
        for (int n : history) {
            hist.add(n);
        }
        obj.add("history", hist);

        JsonArray mine = new JsonArray();
        int mySeat = seatOf(viewer);
        if (mySeat >= 0) {
            for (RouletteEngine.Bet bet : seats[mySeat].bets) {
                mine.add(betToJson(bet));
            }
        }
        obj.add("myBets", mine);

        JsonArray seatArr = new JsonArray();
        for (Seat s : seats) {
            JsonObject o = new JsonObject();
            o.addProperty("index", s.index);
            o.addProperty("occupied", s.isOccupied());
            o.addProperty("name", s.name);
            o.addProperty("bot", s.isBot);
            o.addProperty("bet", s.stake());
            o.addProperty("bets", s.bets.size());
            o.addProperty("payout", s.payout);
            o.addProperty("net", s.netChange);
            o.addProperty("settled", s.settled);
            o.addProperty("mine", s.playerId != null && s.playerId.equals(viewer));
            JsonArray chips = new JsonArray();
            for (RouletteEngine.Bet bet : s.bets) {
                chips.add(betToJson(bet));
            }
            o.add("chips", chips);
            seatArr.add(o);
        }
        obj.add("seats", seatArr);

        JsonArray ev = new JsonArray();
        for (TableEvent e : events) {
            JsonObject o = new JsonObject();
            o.addProperty("t", e.tick);
            o.addProperty("k", e.kind);
            o.addProperty("s", e.seat);
            o.addProperty("x", e.text);
            ev.add(o);
        }
        obj.add("events", ev);
        return obj;
    }

    private static JsonObject betToJson(RouletteEngine.Bet bet) {
        JsonObject o = new JsonObject();
        o.addProperty("type", bet.type.name());
        o.addProperty("number", bet.number);
        o.addProperty("amount", bet.amount);
        return o;
    }

    // ---------------------------------------------------------------- events

    private void pushEvent(String kind, int seat, String text) {
        events.add(new TableEvent(animClock, kind, seat, text));
        if (events.size() > 8) {
            events.remove(0);
        }
    }
}
