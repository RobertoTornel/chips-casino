package dev.macasino.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the house-edge arithmetic.
 *
 * <p>The economy is built on a spread: the house buys at 0.8x and sells at 1.2x, so every
 * complete round trip costs the player 40% of the item's base value. If either multiplier
 * is ever changed to make the spread non-positive, a player could buy and immediately sell
 * for a profit and the whole mod's balance would collapse — so that is asserted here rather
 * than left to inspection.
 */
class PricingRulesTest {

    @Test
    @DisplayName("selling pays 0.8x of base, rounded down")
    void sellAppliesTheDiscount() {
        assertEquals(80L, PricingRules.sell(100L));
        assertEquals(8000L, PricingRules.sell(10000L));
    }

    @Test
    @DisplayName("buying costs 1.2x of base, rounded up")
    void buyAppliesTheMarkup() {
        assertEquals(120L, PricingRules.buy(100L));
        assertEquals(12000L, PricingRules.buy(10000L));
    }

    @Test
    @DisplayName("the spread is always positive, so a round trip can never profit")
    void roundTripAlwaysLoses() {
        // The single most important property of the economy: buy then sell must lose money.
        for (long base : new long[]{1, 2, 3, 7, 10, 64, 100, 1000, 8192, 65536, 1_000_000}) {
            long cost = PricingRules.buy(base);
            long refund = PricingRules.sell(base);
            assertTrue(refund < cost,
                    "base " + base + ": selling for " + refund
                            + " must be strictly less than buying for " + cost);
        }
    }

    @Test
    @DisplayName("an item worth 1 still costs something, so nothing is free")
    void cheapItemsStillHaveAFloorOfOne() {
        // floor(1 * 0.8) == 0 would make a single-value item worthless to sell, so the
        // sell side clamps up to 1 (the floor is in the player's favour here, but only at
        // the very bottom of the table where a Chips either way is immaterial).
        assertEquals(1L, PricingRules.sell(1L));
        // The buy side needs no clamp at 1: ceil(1 * 1.2) == 2 already.
        assertEquals(2L, PricingRules.buy(1L));
        // The floor never inverts the spread, even here: 2 in, 1 out.
        assertTrue(PricingRules.sell(1L) < PricingRules.buy(1L));
    }

    @Test
    @DisplayName("non-positive base prices yield nothing rather than negative money")
    void nonPositiveBasePricesAreRejected() {
        assertEquals(0L, PricingRules.sell(0L));
        assertEquals(0L, PricingRules.buy(0L));
        assertEquals(0L, PricingRules.sell(-500L));
        assertEquals(0L, PricingRules.buy(-500L));
    }

    @Test
    @DisplayName("stack counts scale the price linearly")
    void stackCountsScaleLinearly() {
        assertEquals(80L * 64, PricingRules.sell(100L, 64));
        assertEquals(120L * 16, PricingRules.buy(100L, 16));
        // A zero or negative count is a no-op, not a negative price.
        assertEquals(0L, PricingRules.sell(100L, 0));
        assertEquals(0L, PricingRules.buy(100L, -3));
    }

    @Test
    @DisplayName("multipliers are the documented 0.8 and 1.2")
    void multipliersMatchTheDesign() {
        assertEquals(0.8, PricingRules.sellMultiplier(), 1e-9);
        assertEquals(1.2, PricingRules.buyMultiplier(), 1e-9);
    }

    @Test
    @DisplayName("a diamond is worth 8192 base: sells for 6553, buys for 9831")
    void diamondIsTheWorkedExample() {
        // A concrete case documented in ECONOMY.md; if this drifts, the docs are wrong too.
        assertEquals(6553L, PricingRules.sell(8192L));
        assertEquals(9831L, PricingRules.buy(8192L));
    }

    @Test
    @DisplayName("large base prices do not overflow")
    void largePricesDoNotOverflow() {
        long huge = 1_000_000_000L;
        long sell = PricingRules.sell(huge);
        long buy = PricingRules.buy(huge);
        assertTrue(sell > 0, "sell of huge price should be positive");
        assertTrue(buy > sell, "buy of huge price should exceed sell");
        assertTrue(sell < huge, "sell should be less than base");
        assertTrue(buy > huge, "buy should exceed base");
    }

    @Test
    @DisplayName("maximum long base price is handled safely")
    void maxLongBasePriceIsSafe() {
        long max = Long.MAX_VALUE;
        long sell = PricingRules.sell(max);
        long buy = PricingRules.buy(max);
        assertTrue(sell > 0, "sell of max long should be positive");
        assertTrue(buy > sell, "buy of max long should exceed sell");
        assertTrue(sell < max || sell == max, "sell of max long should not exceed max");
    }

    @Test
    @DisplayName("round trip loss is at least 1 Chips at every base value")
    void roundTripAlwaysLosesAtLeastOneChip() {
        for (long base : new long[]{1, 2, 3, 7, 10, 64, 100, 1000, 8192, 65536, 1_000_000}) {
            long cost = PricingRules.buy(base);
            long refund = PricingRules.sell(base);
            assertTrue(cost - refund >= 1,
                    "base " + base + ": round trip must lose at least 1 Chips "
                            + "(cost=" + cost + ", refund=" + refund + ")");
        }
    }
}
