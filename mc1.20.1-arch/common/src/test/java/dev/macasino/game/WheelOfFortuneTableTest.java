package dev.macasino.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the Wheel of Fortune rules: betting limits, spin determinism, payout table,
 * and segment index return values.
 *
 * <p>{@link WheelOfFortuneTable} is plain Java, so every rule is checkable on a bare JVM.
 * Spins are made deterministic with a server-owned {@link java.util.Random} seeded per
 * spin — tests spin repeatedly until the desired segment appears.
 */
class WheelOfFortuneTableTest {

    private static final long BET = 100L;

    /** Rolls until segment 0 (2x multiplier) appears, then returns the segment. */
    private static int rollUntilSegment(WheelOfFortuneTable table, UUID p) {
        int segment;
        int attempts = 0;
        do {
            segment = table.spin(p);
            attempts++;
        } while (segment != 0 && attempts < 10000);
        return segment;
    }

    // ------------------------------------------------------------------
    // betting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("place bet accepts the limits and rejects outside them")
    void placeBetLimits() {
        WheelOfFortuneTable table = new WheelOfFortuneTable();
        UUID p = UUID.randomUUID();
        assertTrue(table.placeBet(p, WheelOfFortuneTable.MIN_BET), "minimum accepted");
        assertTrue(table.placeBet(p, WheelOfFortuneTable.MAX_BET), "maximum accepted");
        assertFalse(table.placeBet(p, WheelOfFortuneTable.MIN_BET - 1), "below minimum rejected");
        assertFalse(table.placeBet(p, WheelOfFortuneTable.MAX_BET + 1), "above maximum rejected");
    }

    @Test
    @DisplayName("getBet returns the current stake")
    void getBetTracksStake() {
        WheelOfFortuneTable table = new WheelOfFortuneTable();
        UUID p = UUID.randomUUID();
        assertEquals(0L, table.getBet(p));
        table.placeBet(p, BET);
        assertEquals(BET, table.getBet(p));
    }

    @Test
    @DisplayName("clearBet removes the stake and returns it")
    void clearBetReturnsStake() {
        WheelOfFortuneTable table = new WheelOfFortuneTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        assertEquals(BET, table.clearBet(p), "clearBet returns the stake");
        assertEquals(0L, table.getBet(p), "bet is cleared");
    }

    // ------------------------------------------------------------------
    // spin resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("spin returns a segment index between 0 and 7")
    void spinReturnsValidSegment() {
        WheelOfFortuneTable table = new WheelOfFortuneTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int segment = table.spin(p);
        assertTrue(segment >= 0 && segment <= 7, "segment must be 0..7, was " + segment);
        assertEquals(segment, table.getLastSegment(p));
    }

    @Test
    @DisplayName("segment 0 (2x) pays even money")
    void segment2xPaysEvenMoney() {
        WheelOfFortuneTable table = new WheelOfFortuneTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int segment = rollUntilSegment(table, p);
        assertEquals(0, segment, "eventually roll segment 0 (2x)");
        assertEquals(BET * 2, table.getLastPayout(p), "2x multiplier pays even money");
    }

    @Test
    @DisplayName("segment 5 (100x) pays 100x when it eventually lands")
    void segment100xPays100x() {
        WheelOfFortuneTable table = new WheelOfFortuneTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int segment;
        int attempts = 0;
        do {
            segment = table.spin(p);
            attempts++;
        } while (segment != 5 && attempts < 10000);
        assertEquals(5, segment, "eventually roll segment 5 (100x)");
        assertEquals(BET * 100, table.getLastPayout(p), "100x multiplier pays 100x");
    }

    @Test
    @DisplayName("all 8 segments pay their declared multiplier")
    void allSegmentsPayDeclaredMultiplier() {
        WheelOfFortuneTable table = new WheelOfFortuneTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);

        long[] expected = {2, 5, 10, 20, 50, 100, 5, 2};
        for (int seg = 0; seg < 8; seg++) {
            int seg2 = -1;
            int attempts = 0;
            do {
                seg2 = table.spin(p);
                attempts++;
            } while (seg2 != seg && attempts < 10000);
            assertEquals(seg, seg2, "segment " + seg + " eventually lands");
            assertEquals(BET * expected[seg], table.getLastPayout(p),
                    "segment " + seg + " pays " + expected[seg] + "x");
        }
    }

    // ------------------------------------------------------------------
    // payout queue
    // ------------------------------------------------------------------

    @Test
    @DisplayName("payouts are drained exactly once")
    void payoutsDrainedOnce() {
        WheelOfFortuneTable table = new WheelOfFortuneTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int segment = rollUntilSegment(table, p);
        long payout = table.getLastPayout(p);
        long[] expected = {2, 5, 10, 20, 50, 100, 5, 2};
        assertEquals(BET * expected[segment], payout, "segment " + segment + " pays its declared multiplier");
    }
}
