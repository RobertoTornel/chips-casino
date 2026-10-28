package dev.macasino.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class Deck {
    private final List<Card> cards = new ArrayList<>();
    private final Random random = new Random();

    public Deck() {
        reset();
    }

    public void reset() {
        cards.clear();
        for (Card.Suit suit : Card.Suit.values()) {
            for (Card.Rank rank : Card.Rank.values()) {
                cards.add(new Card(suit, rank));
            }
        }
        shuffle();
    }

    public void shuffle() {
        Collections.shuffle(cards, random);
    }

    public Card draw() {
        if (cards.isEmpty()) {
            reset();
        }
        return cards.remove(cards.size() - 1);
    }

    public List<Card> draw(int count) {
        List<Card> drawn = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            drawn.add(draw());
        }
        return drawn;
    }

    /**
     * Test hook: forces the next {@link #draw()} calls to return these cards in the
     * given order. Production code never calls this; the shoe is otherwise shuffled
     * from {@link #reset()}.
     */
    public void setDrawOrder(java.util.List<Card> order) {
        cards.clear();
        for (int i = order.size() - 1; i >= 0; i--) {
            cards.add(order.get(i));
        }
    }

    public int remaining() {
        return cards.size();
    }
}
