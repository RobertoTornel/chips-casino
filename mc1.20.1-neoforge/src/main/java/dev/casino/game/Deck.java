package dev.casino.game;

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

    public int remaining() {
        return cards.size();
    }
}
