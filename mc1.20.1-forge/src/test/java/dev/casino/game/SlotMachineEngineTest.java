package dev.casino.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the slot RTP calibration.
 *
 * <p>The house edge must stay positive: a player who spins forever must lose money on
 * average. We simulate a large number of spins and assert the return-to-player is in a
 * believable band (82%..96%). If weights or payouts are ever edited, this test catches a
 * player-positive edge before it ships.
 */
class SlotMachineEngineTest {

    private static final long BET = 100L;
    private static final int SPINS = 200_000;

    @Test
    @DisplayName("RTP is in the house-edge band (never player-positive)")
    void rtpIsInBand() {
        long totalIn = 0L;
        long totalOut = 0L;
        for (long i = 0; i < SPINS; i++) {
            SlotMachineEngine.Result res = SlotMachineEngine.spin(BET, i);
            totalIn += BET;
            totalOut += res.payout;
        }
        double rtp = (double) totalOut / totalIn;
        assertTrue(rtp > 0.80, "RTP too low: " + rtp);
        assertTrue(rtp < 0.96, "RTP too high (house edge vanished): " + rtp);
    }

    @Test
    @DisplayName("every symbol can hit 3-of-a-kind")
    void allSymbolsPay() {
        for (int s = 0; s < SlotMachineEngine.Symbol.values().length; s++) {
            boolean saw = false;
            for (long i = 0; i < 300_000 && !saw; i++) {
                SlotMachineEngine.Result res = SlotMachineEngine.spin(BET, i * 7 + s);
                if (res.visible[0][1] == s && res.visible[1][1] == s && res.visible[2][1] == s) {
                    saw = true;
                }
            }
            assertTrue(saw, "symbol " + s + " never landed 3-of-a-kind");
        }
    }

    @Test
    @DisplayName("two left-aligned cherries pay 2x without a third cherry")
    void twoCherriesPay() {
        // Force a strip layout that yields C-C-? on the centre row by brute force:
        // scan seeds until the centre row is CHERRY-CHERRY-!CHERRY.
        boolean found = false;
        for (long i = 0; i < 500_000 && !found; i++) {
            SlotMachineEngine.Result res = SlotMachineEngine.spin(BET, i);
            if (res.visible[0][1] == SlotMachineEngine.Symbol.CHERRY.ordinal()
                    && res.visible[1][1] == SlotMachineEngine.Symbol.CHERRY.ordinal()
                    && res.visible[2][1] != SlotMachineEngine.Symbol.CHERRY.ordinal()) {
                assertEquals(2L * BET, res.payout);
                found = true;
            }
        }
        assertTrue(found, "no C-C-!C centre row found in scan");
    }

    @Test
    @DisplayName("a zero or negative bet is rejected")
    void nonPositiveBetRejected() {
        assertThrows(() -> SlotMachineEngine.spin(0L, 1L));
        assertThrows(() -> SlotMachineEngine.spin(-100L, 1L));
    }

    @Test
    @DisplayName("the same seed yields the same result (deterministic)")
    void deterministic() {
        SlotMachineEngine.Result a = SlotMachineEngine.spin(BET, 424242L);
        SlotMachineEngine.Result b = SlotMachineEngine.spin(BET, 424242L);
        assertEquals(a.payout, b.payout);
        assertEquals(a.visible[0][0], b.visible[0][0]);
    }

    private static void assertThrows(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }
}
