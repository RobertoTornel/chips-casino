package dev.macasino.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the pachinko board.
 *
 * <p>The board shipped for a while returning <b>54x per 1x bet</b>: the ball's final
 * position was a free-floating random walk with a wide spread, and the jackpot pocket was
 * a +/-10px window in the middle of it, so 53% of balls hit the jackpot at 100x. Nothing
 * in the build could see that, because there were no tests and the engine has no
 * Minecraft dependencies.
 *
 * <p>These tests pin down the three properties that were wrong:
 * <ol>
 *   <li>the landing distribution is the binomial the peg field implies;</li>
 *   <li>the expected return is below 1.0, with a real house edge;</li>
 *   <li>the board cannot be beaten by any betting strategy the player can reach.</li>
 * </ol>
 */
class PachinkoEngineTest {

    private static final double TOL = 1e-9;

    // ------------------------------------------------------------------
    // Payout table and expected value
    // ------------------------------------------------------------------

    @Test
    @DisplayName("expected return is below 1.0, i.e. the house actually wins")
    void expectedReturnIsBelowOne() {
        double ev = PachinkoEngine.expectedReturn();
        assertTrue(ev < 1.0,
                "board returns " + ev + "x per bet - players would profit forever");
    }

    @Test
    @DisplayName("house edge is in a believable band (2%..25%)")
    void houseEdgeIsReasonable() {
        double edge = PachinkoEngine.houseEdge();
        assertTrue(edge > 0.02 && edge < 0.25,
                "house edge " + (edge * 100) + "% is outside the intended band");
    }

    @Test
    @DisplayName("payout table is symmetric so the board looks fair")
    void payoutTableIsSymmetric() {
        int[] m = PachinkoEngine.POCKET_MULTIPLIERS;
        assertEquals(PachinkoEngine.POCKETS, m.length);
        for (int i = 0; i < m.length; i++) {
            assertEquals(m[i], m[m.length - 1 - i],
                    "pocket " + i + " breaks left/right symmetry");
        }
    }

    @Test
    @DisplayName("no payout is negative or absurd")
    void payoutsAreSane() {
        for (int m : PachinkoEngine.POCKET_MULTIPLIERS) {
            assertTrue(m >= 0, "negative multiplier " + m);
            assertTrue(m <= 200, "multiplier " + m + " is too large to be credible");
        }
    }

    @Test
    @DisplayName("the most likely pocket does not itself return more than 1x")
    void peakPocketIsNotAPrinter() {
        // This is the exact bug that shipped: a generous multiplier on the binomial peak.
        double[] probs = PachinkoEngine.pocketProbabilities();
        int peak = 0;
        for (int i = 1; i < probs.length; i++) {
            if (probs[i] > probs[peak]) peak = i;
        }
        double peakContribution = probs[peak] * PachinkoEngine.POCKET_MULTIPLIERS[peak];
        assertTrue(peakContribution < 1.0,
                "the peak pocket alone contributes " + peakContribution + "x");
    }

    // ------------------------------------------------------------------
    // Distribution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("pocket probabilities sum to 1")
    void probabilitiesSumToOne() {
        double sum = 0;
        for (double p : PachinkoEngine.pocketProbabilities()) sum += p;
        assertEquals(1.0, sum, TOL);
    }

    @Test
    @DisplayName("landing distribution is binomial over the peg rows")
    void distributionIsBinomial() {
        double[] probs = PachinkoEngine.pocketProbabilities();

        // Binomial(10, 0.5) mapped onto 11 pockets: the classic 1,10,45,120,210,252,... /1024.
        long[] expectedCounts = {1, 10, 45, 120, 210, 252, 210, 120, 45, 10, 1};
        for (int i = 0; i < probs.length; i++) {
            assertEquals(expectedCounts[i] / 1024.0, probs[i], TOL,
                    "pocket " + i + " probability is not binomial");
        }
    }

    @Test
    @DisplayName("the centre pocket is the most likely landing")
    void centreIsMostLikely() {
        double[] probs = PachinkoEngine.pocketProbabilities();
        int centre = PachinkoEngine.POCKETS / 2;
        for (int i = 0; i < probs.length; i++) {
            if (i == centre) continue;
            assertTrue(probs[centre] > probs[i],
                    "pocket " + i + " is more likely than the centre");
        }
    }

    @Test
    @DisplayName("empirical simulation matches the analytic distribution")
    void simulationMatchesAnalytics() {
        // 200k drops: enough that the law of large numbers makes the comparison tight,
        // fast enough to keep the test suite snappy.
        int drops = 200_000;
        int[] counts = new int[PachinkoEngine.POCKETS];
        PachinkoEngine engine = new PachinkoEngine(new Random(20260101L));
        for (int i = 0; i < drops; i++) {
            counts[engine.dropBall(1).pocket]++;
        }

        double[] probs = PachinkoEngine.pocketProbabilities();
        for (int i = 0; i < counts.length; i++) {
            double observed = counts[i] / (double) drops;
            // 4 sigma on a binomial proportion; generous but still catches a wrong board.
            double sigma = Math.sqrt(probs[i] * (1 - probs[i]) / drops);
            double tolerance = Math.max(4 * sigma, 0.004);
            assertEquals(probs[i], observed, tolerance,
                    "pocket " + i + ": simulated " + observed + " vs expected " + probs[i]);
        }
    }

    @Test
    @DisplayName("a long simulation loses money for the player")
    void longRunPlayerLoses() {
        // Dusting off the expected value: actually play the board and check the balance.
        PachinkoEngine engine = new PachinkoEngine(new Random(7L));
        long unit = 100;
        long staked = 0;
        long returned = 0;
        for (int i = 0; i < 100_000; i++) {
            PachinkoEngine.BallDropResult r = engine.dropBall(unit);
            staked += unit;
            returned += r.payout;
        }
        double rtp = returned / (double) staked;
        assertTrue(rtp < 1.0,
                "100k drops returned " + rtp + "x - the board leaks money");
        assertEquals(PachinkoEngine.EXPECTED_RETURN, rtp, 0.03,
                "simulated return should track the analytic expected return");
    }

    // ------------------------------------------------------------------
    // Invariants of a single drop
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(longs = {1, 7, 100, 2_500, 1_000_000})
    @DisplayName("payout is exactly bet x multiplier")
    void payoutMatchesMultiplier(long bet) {
        PachinkoEngine engine = new PachinkoEngine(new Random(bet));
        PachinkoEngine.BallDropResult r = engine.dropBall(bet);
        assertEquals(bet * r.multiplier, r.payout);
        assertEquals(PachinkoEngine.multiplierForPocket(r.pocket), r.multiplier);
    }

    @Test
    @DisplayName("a zero bet never pays out")
    void zeroBetPaysNothing() {
        PachinkoEngine engine = new PachinkoEngine(new Random(1));
        for (int i = 0; i < 500; i++) {
            assertEquals(0, engine.dropBall(0).payout);
        }
    }

    @Test
    @DisplayName("a negative bet is rejected rather than paying out")
    void negativeBetRejected() {
        PachinkoEngine engine = new PachinkoEngine(new Random(1));
        assertThrows(IllegalArgumentException.class, () -> engine.dropBall(-1));
    }

    @Test
    @DisplayName("the ball always lands on the board and inside the trajectory")
    void trajectoryStaysOnBoard() {
        PachinkoEngine engine = new PachinkoEngine(new Random(99));
        for (int i = 0; i < 5_000; i++) {
            PachinkoEngine.BallDropResult r = engine.dropBall(10);
            assertTrue(r.pocket >= 0 && r.pocket < PachinkoEngine.POCKETS);
            // one point per row, plus the start point, plus the floor point
            assertEquals(PachinkoEngine.ROWS + 2, r.trajectory.size());
            for (int[] pt : r.trajectory) {
                assertTrue(pt[0] >= 0 && pt[0] <= PachinkoEngine.BOARD_WIDTH,
                        "ball left the board horizontally at x=" + pt[0]);
                assertTrue(pt[1] >= 0 && pt[1] <= PachinkoEngine.BOARD_HEIGHT,
                        "ball left the board vertically at y=" + pt[1]);
            }
        }
    }

    @Test
    @DisplayName("outcome class agrees with the multiplier actually paid")
    void outcomeAgreesWithMultiplier() {
        for (int pocket = 0; pocket < PachinkoEngine.POCKETS; pocket++) {
            PachinkoEngine.DropOutcome outcome = PachinkoEngine.outcomeForPocket(pocket);
            int mult = PachinkoEngine.multiplierForPocket(pocket);
            if (mult == 0) {
                assertEquals(PachinkoEngine.DropOutcome.LOST, outcome);
            } else {
                assertNotEquals(PachinkoEngine.DropOutcome.LOST, outcome);
                assertTrue(outcome.multiplier > 0);
            }
        }
    }

    @Test
    @DisplayName("out-of-range pockets are rejected, not silently paid")
    void outOfRangePocketRejected() {
        assertThrows(IllegalArgumentException.class, () -> PachinkoEngine.multiplierForPocket(-1));
        assertThrows(IllegalArgumentException.class,
                () -> PachinkoEngine.multiplierForPocket(PachinkoEngine.POCKETS));
    }

    @Test
    @DisplayName("every pocket is reachable")
    void everyPocketIsReachable() {
        // A pocket that can never be hit is a dead payout slot: the board would show a
        // prize that does not exist.
        PachinkoEngine engine = new PachinkoEngine(new Random(4242));
        boolean[] seen = new boolean[PachinkoEngine.POCKETS];
        for (int i = 0; i < 200_000; i++) {
            seen[engine.dropBall(1).pocket] = true;
        }
        for (int i = 0; i < seen.length; i++) {
            assertTrue(seen[i], "pocket " + i + " was never reached");
        }
    }

    @Test
    @DisplayName("the engine is deterministic for a fixed seed")
    void deterministicWithSeed() {
        PachinkoEngine a = new PachinkoEngine(new Random(123));
        PachinkoEngine b = new PachinkoEngine(new Random(123));
        for (int i = 0; i < 1_000; i++) {
            PachinkoEngine.BallDropResult ra = a.dropBall(5);
            PachinkoEngine.BallDropResult rb = b.dropBall(5);
            assertEquals(ra.pocket, rb.pocket);
            assertEquals(ra.payout, rb.payout);
        }
    }
}
