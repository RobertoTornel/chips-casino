package dev.casino.game;

import java.util.ArrayList;
import java.util.List;

public class VideoPokerEngine {
    public enum State {
        BETTING,
        DEALT,
        FINISHED
    }

    public static class PokerRoundResult {
        public final List<Card> cards;
        public final PokerHandEvaluator.HandResult handResult;
        public final long payout;
        public final int multiplier;

        public PokerRoundResult(List<Card> cards, PokerHandEvaluator.HandResult handResult, long payout, int multiplier) {
            this.cards = cards;
            this.handResult = handResult;
            this.payout = payout;
            this.multiplier = multiplier;
        }
    }

    private final Deck deck = new Deck();
    private final List<Card> hand = new ArrayList<>(5);
    private final boolean[] held = new boolean[5];
    private long currentBet = 0;
    private State state = State.BETTING;

    public void deal(long bet) {
        deck.reset();
        hand.clear();
        for (int i = 0; i < 5; i++) {
            held[i] = false;
            hand.add(deck.draw());
        }
        currentBet = bet;
        state = State.DEALT;
    }

    public void toggleHold(int cardIndex) {
        if (state == State.DEALT && cardIndex >= 0 && cardIndex < 5) {
            held[cardIndex] = !held[cardIndex];
        }
    }

    public PokerRoundResult draw() {
        if (state != State.DEALT) return null;
        for (int i = 0; i < 5; i++) {
            if (!held[i]) {
                hand.set(i, deck.draw());
            }
        }
        state = State.FINISHED;

        PokerHandEvaluator.HandResult eval = PokerHandEvaluator.evaluate5(hand);
        int mult = 0;

        if (eval.type == PokerHandEvaluator.HandType.ONE_PAIR) {
            // Must be Jacks or Better (score indicates rank >= 11)
            if ("Jacks or Better".equals(eval.description)) {
                mult = 1;
            }
        } else {
            mult = eval.type.videoPokerMultiplier;
        }

        long payout = (mult > 0) ? currentBet * mult : 0;
        return new PokerRoundResult(new ArrayList<>(hand), eval, payout, mult);
    }

    public List<Card> getHand() { return hand; }
    public boolean[] getHeld() { return held; }
    public State getState() { return state; }
    public long getCurrentBet() { return currentBet; }
}
