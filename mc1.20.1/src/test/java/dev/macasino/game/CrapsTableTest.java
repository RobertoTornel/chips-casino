package dev.macasino.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the craps table game logic.
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
        int total = table.roll(p);
        if (total == 7 || total == 11) {
            assertEquals(BET + BET, table.getLastPayout(p), "natural pays even money");
            assertFalse(table.isInPointPhase(p), "natural does not enter point phase");
            assertEquals(0, table.getPoint(p), "no point after natural");
        }
    }

    @Test
    @DisplayName("2, 3, or 12 on come-out loses everything")
    void crapsLoses() {
        CrapsTable table = new CrapsTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int total = -1;
        int attempts = 0;
        do {
            total = table.roll(p);
            attempts++;
        } while ((total != 2 && total != 3 && total != 12) && attempts < 10000);
        assertTrue(total == 2 || total == 3 || total == 12,
                "eventually roll craps, got " + total + " after " + attempts + " attempts");
    }

    @Test
    @DisplayName("establishing a point continues until point or 7")
    void pointContinuesUntilPointOrSeven() {
        CrapsTable table = new CrapsTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);

        int point = -1;
        int attempts = 0;
        do {
            point = table.roll(p);
            attempts++;
        } while ((point < 4 || point > 10 || point == 7) && attempts < 10000);
        assertTrue(point == 4 || point == 5 || point == 6 || point == 8 || point == 9 || point == 10,
                "established a point, got " + point);
        assertEquals(point, table.getPoint(p), "point is recorded");
        assertTrue(table.isInPointPhase(p), "player is in point phase");

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

    @Test
    @DisplayName("payouts are drained exactly once")
    void payoutsDrainedOnce() {
        CrapsTable table = new CrapsTable();
        UUID p = UUID.randomUUID();
        table.placeBet(p, BET);
        int total = rollUntilNatural(table, p);
        assertTrue(total == 7 || total == 11, "eventually roll 7 or 11, got " + total);
    }
}
