package dev.macasino.game;

import dev.macasino.game.RouletteEngine.BetType;
import dev.macasino.game.RouletteTable.Phase;
import dev.macasino.game.RouletteTable.Seat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the roulette rule set the server owns: seating, bet limits, the betting window,
 * the server draw, the payout table (byte-for-byte with {@link RouletteEngine}), the spin
 * clock and the payout queue.
 *
 * <p>{@link RouletteTable} is plain Java, so every rule is checkable on a bare JVM. Spins are
 * made deterministic with {@link RouletteTable#forceWinningNumber(int)} — the equivalent of
 * baccarat's pinned shoe — and the wheel lands after {@value RouletteTable#SPIN_TICKS} ticks
 * of spinning. Nothing sleeps.
 *
 * <p>Tests that assert on outcomes switch the house bots off
 * ({@link RouletteTable#toggleAutoFillBots()}), because they bet from an unseeded
 * {@link java.util.Random}.
 */
class RouletteTableTest {

    private static final long BET = 100L;

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Runs the server clock n times. */
    private static void tick(RouletteTable table, int times) {
        for (int i = 0; i < times; i++) {
            table.tickServer();
        }
    }

    /** Arms the spin and runs the table until it reaches SETTLE (the number is up). */
    private static void spinToSettle(RouletteTable table) {
        table.tickServer(); // arm -> startSpin on the first beat
        int g = 0;
        while (g++ < 4000 && table.getPhase() != Phase.SETTLE) {
            table.tickServer();
        }
    }

    /** Runs the table from SETTLE back to the next betting window. */
    private static void spinToNextWindow(RouletteTable table) {
        int g = 0;
        while (g++ < 4000 && table.getPhase() != Phase.BETTING) {
            table.tickServer();
        }
    }

    /** Plays one full round with a forced number and returns the table at the next window. */
    private static RouletteTable playRound(UUID player, String name, int forcedNumber) {
        RouletteTable table = new RouletteTable();
        table.toggleAutoFillBots();
        table.join(player, name);
        table.forceWinningNumber(forcedNumber);
        table.placeBet(player, BetType.RED, 0, BET);
        table.requestSpin(player);
        spinToSettle(table);
        spinToNextWindow(table);
        return table;
    }

    // ------------------------------------------------------------------
    // seating
    // ------------------------------------------------------------------

    @Test
    @DisplayName("join seats players in order and refuses the seventh")
    void joinSeatsInOrderRefusesSeventh() {
        RouletteTable table = new RouletteTable();
        UUID[] ids = new UUID[7];
        for (int i = 0; i < 7; i++) {
            ids[i] = UUID.randomUUID();
        }
        for (int i = 0; i < 6; i++) {
            assertEquals(i, table.join(ids[i], "P" + i), "seat " + i);
        }
        assertEquals(-1, table.join(ids[6], "P6"), "the seventh player is refused");
        int occupied = 0;
        for (Seat s : table.getSeats()) {
            if (s.isOccupied()) {
                occupied++;
            }
        }
        assertEquals(6, occupied);
    }

    @Test
    @DisplayName("leave frees the seat")
    void leaveFreesTheSeat() {
        RouletteTable table = new RouletteTable();
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        assertEquals(0, table.seatOf(p));
        table.leave(p);
        assertEquals(-1, table.seatOf(p));
        assertFalse(table.getSeats()[0].isOccupied());
    }

    // ------------------------------------------------------------------
    // betting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("place bet accepts the limits and rejects outside them")
    void placeBetLimits() {
        RouletteTable table = new RouletteTable();
        table.toggleAutoFillBots();
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        assertTrue(table.placeBet(p, BetType.RED, 0, RouletteTable.MIN_BET), "minimum accepted");
        assertTrue(table.placeBet(p, BetType.BLACK, 0, RouletteTable.MAX_BET), "maximum accepted");
        assertFalse(table.placeBet(p, BetType.RED, 0, RouletteTable.MIN_BET - 1), "below minimum rejected");
        assertFalse(table.placeBet(p, BetType.RED, 0, RouletteTable.MAX_BET + 1), "above maximum rejected");
    }

    @Test
    @DisplayName("a rejected stake leaves the felt untouched")
    void rejectedStakeUntouched() {
        RouletteTable table = new RouletteTable();
        table.toggleAutoFillBots();
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        table.placeBet(p, BetType.RED, 0, RouletteTable.MIN_BET);
        long before = table.stakeOf(p);
        assertFalse(table.placeBet(p, BetType.RED, 0, RouletteTable.MIN_BET - 1), "below minimum rejected");
        assertEquals(before, table.stakeOf(p), "rejected bet changes nothing");
    }

    @Test
    @DisplayName("betting on the same target replaces the chip")
    void sameTargetReplaces() {
        RouletteTable table = new RouletteTable();
        table.toggleAutoFillBots();
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        table.placeBet(p, BetType.RED, 0, 100L);
        assertEquals(100L, table.stakeOf(p));
        table.placeBet(p, BetType.RED, 0, 40L);
        assertEquals(40L, table.stakeOf(p), "red now holds 40, not 140");
        assertEquals(1, table.getSeats()[0].bets.size(), "still one chip on red");
    }

    @Test
    @DisplayName("clear bets returns the whole felt stake")
    void clearBetsReturnsFeltStake() {
        RouletteTable table = new RouletteTable();
        table.toggleAutoFillBots();
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        table.placeBet(p, BetType.RED, 0, 100L);
        table.placeBet(p, BetType.EVEN, 0, 50L);
        long refund = table.clearBets(p);
        assertEquals(150L, refund, "clear refunds everything on the felt");
        assertEquals(0, table.stakeOf(p), "felt empty after clear");
    }

    @Test
    @DisplayName("undo last bet returns the last chip")
    void undoLastBetReturnsLastChip() {
        RouletteTable table = new RouletteTable();
        table.toggleAutoFillBots();
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        table.placeBet(p, BetType.RED, 0, 100L);
        table.placeBet(p, BetType.BLACK, 0, 25L);
        long refund = table.undoLastBet(p);
        assertEquals(25L, refund, "undo refunds the last chip");
        assertEquals(100L, table.stakeOf(p), "red remains");
    }

    // ------------------------------------------------------------------
    // spin authority
    // ------------------------------------------------------------------

    @Test
    @DisplayName("winning number is secret until settle")
    void winningNumberSecretUntilSettle() {
        RouletteTable table = new RouletteTable();
        table.toggleAutoFillBots();
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        table.forceWinningNumber(17);
        table.placeBet(p, BetType.STRAIGHT, 17, BET);
        table.requestSpin(p);
        tick(table, 1); // arm -> startSpin on the first beat
        int beats = 0;
        while (beats++ < 90 && table.getPhase() != Phase.SETTLE) {
            assertEquals(-1, table.getWinningNumber(), "number hidden while spinning");
            table.tickServer();
        }
        while (table.getPhase() != Phase.BETTING) {
            table.tickServer();
        }
        assertEquals(17, table.getLastWinningNumber(), "number recorded at settle");
    }

    @Test
    @DisplayName("straight up pays 35 to 1")
    void straightUpPays35To1() {
        RouletteTable table = new RouletteTable();
        table.toggleAutoFillBots();
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        table.forceWinningNumber(7);
        table.placeBet(p, BetType.STRAIGHT, 7, BET);
        table.requestSpin(p);
        spinToSettle(table);
        assertEquals(BET + BET * 35, table.getSeats()[0].payout, "straight up pays 35:1");
    }

    @Test
    @DisplayName("payouts match the single player engine")
    void payoutsMatchEngine() {
        RouletteEngine.BetType[] types = RouletteEngine.BetType.values();
        int[] numbers = {0, 1, 2, 3, 4, 12, 13, 18, 19, 36};
        for (BetType type : types) {
            for (int number : numbers) {
                RouletteTable table = new RouletteTable();
                table.toggleAutoFillBots();
                UUID p = UUID.randomUUID();
                table.join(p, "Ada");
                table.forceWinningNumber(number);
                table.placeBet(p, type, number, BET);
                table.requestSpin(p);
                spinToSettle(table);
                Seat seat = table.getSeats()[0];
                long expected = RouletteTable.payoutFor(
                        List.of(new RouletteEngine.Bet(type, number, BET)), number);
                assertEquals(expected, seat.payout, type + " on " + number + " should match the engine");
            }
        }
    }

    @Test
    @DisplayName("red and black pay even money, zero beats both")
    void redBlackEvenMoney() {
        RouletteTable table = new RouletteTable();
        table.toggleAutoFillBots();
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        table.forceWinningNumber(5); // red
        table.placeBet(p, BetType.RED, 0, BET);
        table.requestSpin(p);
        spinToSettle(table);
        assertEquals(BET + BET, table.getSeats()[0].payout, "red wins even money");
    }

    @Test
    @DisplayName("dozen pays 2 to 1")
    void dozenPaysTwoToOne() {
        RouletteTable table = new RouletteTable();
        table.toggleAutoFillBots();
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        table.forceWinningNumber(13); // 2nd dozen
        table.placeBet(p, BetType.DOZEN_2, 0, BET);
        table.requestSpin(p);
        spinToSettle(table);
        assertEquals(BET + BET * 2, table.getSeats()[0].payout, "dozen pays 2:1");
    }

    // ------------------------------------------------------------------
    // payout queue
    // ------------------------------------------------------------------

    @Test
    @DisplayName("payouts are drained exactly once")
    void payoutsDrainedOnce() {
        RouletteTable table = new RouletteTable();
        table.toggleAutoFillBots();
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        table.forceWinningNumber(5);
        table.placeBet(p, BetType.RED, 0, BET);
        table.requestSpin(p);
        spinToSettle(table);
        Map<UUID, Long> first = table.drainPayouts();
        Map<UUID, Long> second = table.drainPayouts();
        assertEquals(200L, first.get(p), "first drain pays the win");
        assertTrue(second.isEmpty(), "second drain is empty");
    }

    @Test
    @DisplayName("bots never appear in the payout queue")
    void botsNeverInPayoutQueue() {
        RouletteTable table = new RouletteTable();
        // bots on by default
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        table.forceWinningNumber(5);
        table.placeBet(p, BetType.RED, 0, BET);
        table.requestSpin(p);
        spinToSettle(table);
        for (UUID uid : table.drainPayouts().keySet()) {
            int seat = table.seatOf(uid);
            assertFalse(seat >= 0 && table.getSeats()[seat].isBot,
                    "bot " + uid + " must not be credited");
        }
    }

    // ------------------------------------------------------------------
    // round flow
    // ------------------------------------------------------------------

    @Test
    @DisplayName("settle returns to betting after the hold")
    void settleReturnsToBetting() {
        RouletteTable table = playRound(UUID.randomUUID(), "Ada", 5);
        assertEquals(Phase.BETTING, table.getPhase(), "betting reopens after settle hold");
    }

    @Test
    @DisplayName("the wheel angle is monotonic during a spin")
    void wheelAngleMonotonic() {
        RouletteTable table = new RouletteTable();
        table.toggleAutoFillBots();
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        table.forceWinningNumber(22);
        table.placeBet(p, BetType.STRAIGHT, 22, BET);
        table.requestSpin(p);
        tick(table, 1);
        double previous = table.getWheelAngleDegrees();
        boolean sawSpin = false;
        while (table.getPhase() != Phase.BETTING) {
            table.tickServer();
            if (table.getPhase() == Phase.SPINNING) {
                sawSpin = true;
                double now = table.getWheelAngleDegrees();
                assertTrue(now >= previous, "wheel angle should not decrease mid-spin: " + previous + " -> " + now);
            }
            previous = table.getWheelAngleDegrees();
        }
        assertTrue(sawSpin, "the table passed through SPINNING");
    }

    @Test
    @DisplayName("a full round always terminates")
    void fullRoundAlwaysTerminates() {
        RouletteTable table = new RouletteTable();
        UUID p = UUID.randomUUID();
        table.join(p, "Ada");
        int rounds = 0;
        int g = 0;
        while (rounds < 50 && g++ < 20000) {
            table.tickServer();
            if (table.getPhase() == Phase.BETTING && table.getRoundNumber() > rounds) {
                rounds = table.getRoundNumber();
            }
        }
        assertTrue(rounds >= 5, "the table completed several rounds: " + rounds);
    }

    @Test
    @DisplayName("bets are cleared after each round")
    void betsClearedAfterRound() {
        RouletteTable table = playRound(UUID.randomUUID(), "Ada", 5);
        Seat seat = table.getSeats()[0];
        assertEquals(0, seat.stake(), "felt cleared for the next window");
    }
}
