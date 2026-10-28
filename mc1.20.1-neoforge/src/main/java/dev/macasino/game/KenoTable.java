package dev.macasino.game;

import java.util.UUID;

/**
 * Keno: pure server-side lottery game logic.
 *
 * <p>A player places a bet and picks numbers (the client sends the picks). The
 * server draws 20 numbers from 1..80 and counts how many of the player's picks
 * match. Payout is based on the number of matches:
 * <ul>
 *   <li>0 or 1 match: lose everything</li>
 *   <li>2 matches: 2x</li>
 *   <li>3 matches: 10x</li>
 *   <li>4 matches: 50x</li>
 *   <li>5 matches: 500x</li>
 * </ul>
 *
 * <p>The draw is performed by a server-owned {@link java.util.Random}, so a client
 * cannot influence the outcome.
 */
public class KenoTable {

    public static final int MIN_BET = 10;
    public static final int MAX_BET = 10_000;

    private static final int NUMBERS_TO_PICK = 5;
    private static final int NUMBERS_DRAWN = 20;
    private static final int MAX_NUMBER = 80;
    private static final long[] PAYOUTS = {0, 0, 2, 10, 50, 500};

    private final java.util.Random rng = new java.util.Random();
    private final java.util.Map<UUID, Long> bets = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<UUID, Integer> matches = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<UUID, Long> lastPayout = new java.util.concurrent.ConcurrentHashMap<>();

    /** Place a bet for a player. Returns false if the stake is out of range. */
    public boolean placeBet(UUID playerId, long amount) {
        if (amount < MIN_BET || amount > MAX_BET) return false;
        bets.put(playerId, amount);
        return true;
    }

    /** Draw 20 numbers and count how many of the player's picks match.
     * Returns the number of matches. */
    public int draw(UUID playerId, int[] playerPicks) {
        Long bet = bets.get(playerId);
        if (bet == null || bet <= 0 || playerPicks == null || playerPicks.length != NUMBERS_TO_PICK) {
            return -1;
        }

        boolean[] drawn = new boolean[MAX_NUMBER + 1];
        int drawnCount = 0;
        while (drawnCount < NUMBERS_DRAWN) {
            int n = rng.nextInt(MAX_NUMBER) + 1;
            if (!drawn[n]) {
                drawn[n] = true;
                drawnCount++;
            }
        }

        int matchCount = 0;
        for (int pick : playerPicks) {
            if (pick >= 1 && pick <= MAX_NUMBER && drawn[pick]) {
                matchCount++;
            }
        }

        matches.put(playerId, matchCount);

        long multiplier = matchCount < PAYOUTS.length ? PAYOUTS[matchCount] : 0;
        long payout = bet * multiplier;
        lastPayout.put(playerId, payout);

        return matchCount;
    }

    /** Get the number of matches for a player, or -1 if no draw has occurred. */
    public int getMatches(UUID playerId) {
        return matches.getOrDefault(playerId, -1);
    }

    /** Get the last payout for a player, or 0 if none. */
    public long getLastPayout(UUID playerId) {
        return lastPayout.getOrDefault(playerId, 0L);
    }

    /** Get the bet amount for a player, or 0 if none. */
    public long getBet(UUID playerId) {
        return bets.getOrDefault(playerId, 0L);
    }

    /** Clear a player's bet (refund). */
    public long clearBet(UUID playerId) {
        Long bet = bets.remove(playerId);
        return bet != null ? bet : 0L;
    }
}
