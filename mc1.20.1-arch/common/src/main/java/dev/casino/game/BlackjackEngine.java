package dev.casino.game;

import java.util.ArrayList;
import java.util.List;

public class BlackjackEngine {
    public enum GameStatus {
        BETTING,
        PLAYER_TURN,
        DEALER_TURN,
        FINISHED
    }

    public enum RoundResult {
        NONE,
        PLAYER_WIN,
        DEALER_WIN,
        PUSH,
        BLACKJACK,
        PLAYER_BUST,
        DEALER_BUST
    }

    public static class Hand {
        public final List<Card> cards = new ArrayList<>();

        public void add(Card card) {
            cards.add(card);
        }

        public int calculateValue() {
            int total = 0;
            int aces = 0;
            for (Card c : cards) {
                if (c.rank == Card.Rank.ACE) {
                    aces++;
                    total += 11;
                } else if (c.rank.value >= 10) {
                    total += 10;
                } else {
                    total += c.rank.value;
                }
            }
            while (total > 21 && aces > 0) {
                total -= 10;
                aces--;
            }
            return total;
        }

        public boolean isBust() {
            return calculateValue() > 21;
        }

        public boolean isBlackjack() {
            return cards.size() == 2 && calculateValue() == 21;
        }
    }

    private final Deck deck = new Deck();
    private final Hand playerHand = new Hand();
    private final Hand dealerHand = new Hand();
    private long currentBet = 0;
    private GameStatus status = GameStatus.BETTING;
    private RoundResult result = RoundResult.NONE;
    private long payout = 0;

    public void startRound(long bet) {
        deck.reset();
        playerHand.cards.clear();
        dealerHand.cards.clear();
        currentBet = bet;
        status = GameStatus.PLAYER_TURN;
        result = RoundResult.NONE;
        payout = 0;

        playerHand.add(deck.draw());
        dealerHand.add(deck.draw());
        playerHand.add(deck.draw());
        dealerHand.add(deck.draw());

        if (playerHand.isBlackjack()) {
            if (dealerHand.isBlackjack()) {
                status = GameStatus.FINISHED;
                result = RoundResult.PUSH;
                payout = currentBet;
            } else {
                status = GameStatus.FINISHED;
                result = RoundResult.BLACKJACK;
                // Blackjack pays 3:2 (1.5x profit + original bet)
                payout = currentBet + (currentBet * 3 / 2);
            }
        }
    }

    public void hit() {
        if (status != GameStatus.PLAYER_TURN) return;
        playerHand.add(deck.draw());
        if (playerHand.isBust()) {
            status = GameStatus.FINISHED;
            result = RoundResult.PLAYER_BUST;
            payout = 0;
        } else if (playerHand.calculateValue() == 21) {
            stand();
        }
    }

    public void doubleDown() {
        if (status != GameStatus.PLAYER_TURN || playerHand.cards.size() != 2) return;
        currentBet *= 2;
        playerHand.add(deck.draw());
        if (playerHand.isBust()) {
            status = GameStatus.FINISHED;
            result = RoundResult.PLAYER_BUST;
            payout = 0;
        } else {
            stand();
        }
    }

    public void stand() {
        if (status != GameStatus.PLAYER_TURN) return;
        status = GameStatus.DEALER_TURN;

        // Dealer draws until 17 or more
        while (dealerHand.calculateValue() < 17) {
            dealerHand.add(deck.draw());
        }

        status = GameStatus.FINISHED;
        int pVal = playerHand.calculateValue();
        int dVal = dealerHand.calculateValue();

        if (dealerHand.isBust()) {
            result = RoundResult.DEALER_BUST;
            payout = currentBet * 2;
        } else if (pVal > dVal) {
            result = RoundResult.PLAYER_WIN;
            payout = currentBet * 2;
        } else if (pVal < dVal) {
            result = RoundResult.DEALER_WIN;
            payout = 0;
        } else {
            result = RoundResult.PUSH;
            payout = currentBet;
        }
    }

    public Hand getPlayerHand() { return playerHand; }
    public Hand getDealerHand() { return dealerHand; }
    public GameStatus getStatus() { return status; }
    public RoundResult getResult() { return result; }
    public long getCurrentBet() { return currentBet; }
    public long getPayout() { return payout; }
}
