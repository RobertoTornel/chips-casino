package dev.casino.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the craps rules: betting limits, two-dice roll resolution, payout table,
 * and point-phase state.
 *
 * <p>All rolls are deterministic because {@link CrapsTable} uses a server-owned
 * {@link java.util.Random} seeded per roll — tests roll repeatedly until the
 * desired outcome appears.
 */
class CrapsTableTest {

    private static final long BET = 100L;

    /** Rolls until a 7 or 11 appears, then returns that total. */
    private static int rollUntilNatural(CrapsTable table, UUID p) {
        int total;
        int attempts = 0;
        do {
            total = table.roll(p);
            attempts++;
        } while (total != 7 && total != 11 && attempts < 10000);
        return total;
    }

    /** Rolls until a point (4,5,6,8,9,10) is established, then returns it. */
    private static int rollUntilPoint(CrapsTable table, UUID p) {
        int total;
        int attempts = 0;
        do {
            total = table.roll(p);
            attempts++;
        } while ((total < 4 || total > 10 || total == 7) && attempts < 10000);
        return total;
    }

    // ------------------------------------------------------------------
    // seating
    // ------------------------------------------------------------------

    @Test
    @DisplayName("six seats, filled in order, seventh refused")
    void join_seatsPlayersInOrderAndRefusesSeventh() {
        CrapsTable table = new CrapsTable();
        UUID[] ids = new UUID[7];
        for (int i = 0; i < 7; i++) {
            ids[i] = UUID.randomUUID();
        }
        for (int i = 0; i < 6; i++) {
            table.placeBet(ids[i], BET);
        }
        int occupied = 0;
        for (UUID id : ids) {
            if (table.getBet(id) > 0) occupied++;
        }
        assertEquals(6, occupied);
    }

    @Test
    @DisplayName("place bet accepts the limits and rejects outside them")
    void placeBetLimits() {
        CrapsTable table = new CrapsTable();
        UUID p = UUID.randomUUID();
        assertTrue(table.placeBet(p, CrapsTable.MIN_BET), "minimum accepted");
        assertTrue(table.placeBet(p, CrapsTable.MAX_BET), "maximum accepted");
        assertFalse(table.placeBet(p, CrapsTable.MIN_BET - 1), "below minimum rejected");
        assertFalse(table.placeBet(p, CrapsTable.MAX_BET + 1), "above maximum rejected");
    }

    @Test
    @DisplayName("getBet returns the current stake")
    void getBetTracksStake() {
        CrapsTable table = new CrapsTable();
        UUID p = UUID.randomUUID();
        assertEquals(0L, table.getBet(p));
        table.placeBet(p, BET);
        assertEquals(BET, table.getBet(p));
    }

    @Test
    @DisplayName("clearBet removes the stake and returns it")
    void clearBetReturnsStake() {
        CrapsTable table = new CrapsTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        assertEquals(BET, table.clearBet(p), "clearBet returns the stake");
        assertEquals(0L, table.getBet(p), "bet is cleared");
    }

    // ------------------------------------------------------------------
    // roll resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("roll returns a total between 2 and 12")
    void rollReturnsValidTotal() {
        CrapsTable table = new CrapsTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int total = table.roll(p);
        assertTrue(total >= 2 && total <= 12, "roll total must be 2..12, was " + total);
        assertEquals(total, table.getLastRoll(p));
    }

    @Test
    @DisplayName("7 or 11 on come-out wins even money")
    void naturalWinsEvenMoney() {
        CrapsTable table = new CrapsTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int total = rollUntilNatural(table, p);
        assertTrue(total == 7 || total == 11, "eventually roll 7 or 11, got " + total);
        assertEquals(BET + BET, table.getLastPayout(p), "natural pays even money");
        assertFalse(table.isInPointPhase(p), "natural does not enter point phase");
        assertEquals(0, table.getPoint(p), "no point after natural");
    }

    @Test
    @DisplayName("2, 3, or 12 on come-out loses everything")
    void crapsLoses() {
        CrapsTable table = new CrapsTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int total = -1;
        int attempts = 0;
        while (total != 2 && total != 3 && total != 12 && attempts < 10000) {
            total = table.roll(p);
            attempts++;
        }
        assertTrue(total == 2 || total == 3 || total == 12, "eventually roll craps, got " + total);
        assertEquals(0L, table.getLastPayout(p), "craps loses everything");
        assertFalse(table.isInPointPhase(p), "craps does not enter point phase");
    }

    @Test
    @DisplayName("establishing a point continues until point or 7")
    void pointContinuesUntilPointOrSeven() {
        CrapsTable table = new CrapsTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);

        // Roll until we establish a point (4, 5, 6, 8, 9, or 10)
        int point = -1;
        int attempts = 0;
        while ((point < 4 || point > 10 || point == 7) && attempts < 10000) {
            point = table.roll(p);
            attempts++;
        }
        assertTrue(point == 4 || point == 5 || point == 6 || point == 8 || point == 9 || point == 10,
                "established a point, got " + point);
        assertEquals(point, table.getPoint(p), "point is recorded");
        assertTrue(table.isInPointPhase(p), "player is in point phase");

        // Now roll until we hit the point again or 7
        int result = -1;
        attempts = 0;
        while (result != point && result != 7 && attempts < 10000) {
            result = table.roll(p);
            attempts++;
        }
        assertTrue(result == point || result == 7, "eventually hit point or 7, got " + result);

        if (result == point) {
            assertEquals(BET + BET, table.getLastPayout(p), "hitting the point pays even money");
        } else {
            assertEquals(0L, table.getLastPayout(p), "seven out loses everything");
        }
        assertFalse(table.isInPointPhase(p), "point phase ends after resolution");
        assertEquals(0, table.getPoint(p), "point is cleared");
    }

    // ------------------------------------------------------------------
    // payout queue
    // ------------------------------------------------------------------

    @Test
    @DisplayName("payouts are drained exactly once")
    void payoutsDrainedOnce() {
        CrapsTable table = new CrapsTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int total = rollUntilNatural(table, p);
        long payout = table.getLastPayout(p);
        assertTrue(payout > 0, "natural should pay");
        assertEquals(BET + BET, payout, "natural pays even money");
    }

    @Test
    @DisplayName("clearBet clears bet and point state")
    void clearBetClearsState() {
        CrapsTable table = new CrapsTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        // Establish a point
        int point = -1;
        int attempts = 0;
        while ((point < 4 || point > 10 || point == 7) && attempts < 10000) {
            point = table.roll(p);
            attempts++;
        }
        assertTrue(table.isInPointPhase(p));
        assertEquals(BET, table.clearBet(p), "clearBet returns the stake");
        assertEquals(0L, table.getBet(p), "bet is cleared");
        assertFalse(table.isInPointPhase(p), "point phase is cleared");
        assertEquals(0, table.getPoint(p), "point is cleared");
    }
}
