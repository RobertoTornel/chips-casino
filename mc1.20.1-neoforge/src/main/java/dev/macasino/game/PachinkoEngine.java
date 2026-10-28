package dev.macasino.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pachinko: a ball falls through a Galton-board peg field and lands in one of several
 * payout pockets.
 *
 * <h2>Why the physics is a random walk and not a sum of "bounces"</h2>
 *
 * The first version of this class advanced the ball with a running total of random
 * deflections and clamped it to the walls. That behaves nothing like a real peg board:
 * the deflection accumulated indefinitely, so the final x was little more than one draw
 * from a very wide distribution. Combined with a jackpot window of +/-10px against a
 * spread of +/-13px, 53% of all balls hit the jackpot, and the board returned
 * <b>54x per 1x bet</b> - a money printer rather than a casino game.
 *
 * A real peg field is a Galton board. Each row, the ball meets a peg and goes left or
 * right with equal probability. After N rows the landing column is a binomial
 * distribution centred on the drop point, and the *variance is a property of the board*
 * (number of rows) rather than of a free-floating multiplier. That is what this class
 * models: one discrete left/right decision per peg row, so the outcome distribution is
 * exactly binomial and can be reasoned about, tested, and tuned.
 *
 * <h2>Payouts and house edge</h2>
 *
 * Pocket multipliers are chosen so the expected return sits below 1.0 (a real house
 * edge). The two centre pockets are the jackpot, and because the binomial peak is there
 * they are deliberately given a *small* multiplier - a big multiplier in the centre is
 * precisely the bug that made the old board unprofitable. Value lives in the rare tails.
 *
 * The expected return is asserted by {@code PachinkoEngineTest}; see {@link #EXPECTED_RETURN}.
 */
public class PachinkoEngine {

    /** Board geometry in logical units. The client scales these to the drawn board. */
    public static final int BOARD_WIDTH = 180;
    public static final int BOARD_HEIGHT = 160;

    /** Peg rows the ball falls through. Each row is one left/right decision. */
    public static final int ROWS = 10;

    /**
     * Pockets across the board floor. Must be odd so there is a unique centre pocket and
     * a symmetric left/right layout. 11 pockets = 10 rows: the board is the classic
     * Galton triangle, so the centre pocket is the most likely landing.
     */
    public static final int POCKETS = 11;

    /** Pins drawn per row (visual only; the model uses ROWS left/right decisions). */
    public static final int PINS_PER_ROW = POCKETS;

    /**
     * Payout multipliers, left to right. Symmetric so the board looks fair and the
     * client can mirror the layout.
     *
     * <p><b>The centre pocket is the most likely landing.</b> Over 10 rows the landing
     * counts are 1-10-45-120-210-<b>252</b>-210-120-45-10-1 out of 1024, so pocket 5
     * receives 24.6% of balls. A generous multiplier there is self-defeating: 5x on the
     * centre alone returns 1.23x and the whole board loses money no matter what the edges
     * pay. The centre therefore pays the *smallest* positive amount and the value is
     * carried by the tails, which is how a real board is tuned.
     *
     * <p>Expected return with this table is 0.891 (a ~10.9% house edge), asserted by
     * {@code PachinkoEngineTest}. Do not raise a centre multiplier without re-running it.
     */
    public static final int[] POCKET_MULTIPLIERS = {
            60, 6, 2, 1, 0, 1, 0, 1, 2, 6, 60
    };

    /**
     * Outcome class for display. The multiplier here is the *headline* figure shown to the
     * player; the money actually paid comes from {@link #POCKET_MULTIPLIERS} via the
     * landing pocket. Do not pay out from this enum.
     */
    public enum DropOutcome {
        LOST(0, "Out Gutter"),
        REGULAR_PIN(2, "Pin Pocket"),
        TULIP_GATE(6, "Tulip Gate"),
        JACKPOT(60, "FEVER JACKPOT!");

        public final int multiplier;
        public final String label;

        DropOutcome(int multiplier, String label) {
            this.multiplier = multiplier;
            this.label = label;
        }
    }

    public static class BallDropResult {
        /** Series of (x, y) points for the client to plot, one per peg row. */
        public final List<int[]> trajectory;
        public final DropOutcome outcome;
        /** Which pocket the ball landed in, 0..POCKETS-1. */
        public final int pocket;
        public final int multiplier;
        public final long payout;

        public BallDropResult(List<int[]> trajectory, DropOutcome outcome, int pocket,
                              int multiplier, long payout) {
            this.trajectory = trajectory;
            this.outcome = outcome;
            this.pocket = pocket;
            this.multiplier = multiplier;
            this.payout = payout;
        }
    }

    private final Random random;

    public PachinkoEngine() {
        this(new Random());
    }

    /** Injectable RNG so tests can drive the board deterministically. */
    public PachinkoEngine(Random random) {
        this.random = random;
    }

    /**
     * Multiplier for a landing pocket. Pocket 0 is the far-left edge.
     */
    public static int multiplierForPocket(int pocket) {
        if (pocket < 0 || pocket >= POCKET_MULTIPLIERS.length) {
            throw new IllegalArgumentException("pocket out of range: " + pocket);
        }
        return POCKET_MULTIPLIERS[pocket];
    }

    /** Human-readable outcome class for a pocket, used by the UI and the tooltip. */
    public static DropOutcome outcomeForPocket(int pocket) {
        int m = multiplierForPocket(pocket);
        if (m == 0) return DropOutcome.LOST;
        if (m >= 20) return DropOutcome.JACKPOT;
        if (m >= 5) return DropOutcome.TULIP_GATE;
        return DropOutcome.REGULAR_PIN;
    }

    /**
     * Drop one ball. The ball starts at the centre column of the top row and makes one
     * left/right decision per peg row, then lands in the resulting pocket.
     */
    public BallDropResult dropBall(long betPerBall) {
        if (betPerBall < 0) {
            throw new IllegalArgumentException("bet must not be negative");
        }

        List<int[]> path = new ArrayList<>();

        // Galton board: start centred, then one binary decision per row.
        int position = 0;                  // signed offset from centre, in columns
        float x = BOARD_WIDTH / 2f;
        float y = 10f;
        path.add(new int[]{(int) x, (int) y});

        float rowStep = (float) (BOARD_HEIGHT - 30) / ROWS;
        for (int row = 0; row < ROWS; row++) {
            // One peg, one decision: exactly 50/50, no accumulated drift.
            position += random.nextBoolean() ? 1 : -1;

            x = xForOffset(position);
            y += rowStep;
            path.add(new int[]{(int) x, (int) y});
        }

        // Map the signed offset (-ROWS..+ROWS) onto a pocket index (0..POCKETS-1).
        int centre = POCKETS / 2;
        int pocket = centre + (position / 2);
        // Guard: the walk cannot exceed ROWS steps, and ROWS == POCKETS-1, so the
        // mirrored walk lands inside the floor. Clamp only as a defensive invariant.
        if (pocket < 0) pocket = 0;
        if (pocket >= POCKETS) pocket = POCKETS - 1;

        int multiplier = multiplierForPocket(pocket);
        DropOutcome outcome = outcomeForPocket(pocket);

        // Add the floor point so the client can draw the ball sitting in its pocket.
        float floorX = xForOffset(position);
        path.add(new int[]{(int) floorX, BOARD_HEIGHT});

        long payout = (long) betPerBall * multiplier;
        return new BallDropResult(path, outcome, pocket, multiplier, payout);
    }

    /**
     * Horizontal distance between adjacent pockets, in board units.
     *
     * <p>The walk offset spans -ROWS..+ROWS (one unit per row), and POCKETS-1 == ROWS, so
     * one unit of offset is <b>half</b> a pocket: the full excursion of the walk then
     * covers exactly the outer pockets. Getting this wrong (a full pocket per unit) sent
     * the ball to x=-60..240 on a 180-wide board, i.e. visibly off the cabinet.
     */
    public static float columnWidth() {
        return (BOARD_WIDTH - 30f) / (float) (POCKETS - 1);
    }

    /** Board-x for a signed walk offset in [-ROWS, +ROWS]. */
    private static float xForOffset(int offset) {
        return BOARD_WIDTH / 2f + offset * (columnWidth() / 2f);
    }

    /** X pixel (0..BOARD_WIDTH) of the centre of a pocket, for drawing. */
    public static float pocketCentreX(int pocket) {
        return BOARD_WIDTH / 2f + (pocket - POCKETS / 2) * columnWidth();
    }

    // ---------------------------------------------------------------------
    // Expected value - the number that must stay below 1.0
    // ---------------------------------------------------------------------

    /**
     * Probability of landing in each pocket, as an exact binomial over {@link #ROWS}
     * coin flips mapped through the same arithmetic {@link #dropBall} uses.
     *
     * <p>Kept in the main source set (not the test set) because the UI shows the board's
     * return to the player, and because keeping it next to the physics makes it obvious
     * when the two drift apart.
     */
    public static double[] pocketProbabilities() {
        // Binomial coefficients for ROWS coin flips.
        long[] binom = new long[ROWS + 1];
        binom[0] = 1;
        for (int i = 1; i <= ROWS; i++) {
            for (int j = i; j >= 1; j--) {
                binom[j] += binom[j - 1];
            }
        }
        long total = 1L << ROWS;

        double[] probs = new double[POCKETS];
        int centre = POCKETS / 2;
        for (int k = 0; k <= ROWS; k++) {
            // k = number of "+1" decisions; position = k - (ROWS - k) = 2k - ROWS
            int position = 2 * k - ROWS;
            int pocket = centre + position / 2;
            if (pocket < 0) pocket = 0;
            if (pocket >= POCKETS) pocket = POCKETS - 1;
            probs[pocket] += (double) binom[k] / total;
        }
        return probs;
    }

    /** Expected return per unit bet. Below 1.0 means the house profits. */
    public static double expectedReturn() {
        double[] probs = pocketProbabilities();
        double ev = 0;
        for (int i = 0; i < POCKETS; i++) {
            ev += probs[i] * POCKET_MULTIPLIERS[i];
        }
        return ev;
    }

    /** Documented expected return, asserted by the test suite. */
    public static final double EXPECTED_RETURN = expectedReturn();

    /** House edge as a fraction (0.10 == 10%). */
    public static double houseEdge() {
        return 1.0 - expectedReturn();
    }
}
