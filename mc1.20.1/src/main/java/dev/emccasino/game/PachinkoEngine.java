package dev.emccasino.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PachinkoEngine {
    public static final int BOARD_WIDTH = 180;
    public static final int BOARD_HEIGHT = 160;
    public static final int ROWS = 10;
    public static final int PINS_PER_ROW = 9;

    public enum DropOutcome {
        LOST(0, "Out Gutter"),
        REGULAR_PIN(5, "Pin Pocket (5x)"),
        TULIP_GATE(15, "Tulip Gate (15x)"),
        JACKPOT(100, "FEVER JACKPOT! (100x)");

        public final int multiplier;
        public final String label;

        DropOutcome(int multiplier, String label) {
            this.multiplier = multiplier;
            this.label = label;
        }
    }

    public static class BallDropResult {
        public final List<int[]> trajectory; // series of (x, y) coordinates for smooth visual bounce
        public final DropOutcome outcome;
        public final long payout;

        public BallDropResult(List<int[]> trajectory, DropOutcome outcome, long payout) {
            this.trajectory = trajectory;
            this.outcome = outcome;
            this.payout = payout;
        }
    }

    private final Random random = new Random();

    public BallDropResult dropBall(long betPerBall) {
        List<int[]> path = new ArrayList<>();

        // Start near center top
        float x = (BOARD_WIDTH / 2f) + (random.nextFloat() * 20f - 10f);
        float y = 10;
        path.add(new int[]{(int) x, (int) y});

        // Fall through peg rows
        for (int row = 0; row < ROWS; row++) {
            y += (float) (BOARD_HEIGHT - 30) / ROWS;
            // Peg bounce deflection
            float bounce = (random.nextFloat() - 0.48f) * 14f;
            x += bounce;

            // Constrain within side walls
            if (x < 15) x = 15 + random.nextFloat() * 5;
            if (x > BOARD_WIDTH - 15) x = BOARD_WIDTH - 15 - random.nextFloat() * 5;

            path.add(new int[]{(int) x, (int) y});
        }

        // Final drop position determines outcome
        y = BOARD_HEIGHT;
        path.add(new int[]{(int) x, (int) y});

        DropOutcome outcome;
        int finalX = (int) x;
        int center = BOARD_WIDTH / 2;

        // Center pocket is Jackpot (width 20)
        if (Math.abs(finalX - center) <= 10) {
            outcome = DropOutcome.JACKPOT;
        }
        // Left & right tulips (width 16 each)
        else if (Math.abs(finalX - (center - 35)) <= 8 || Math.abs(finalX - (center + 35)) <= 8) {
            outcome = DropOutcome.TULIP_GATE;
        }
        // Outer pin pockets
        else if (Math.abs(finalX - (center - 65)) <= 10 || Math.abs(finalX - (center + 65)) <= 10) {
            outcome = DropOutcome.REGULAR_PIN;
        }
        // Otherwise out-chute
        else {
            outcome = DropOutcome.LOST;
        }

        long payout = (long) (betPerBall * outcome.multiplier);
        return new BallDropResult(path, outcome, payout);
    }
}
