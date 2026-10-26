package dev.casino.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pinball: the player fires a ball, works the flippers, and scores by hitting targets.
 *
 * <h2>Why this is not just "pachinko with different numbers"</h2>
 *
 * Pachinko is a game of pure chance: the player chooses a bet, lets go, and a Galton
 * board decides. Pinball is the opposite - it is a game of <b>skill with a house edge</b>.
 * The player's flipper timing genuinely changes the outcome, so the engine models the
 * ball's journey rather than drawing from a distribution.
 *
 * <p>The tension is that a purely skilled game has no house edge, and a purely random one
 * has no skill. This engine sits in between: each flipper hit is resolved as a skill check
 * whose success chance is driven by the player's timing input, and the drain rate is
 * tuned so that even a perfect player converges to the configured return-to-player.
 *
 * <h2>Model</h2>
 *
 * <ol>
 *   <li>The player launches a ball and pays the bet.</li>
 *   <li>Each {@link #tick} advances the ball; the player supplies a flipper input.</li>
 *   <li>A well-timed flip keeps the ball alive and scores the bumper row; a missed flip
 *       drains the ball down the outlane.</li>
 *   <li>When the ball drains, the run ends and the score is paid out.</li>
 * </ol>
 *
 * The expected return is asserted by {@code PinballEngineTest}.
 */
public class PinballEngine {

    /** Points a bumper is worth, in stake units (score / this = multiplier). */
    public static final int BUMPER_VALUE = 1;

    /** Score units per 1x of stake. */
    public static final int SCORE_PER_UNIT = 10;

    /** Number of bumpers a ball can strike before draining. */
    public static final int BUMPERS = 8;

    /**
     * Base chance that a flipper input saves the ball. The player's timing input scales
     * this: a perfect input gets {@link #MAX_SKILL_BONUS}, a mistimed one gets nothing.
     */
    public static final double BASE_SAVE_CHANCE = 0.42;

    /** Extra save chance granted by a perfectly timed flip. */
    public static final double MAX_SKILL_BONUS = 0.30;

    /**
     * The return-to-player the board is tuned to, including the skill term. A perfect
     * player approaches this; a careless one falls well below it. Kept under 1.0 so the
     * machine still earns even against someone with good timing.
     */
    public static final double TARGET_RTP = 0.92;

    public enum BallState {
        READY, PLAYING, DRAINED
    }

    /** One completed ball, as reported to the UI. */
    public static class BallResult {
        public final int bumpersHit;
        public final int drains;
        public final long score;
        public final long payout;
        public final double rtp;

        public BallResult(int bumpersHit, int drains, long score, long payout, double rtp) {
            this.bumpersHit = bumpersHit;
            this.drains = drains;
            this.score = score;
            this.payout = payout;
            this.rtp = rtp;
        }
    }

    private final Random random;

    private BallState state = BallState.READY;
    private long bet;
    private int bounce;
    private int bumpersHit;
    private int saves;
    private int drains;
    private long score;

    public PinballEngine() {
        this(new Random());
    }

    /** Injectable RNG so tests are deterministic. */
    public PinballEngine(Random random) {
        this.random = random;
    }

    public BallState state() {
        return state;
    }

    public long bet() {
        return bet;
    }

    /** Current ball position index along the playfield, 0..BUMPERS. */
    public int bounce() {
        return bounce;
    }

    public int bumpersHit() {
        return bumpersHit;
    }

    public long score() {
        return score;
    }

    /** Launch a ball. The stake is taken by the caller. */
    public void launch(long bet) {
        if (bet <= 0) {
            throw new IllegalArgumentException("bet must be positive");
        }
        if (state == BallState.PLAYING) {
            throw new IllegalStateException("ball already in play");
        }
        this.bet = bet;
        this.state = BallState.PLAYING;
        this.bounce = 0;
        this.bumpersHit = 0;
        this.saves = 0;
        this.drains = 0;
        this.score = 0;
    }

    /**
     * Advance the ball one step.
     *
     * @param flipperPower how well the player timed the flip, 0.0 (mistimed) .. 1.0
     *                     (perfect). Clamped into range.
     * @return true if the ball is still in play after this step
     */
    public boolean tick(double flipperPower) {
        if (state != BallState.PLAYING) {
            return false;
        }

        double power = Math.max(0.0, Math.min(1.0, flipperPower));
        double saveChance = BASE_SAVE_CHANCE + MAX_SKILL_BONUS * power;

        // The ball is heading for the drain; the flipper decides whether it is saved.
        if (random.nextDouble() < saveChance) {
            saves++;
            bumpersHit++;
            score += (long) BUMPER_VALUE * SCORE_PER_UNIT;
            bounce++;
            if (bounce >= BUMPERS) {
                // Reached the end of the bumper row: the ball rolls back, no drain yet.
                bounce = BUMPERS - 1;
            }
            return true;
        }

        // Missed the flip: the ball drains and the run ends.
        drains++;
        state = BallState.DRAINED;
        return false;
    }

    /** Finish the ball and convert the score into a payout. */
    public BallResult finish() {
        if (state == BallState.PLAYING) {
            state = BallState.DRAINED;
        }
        long payout = scoreToPayout(score, bet);
        double rtp = bet > 0 ? payout / (double) bet : 0;
        return new BallResult(bumpersHit, drains, score, payout, rtp);
    }

    /**
     * Convert a score into money. The score is in arbitrary points; this fixes the
     * exchange rate so that the payout cannot exceed the tuned return-to-player.
     */
    public static long scoreToPayout(long score, long bet) {
        if (score <= 0 || bet <= 0) return 0;
        // score = bumpers * SCORE_PER_UNIT, and one bumper pays perBumper, so the
        // conversion divides by SCORE_PER_UNIT exactly once.
        double perBumper = perBumperPayout(bet);
        long payout = Math.round(score / (double) SCORE_PER_UNIT * perBumper);
        return Math.max(0, payout);
    }

    /**
     * Money paid for one bumper at the given stake.
     *
     * <p>Calibrated so that the <b>best reachable</b> play returns {@link #TARGET_RTP}: the
     * geometric mean bumper count at full skill is {@code p/(1-p)} capped at
     * {@link #BUMPERS}, and dividing the target by that gives the per-bumper value. Tuning
     * against the skilled ceiling (rather than the average) is what keeps the machine
     * profitable even against a player with perfect timing.
     */
    public static double perBumperPayout(long bet) {
        double bestBumpers = Math.min(
                (BASE_SAVE_CHANCE + MAX_SKILL_BONUS) / (1 - (BASE_SAVE_CHANCE + MAX_SKILL_BONUS)),
                BUMPERS);
        return (double) bet * TARGET_RTP / bestBumpers;
    }

    // ------------------------------------------------------------------
    // Expected value
    // ------------------------------------------------------------------

    /**
     * Expected payout per unit staked for a player with the given average timing skill.
     *
     * <p>The run is geometric: each tick either scores a bumper or drains, so the expected
     * number of bumpers for a per-tick save chance {@code p} is {@code p / (1 - p)}. The
     * expected score is that count times {@link #SCORE_PER_UNIT}, and payouts convert
     * through {@link #scoreToPayout}.
     *
     * @param skill average flipper power in 0..1
     */
    public static double expectedReturn(double skill) {
        double power = Math.max(0.0, Math.min(1.0, skill));
        double p = BASE_SAVE_CHANCE + MAX_SKILL_BONUS * power;

        // Cap the geometric mean so a player with a very high save chance cannot diverge
        // past the bumper cap: a real ball is physically limited to the bumper row.
        double meanBumpers = Math.min(p / (1 - p), BUMPERS);

        double perBumper = perBumperPayout(1);
        return meanBumpers * perBumper;
    }

    /** Expected return for a player with perfect timing. */
    public static double expectedReturnSkilled() {
        return expectedReturn(1.0);
    }

    /** Expected return for a player who never times a flip. */
    public static double expectedReturnUnskilled() {
        return expectedReturn(0.0);
    }

    /**
     * Iterate the payout scale so that a near-perfect player returns {@link #TARGET_RTP}
     * rather than the raw geometric overshoot. Used to document/tune the board.
     */
    public static List<Double> rtpCurve() {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            out.add(expectedReturn(i / 10.0));
        }
        return out;
    }
}
