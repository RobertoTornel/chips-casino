package dev.macasino.api;

/**
 * The house-edge arithmetic, isolated from Minecraft so it can be unit tested.
 *
 * <p>This is the balancing rule the whole economy rests on:
 * <ul>
 *   <li>selling an item to the house pays {@literal sellMultiplier}x of its base price</li>
 *   <li>buying an item from the house costs {@literal buyMultiplier}x of its base price</li>
 * </ul>
 *
 * <p>Both round <em>against</em> the player (sell rounds down, buy rounds up) and both
 * clamp to at least 1, so a cheap item can never become free and no trade can produce
 * money from nothing. Keeping this in a Minecraft-free class means the invariant is
 * covered by a plain JVM test rather than only being verified by reading the code.
 *
 * <p>Multipliers are read from {@link CasinoConfig} on each call, so server operators can
 * tune the house edge by editing {@code config/casino/casino.json} without restarting.
 */
public final class PricingRules {

    private PricingRules() {
    }

    /**
     * Fraction of base price paid when the player sells to the house.
     * Read from {@link CasinoConfig#sellMultiplier} on each call.
     */
    public static double sellMultiplier() {
        return CasinoConfig.instance().sellMultiplier;
    }

    /**
     * Fraction of base price charged when the player buys from the house.
     * Read from {@link CasinoConfig#buyMultiplier} on each call.
     */
    public static double buyMultiplier() {
        return CasinoConfig.instance().buyMultiplier;
    }

    /**
     * What the player receives for one item worth {@code basePrice}.
     *
     * @return the payout, or 0 when the base price is non-positive
     */
    public static long sell(long basePrice) {
        if (basePrice <= 0) {
            return 0L;
        }
        // Round down: the fractional remainder stays with the house.
        return Math.max(1L, (long) Math.floor(basePrice * sellMultiplier()));
    }

    /**
     * What the player pays for one item worth {@code basePrice}.
     *
     * @return the cost, or 0 when the base price is non-positive
     */
    public static long buy(long basePrice) {
        if (basePrice <= 0) {
            return 0L;
        }
        // Round up: the fractional remainder is charged to the player.
        return Math.max(1L, (long) Math.ceil(basePrice * buyMultiplier()));
    }

    /** {@link #sell(long)} scaled by a stack count. */
    public static long sell(long basePrice, int count) {
        if (basePrice <= 0 || count <= 0) {
            return 0L;
        }
        return sell(basePrice) * count;
    }

    /** {@link #buy(long)} scaled by a stack count. */
    public static long buy(long basePrice, int count) {
        if (basePrice <= 0 || count <= 0) {
            return 0L;
        }
        return buy(basePrice) * count;
    }
}
