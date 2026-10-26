package dev.emccasino.game;

import java.util.ArrayList;
import java.util.List;

public class BaccaratEngine {
    public enum BetTarget {
        PLAYER(1.0),
        BANKER(0.95), // 5% commission
        TIE(8.0);

        public final double payoutMultiplier;

        BetTarget(double payoutMultiplier) {
            this.payoutMultiplier = payoutMultiplier;
        }
    }

    public enum Outcome {
        PLAYER_WIN,
        BANKER_WIN,
        TIE
    }

    public static class BaccaratResult {
        public final List<Card> playerCards;
        public final List<Card> bankerCards;
        public final int playerTotal;
        public final int bankerTotal;
        public final Outcome outcome;
        public final long payout;

        public BaccaratResult(List<Card> playerCards, List<Card> bankerCards, int playerTotal, int bankerTotal, Outcome outcome, long payout) {
            this.playerCards = playerCards;
            this.bankerCards = bankerCards;
            this.playerTotal = playerTotal;
            this.bankerTotal = bankerTotal;
            this.outcome = outcome;
            this.payout = payout;
        }
    }

    private static int cardValue(Card card) {
        if (card.rank == Card.Rank.ACE) return 1;
        if (card.rank.value >= 10) return 0;
        return card.rank.value;
    }

    public static int handTotal(List<Card> hand) {
        int sum = 0;
        for (Card c : hand) {
            sum += cardValue(c);
        }
        return sum % 10;
    }

    public BaccaratResult play(BetTarget betTarget, long betAmount) {
        Deck deck = new Deck();
        List<Card> playerHand = new ArrayList<>();
        List<Card> bankerHand = new ArrayList<>();

        playerHand.add(deck.draw());
        bankerHand.add(deck.draw());
        playerHand.add(deck.draw());
        bankerHand.add(deck.draw());

        int pTotal = handTotal(playerHand);
        int bTotal = handTotal(bankerHand);

        // Check Naturals
        if (pTotal < 8 && bTotal < 8) {
            Card playerThirdCard = null;
            // Player drawing rules
            if (pTotal <= 5) {
                playerThirdCard = deck.draw();
                playerHand.add(playerThirdCard);
                pTotal = handTotal(playerHand);
            }

            // Banker drawing rules
            if (playerThirdCard == null) {
                if (bTotal <= 5) {
                    bankerHand.add(deck.draw());
                    bTotal = handTotal(bankerHand);
                }
            } else {
                int p3Val = cardValue(playerThirdCard);
                boolean bankerDraws = false;
                if (bTotal <= 2) {
                    bankerDraws = true;
                } else if (bTotal == 3) {
                    bankerDraws = (p3Val != 8);
                } else if (bTotal == 4) {
                    bankerDraws = (p3Val >= 2 && p3Val <= 7);
                } else if (bTotal == 5) {
                    bankerDraws = (p3Val >= 4 && p3Val <= 7);
                } else if (bTotal == 6) {
                    bankerDraws = (p3Val == 6 || p3Val == 7);
                }
                if (bankerDraws) {
                    bankerHand.add(deck.draw());
                    bTotal = handTotal(bankerHand);
                }
            }
        }

        Outcome outcome;
        if (pTotal > bTotal) {
            outcome = Outcome.PLAYER_WIN;
        } else if (bTotal > pTotal) {
            outcome = Outcome.BANKER_WIN;
        } else {
            outcome = Outcome.TIE;
        }

        long payout = 0;
        if (outcome == Outcome.TIE) {
            if (betTarget == BetTarget.TIE) {
                payout = betAmount + (long) (betAmount * BetTarget.TIE.payoutMultiplier);
            } else {
                // Main bets push on tie
                payout = betAmount;
            }
        } else if (outcome == Outcome.PLAYER_WIN && betTarget == BetTarget.PLAYER) {
            payout = betAmount + (long) (betAmount * BetTarget.PLAYER.payoutMultiplier);
        } else if (outcome == Outcome.BANKER_WIN && betTarget == BetTarget.BANKER) {
            payout = betAmount + (long) (betAmount * BetTarget.BANKER.payoutMultiplier);
        }

        return new BaccaratResult(playerHand, bankerHand, pTotal, bTotal, outcome, payout);
    }
}
