package dev.casino.game;

import java.util.Objects;

public class Card implements Comparable<Card> {
    public enum Suit {
        HEARTS("♥", true, 0),
        DIAMONDS("♦", true, 1),
        CLUBS("♣", false, 2),
        SPADES("♠", false, 3);

        public final String symbol;
        public final boolean isRed;
        public final int index;

        Suit(String symbol, boolean isRed, int index) {
            this.symbol = symbol;
            this.isRed = isRed;
            this.index = index;
        }
    }

    public enum Rank {
        TWO(2, "2"),
        THREE(3, "3"),
        FOUR(4, "4"),
        FIVE(5, "5"),
        SIX(6, "6"),
        SEVEN(7, "7"),
        EIGHT(8, "8"),
        NINE(9, "9"),
        TEN(10, "10"),
        JACK(11, "J"),
        QUEEN(12, "Q"),
        KING(13, "K"),
        ACE(14, "A");

        public final int value;
        public final String display;

        Rank(int value, String display) {
            this.value = value;
            this.display = display;
        }
    }

    public final Suit suit;
    public final Rank rank;

    public Card(Suit suit, Rank rank) {
        this.suit = suit;
        this.rank = rank;
    }

    public String toShortString() {
        return rank.display + suit.symbol;
    }

    @Override
    public String toString() {
        return toShortString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Card card = (Card) o;
        return suit == card.suit && rank == card.rank;
    }

    @Override
    public int hashCode() {
        return Objects.hash(suit, rank);
    }

    @Override
    public int compareTo(Card o) {
        return Integer.compare(this.rank.value, o.rank.value);
    }
}
