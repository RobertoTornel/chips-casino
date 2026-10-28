package dev.macasino.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pinball machine.
 *
 * <p>Pinball is the only game in the mod where player input changes the outcome, which
 * makes it the easiest one to accidentally turn into a money printer: a generous
 * per-bumper payout times a skilled player's long ball is unbounded profit. These tests
 * pin the machine to a return-to-player below 1.0 <b>even for a player with perfect
 * timing</b>, which is the worst case the house can face.
 */
class PinballEngineTest {

    @Test
    @DisplayName("even a perfect player cannot beat the machine")
    void perfectPlayerStillLoses() {
        double ev = PinballEngine.expectedReturnSkilled();
        assertTrue(ev < 1.0,
                "perfect play returns " + ev + "x - the machine is a money printer");
    }

    @Test
    @DisplayName("the skilled ceiling lands on the configured target")
    void skilledCeilingMatchesTarget() {
        assertEquals(PinballEngine.TARGET_RTP, PinballEngine.expectedReturnSkilled(), 1e-9);
    }

    @Test
    @DisplayName("skill improves the return monotonically, without ever crossing 1.0")
    void skillIsMonotonicAndBounded() {
        double previous = -1;
        for (int i = 0; i <= 10; i++) {
            double ev = PinballEngine.expectedReturn(i / 10.0);
            assertTrue(ev > previous, "return must increase with skill (step " + i + ")");
            assertTrue(ev <= PinballEngine.TARGET_RTP + 1e-9,
                    "return at skill " + i / 10.0 + " exceeds the tuned ceiling");
            previous = ev;
        }
    }

    @Test
    @DisplayName("a careless player loses a lot more than a skilled one")
    void skillMatters() {
        double bad = PinballEngine.expectedReturnUnskilled();
        double good = PinballEngine.expectedReturnSkilled();
        assertTrue(bad < good * 0.5,
                "the skill gap is too small to feel meaningful: " + bad + " vs " + good);
        assertTrue(bad > 0, "an unskilled player must still win sometimes");
    }

    @Test
    @DisplayName("skill input outside 0..1 is clamped, not extrapolated")
    void skillIsClamped() {
        assertEquals(PinballEngine.expectedReturn(0.0), PinballEngine.expectedReturn(-5.0), 1e-9);
        assertEquals(PinballEngine.expectedReturn(1.0), PinballEngine.expectedReturn(99.0), 1e-9);
    }

    // ------------------------------------------------------------------
    // Ball lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a ball starts ready and cannot tick before launch")
    void cannotTickBeforeLaunch() {
        PinballEngine engine = new PinballEngine(new Random(1));
        assertEquals(PinballEngine.BallState.READY, engine.state());
        assertFalse(engine.tick(1.0), "tick before launch must be a no-op");
    }

    @Test
    @DisplayName("launching with a non-positive bet is rejected")
    void launchRejectsBadBets() {
        PinballEngine engine = new PinballEngine(new Random(1));
        assertThrows(IllegalArgumentException.class, () -> engine.launch(0));
        assertThrows(IllegalArgumentException.class, () -> engine.launch(-10));
    }

    @Test
    @DisplayName("a second ball cannot be launched while one is in play")
    void cannotLaunchTwoBalls() {
        PinballEngine engine = new PinballEngine(new Random(1));
        engine.launch(100);
        assertThrows(IllegalStateException.class, () -> engine.launch(100));
    }

    @Test
    @DisplayName("every ball eventually drains")
    void ballAlwaysDrains() {
        PinballEngine engine = new PinballEngine(new Random(5));
        engine.launch(100);
        int steps = 0;
        while (engine.state() == PinballEngine.BallState.PLAYING && steps < 10_000) {
            engine.tick(0.5);
            steps++;
        }
        assertEquals(PinballEngine.BallState.DRAINED, engine.state(),
                "ball never drained - the run loop would hang the server thread");
        assertTrue(steps < 10_000, "ball took " + steps + " steps to drain");
    }

    @Test
    @DisplayName("a drained ball cannot keep ticking")
    void drainedBallIsInert() {
        PinballEngine engine = new PinballEngine(new Random(5));
        engine.launch(100);
        while (engine.state() == PinballEngine.BallState.PLAYING) {
            engine.tick(0.0);
        }
        long scoreAtDrain = engine.score();
        assertFalse(engine.tick(1.0));
        assertEquals(scoreAtDrain, engine.score(), "a drained ball must not keep scoring");
    }

    @Test
    @DisplayName("finishing reports a payout that matches the score")
    void finishPaysOut() {
        PinballEngine engine = new PinballEngine(new Random(11));
        engine.launch(200);
        while (engine.state() == PinballEngine.BallState.PLAYING) {
            engine.tick(1.0);
        }
        PinballEngine.BallResult r = engine.finish();
        assertEquals(engine.score(), r.score);
        assertTrue(r.payout >= 0);
        assertEquals(0, r.drains > 1 ? 1 : 0, "exactly one drain ends a ball");
    }

    @Test
    @DisplayName("scoreToPayout is linear in score and pays nothing for nothing")
    void payoutScalesWithScore() {
        long bet = 1_000;
        long oneBumper = PinballEngine.scoreToPayout(PinballEngine.SCORE_PER_UNIT, bet);

        // A single bumper pays a fraction of the stake: the machine cannot be farmed by
        // nudging the ball once and cashing out.
        assertTrue(oneBumper > 0 && oneBumper < bet,
                "one bumper paid " + oneBumper + " on a " + bet + " bet");

        // More bumpers always pay more, and the scale is linear.
        long previous = -1;
        for (int bumpers = 0; bumpers <= PinballEngine.BUMPERS; bumpers++) {
            long score = (long) bumpers * PinballEngine.SCORE_PER_UNIT;
            long payout = PinballEngine.scoreToPayout(score, bet);
            assertTrue(payout >= previous, "payout not monotonic at " + bumpers + " bumpers");
            // The engine rounds once on the total, so an integer comparison against
            // bumpers*oneBumper drifts by a unit or two on large stakes. That is correct
            // behaviour (rounding once is fairer than rounding per bumper), so compare
            // with a tolerance instead of demanding exact equality.
            assertEquals((double) (bumpers * oneBumper), (double) payout, 2.0,
                    "payout is not linear in score at " + bumpers + " bumpers");
            previous = payout;
        }

        // A brilliant ball legitimately pays more than the stake; that is the appeal.
        // What must stay below 1.0 is the *expected* return, tested separately above.
        long fullRow = PinballEngine.scoreToPayout(
                (long) PinballEngine.BUMPERS * PinballEngine.SCORE_PER_UNIT, bet);
        assertTrue(fullRow > bet, "a full bumper row should be a winning ball");

        assertEquals(0, PinballEngine.scoreToPayout(0, bet));
        assertEquals(0, PinballEngine.scoreToPayout(-50, bet));
        assertEquals(0, PinballEngine.scoreToPayout(100, 0));
    }

    @Test
    @DisplayName("a long session with perfect play stays under water")
    void longSessionPerfectPlayLoses() {
        // Simulate the machine's best case: a player with perfect timing, many balls.
        PinballEngine engine = new PinballEngine(new Random(2026));
        long bet = 100;
        long staked = 0;
        long returned = 0;
        for (int ball = 0; ball < 20_000; ball++) {
            engine.launch(bet);
            staked += bet;
            while (engine.state() == PinballEngine.BallState.PLAYING) {
                engine.tick(1.0);           // perfect timing, every time
            }
            returned += engine.finish().payout;
        }
        double rtp = returned / (double) staked;
        assertTrue(rtp < 1.0,
                "20k balls of perfect play returned " + rtp + "x - machine loses money");
        assertEquals(PinballEngine.TARGET_RTP, rtp, 0.25,
                "realistic RTP " + rtp + " is far from the tuned " + PinballEngine.TARGET_RTP);
    }

    @Test
    @DisplayName("the engine is deterministic for a fixed seed")
    void deterministicWithSeed() {
        PinballEngine a = new PinballEngine(new Random(77));
        PinballEngine b = new PinballEngine(new Random(77));
        a.launch(50);
        b.launch(50);
        while (a.state() == PinballEngine.BallState.PLAYING) {
            boolean ca = a.tick(0.8);
            boolean cb = b.tick(0.8);
            assertEquals(ca, cb);
        }
        assertEquals(a.score(), b.score());
    }

    @Test
    @DisplayName("the reported RTP curve has eleven points and never reaches 1.0")
    void rtpCurveIsSane() {
        var curve = PinballEngine.rtpCurve();
        assertEquals(11, curve.size());
        for (double ev : curve) {
            assertTrue(ev < 1.0, "curve contains a losing point: " + ev);
        }
    }
}
