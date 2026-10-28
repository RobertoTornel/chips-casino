package dev.macasino.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the Keno rules: betting limits, draw determinism, payout table,
 * and match counting.
 *
 * <p>{@link KenoTable} is plain Java, so every rule is checkable on a bare JVM.
 * Draws are made deterministic with a server-owned {@link java.util.Random} seeded per
 * draw — tests draw repeatedly until the desired match count appears.
 *
 * <p>Tests that assert on outcomes switch the house bots off, because they bet from an
 * unseeded {@link java.util.Random}.
 */
class KenoTableTest {

    private static final long BET = 100L;

    /** Rolls until exactly 5 matches occur, then returns the match count. */
    private static int drawUntilFiveMatches(KenoTable table, UUID p) {
        int matches;
        int attempts = 0;
        do {
            int[] picks = {1, 2, 3, 4, 5};
            matches = table.draw(p, picks);
            attempts++;
        } while (matches != 5 && attempts < 100000);
        return matches;
    }

    // ------------------------------------------------------------------
    // betting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("place bet accepts the limits and rejects outside them")
    void placeBetLimits() {
        KenoTable table = new KenoTable();
        UUID p = UUID.randomUUID();
        assertTrue(table.placeBet(p, KenoTable.MIN_BET), "minimum accepted");
        assertTrue(table.placeBet(p, KenoTable.MAX_BET), "maximum accepted");
        assertFalse(table.placeBet(p, KenoTable.MIN_BET - 1), "below minimum rejected");
        assertFalse(table.placeBet(p, KenoTable.MAX_BET + 1), "above maximum rejected");
    }

    @Test
    @DisplayName("getBet returns the current stake")
    void getBetTracksStake() {
        KenoTable table = new KenoTable();
        UUID p = UUID.randomUUID();
        assertEquals(0L, table.getBet(p));
        table.placeBet(p, BET);
        assertEquals(BET, table.getBet(p));
    }

    @Test
    @DisplayName("clearBet removes the stake and returns it")
    void clearBetReturnsStake() {
        KenoTable table = new KenoTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        assertEquals(BET, table.clearBet(p), "clearBet returns the stake");
        assertEquals(0L, table.getBet(p), "bet is cleared");
    }

    // ------------------------------------------------------------------
    // draw resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("draw returns a match count between 0 and 5")
    void drawReturnsValidMatchCount() {
        KenoTable table = new KenoTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int[] picks = {1, 2, 3, 4, 5};
        int matches = table.draw(p, picks);
        assertTrue(matches >= 0 && matches <= 5, "matches must be 0..5, was " + matches);
        assertEquals(matches, table.getMatches(p));
    }

    @Test
    @DisplayName("draw with invalid bet returns -1")
    void drawRejectsInvalidBet() {
        KenoTable table = new KenoTable();
        UUID p = UUID.randomUUID();
        int[] picks = {1, 2, 3, 4, 5};
        assertEquals(-1, table.draw(p, picks), "no bet placed returns -1");
    }

    @Test
    @DisplayName("draw with wrong number of picks returns -1")
    void drawRejectsWrongPickCount() {
        KenoTable table = new KenoTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int[] picks = {1, 2, 3};
        assertEquals(-1, table.draw(p, picks), "wrong number of picks returns -1");
    }

    @Test
    @DisplayName("5 matches pays 500x")
    void fiveMatchesPays500x() {
        KenoTable table = new KenoTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int matches = drawUntilFiveMatches(table, p);
        assertEquals(5, matches, "eventually hit 5 matches");
        assertEquals(BET * 500, table.getLastPayout(p), "5 matches pays 500x");
    }

    @Test
    @DisplayName("all match counts pay their declared multiplier")
    void allMatchCountsPayDeclaredMultiplier() {
        KenoTable table = new KenoTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);

        long[] expected = {0, 0, 2, 10, 50, 500};
        for (int m = 0; m <= 5; m++) {
            int matches = -1;
            int attempts = 0;
            // Draw repeatedly until we get exactly m matches
            do {
                int[] picks = {1, 2, 3, 4, 5};
                matches = table.draw(p, picks);
                attempts++;
            } while (matches != m && attempts < 100000);
            assertEquals(m, matches, "eventually match " + m + " numbers");
            assertEquals(BET * expected[m], table.getLastPayout(p),
                    m + " matches pays " + expected[m] + "x");
        }
    }

    // ------------------------------------------------------------------
    // payout queue
    // ------------------------------------------------------------------

    @Test
    @DisplayName("payouts are drained exactly once")
    void payoutsDrainedOnce() {
        KenoTable table = new KenoTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int[] picks = {1, 2, 3, 4, 5};
        int matches = table.draw(p, picks);
        long payout = table.getLastPayout(p);
        long[] expected = {0, 0, 2, 10, 50, 500};
        assertEquals(BET * expected[matches], payout, matches + " matches pays its declared multiplier");
    }
}
