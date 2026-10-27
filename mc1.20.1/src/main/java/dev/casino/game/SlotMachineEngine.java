package dev.casino.game;

import java.util.Random;

/**
 * Pure slot-machine math: weighted reels, stop positions, payout table.
 *
 * <p>No Minecraft types — entirely JVM-testable. The server owns the seed and the bet,
 * the client only ever sends a bet and a spin request.
 *
 * <p>Reel model:
 * <ul>
 *   <li>3 reels, each a 24-position strip drawn from the weighted symbol pool.</li>
 *   <li>A spin picks one stop position per reel; the visible 3x3 window is
 *       {@code strip[(stop + row) % 24]} for row 0..2.</li>
 *   <li>The centre row ({@code row == 1}) determines the payout.</li>
 * </ul>
 *
 * <p>Symbols, rarest first:
 * <table>
 *   <tr><td>Symbol</td><td>Weight</td><td>3-of-a-kind pays</td></tr>
 *   <tr><td>SEVEN</td><td>5</td><td>100x bet</td></tr>
 *   <tr><td>BAR</td><td>10</td><td>50x bet</td></tr>
 *   <tr><td>BELL</td><td>20</td><td>20x bet</td></tr>
 *   <tr><td>LEMON</td><td>25</td><td>10x bet</td></tr>
 *   <tr><td>CHERRY</td><td>40</td><td>5x bet; two left-aligned cherries pay 2x</td></tr>
 * </table>
 *
 * <p>Calibrated house edge: ~11% (RTP ~89%), in the same band as Pachinko (10.9%).
 */
public final class SlotMachineEngine {

    /** Symbol ordinals, matched to the sprite sheet order. */
    public enum Symbol { CHERRY, LEMON, BELL, BAR, SEVEN }

    /** Strip length per reel. */
    private static final int STRIP_LEN = 24;

    /** Symbol weights (sum = 100). Index = Symbol.ordinal(). */
    private static final int[] WEIGHTS = {40, 25, 20, 10, 5};

    /** 3-of-a-kind multiplier, index = Symbol.ordinal(). */
    private static final long[] PAYOUT_3 = {5, 10, 20, 50, 100};

    /** Two left-aligned CHERRYs pay this multiplier. */
    private static final long PAYOUT_2_CHERRY = 2;

    /** Result of one spin. {@code visible[reel][row]} holds symbol ordinals. */
    public static final class Result {
        public final int[][] visible; // [3][3]
        public final int[] stop;      // [3] stop position per reel
        public final long bet;
        public final long payout;
        public final boolean won;

        Result(int[][] visible, int[] stop, long bet, long payout) {
            this.visible = visible;
            this.stop = stop;
            this.bet = bet;
            this.payout = payout;
            this.won = payout > 0;
        }
    }

    public SlotMachineEngine() {
    }

    /**
     * Spin the reels deterministically for {@code bet} chips using {@code seed}.
     *
     * <p>The seed is server-generated so the client cannot influence the outcome.
     */
    public static Result spin(long bet, long seed) {
        if (bet <= 0) {
            throw new IllegalArgumentException("bet must be positive");
        }
        Random rng = new Random(seed);

        // ---- generate the three reel strips from the weighted pool -------------
        int[][] strips = new int[3][STRIP_LEN];
        for (int r = 0; r < 3; r++) {
            for (int i = 0; i < STRIP_LEN; i++) {
                strips[r][i] = weightedSymbol(rng);
            }
        }

        // ---- pick one stop per reel -------------------------------------------
        int[] stop = new int[3];
        for (int r = 0; r < 3; r++) {
            stop[r] = rng.nextInt(STRIP_LEN);
        }

        // ---- visible 3x3 window ------------------------------------------------
        int[][] visible = new int[3][3];
        for (int r = 0; r < 3; r++) {
            for (int row = 0; row < 3; row++) {
                visible[r][row] = strips[r][(stop[r] + row) % STRIP_LEN];
            }
        }

        // ---- payout from the centre row ----------------------------------------
        long payout = calcPayout(visible[0][1], visible[1][1], visible[2][1], bet);
        return new Result(visible, stop, bet, payout);
    }

    /** Pick one symbol from the weighted pool. */
    private static int weightedSymbol(Random rng) {
        int roll = rng.nextInt(100);
        int acc = 0;
        for (int s = 0; s < WEIGHTS.length; s++) {
            acc += WEIGHTS[s];
            if (roll < acc) {
                return s;
            }
        }
        return Symbol.SEVEN.ordinal();
    }

    /** Payout for one centre row. */
    private static long calcPayout(int a, int b, int c, long bet) {
        if (a == b && b == c) {
            return PAYOUT_3[a] * bet;
        }
        // two left-aligned cherries (three-of-a-kind already handled above)
        if (a == Symbol.CHERRY.ordinal() && b == Symbol.CHERRY.ordinal()) {
            return PAYOUT_2_CHERRY * bet;
        }
        return 0;
    }

    /** Symbol name for debug / lang. */
    public static String name(int ordinal) {
        return Symbol.values()[ordinal].name();
    }
}
