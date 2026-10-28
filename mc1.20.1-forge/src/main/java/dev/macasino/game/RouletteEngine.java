package dev.macasino.game;

import java.util.*;

public class RouletteEngine {
    public static final Set<Integer> RED_NUMBERS = Set.of(
            1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36
    );

    public enum BetType {
        STRAIGHT(35),      // Specific number 0..36
        RED(1),            // Red color
        BLACK(1),          // Black color
        EVEN(1),           // Even number (excluding 0)
        ODD(1),            // Odd number
        LOW(1),            // 1-18
        HIGH(1),           // 19-36
        DOZEN_1(2),        // 1-12
        DOZEN_2(2),        // 13-24
        DOZEN_3(2),        // 25-36
        COL_1(2),          // 1, 4, 7, ..., 34
        COL_2(2),          // 2, 5, 8, ..., 35
        COL_3(2);          // 3, 6, 9, ..., 36

        public final int payoutMultiplier;

        BetType(int payoutMultiplier) {
            this.payoutMultiplier = payoutMultiplier;
        }
    }

    public static class Bet {
        public final BetType type;
        public final int number; // only relevant for STRAIGHT
        public final long amount;

        public Bet(BetType type, int number, long amount) {
            this.type = type;
            this.number = number;
            this.amount = amount;
        }
    }

    public static class SpinResult {
        public final int winningNumber;
        public final boolean isRed;
        public final boolean isBlack;
        public final boolean isGreen;
        public final long totalWon;

        public SpinResult(int winningNumber, boolean isRed, boolean isBlack, boolean isGreen, long totalWon) {
            this.winningNumber = winningNumber;
            this.isRed = isRed;
            this.isBlack = isBlack;
            this.isGreen = isGreen;
            this.totalWon = totalWon;
        }
    }

    private final java.security.SecureRandom random = new java.security.SecureRandom();

    public SpinResult spin(List<Bet> bets) {
        int winningNumber = random.nextInt(37); // 0 to 36
        boolean isGreen = (winningNumber == 0);
        boolean isRed = !isGreen && RED_NUMBERS.contains(winningNumber);
        boolean isBlack = !isGreen && !isRed;

        long totalWon = 0;
        for (Bet bet : bets) {
            if (isWinningBet(bet, winningNumber, isRed, isBlack)) {
                // Return original bet + profit
                totalWon += bet.amount + (bet.amount * bet.type.payoutMultiplier);
            }
        }

        return new SpinResult(winningNumber, isRed, isBlack, isGreen, totalWon);
    }

    public static boolean isWinningBet(Bet bet, int number, boolean isRed, boolean isBlack) {
        if (number == 0) {
            return bet.type == BetType.STRAIGHT && bet.number == 0;
        }

        return switch (bet.type) {
            case STRAIGHT -> bet.number == number;
            case RED -> isRed;
            case BLACK -> isBlack;
            case EVEN -> number % 2 == 0;
            case ODD -> number % 2 != 0;
            case LOW -> number >= 1 && number <= 18;
            case HIGH -> number >= 19 && number <= 36;
            case DOZEN_1 -> number >= 1 && number <= 12;
            case DOZEN_2 -> number >= 13 && number <= 24;
            case DOZEN_3 -> number >= 25 && number <= 36;
            case COL_1 -> number % 3 == 1;
            case COL_2 -> number % 3 == 2;
            case COL_3 -> number % 3 == 0;
        };
    }
}
