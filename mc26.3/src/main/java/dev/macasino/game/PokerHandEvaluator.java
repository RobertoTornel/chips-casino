package dev.macasino.game;

import java.util.*;

public class PokerHandEvaluator {
    public enum HandType {
        HIGH_CARD(1, "High Card", 0),
        ONE_PAIR(2, "One Pair", 0),
        TWO_PAIR(3, "Two Pair", 2),
        THREE_OF_A_KIND(4, "Three of a Kind", 3),
        STRAIGHT(5, "Straight", 4),
        FLUSH(6, "Flush", 6),
        FULL_HOUSE(7, "Full House", 9),
        FOUR_OF_A_KIND(8, "Four of a Kind", 25),
        STRAIGHT_FLUSH(9, "Straight Flush", 50),
        ROYAL_FLUSH(10, "Royal Flush", 800);

        public final int rank;
        public final String name;
        public final int videoPokerMultiplier;

        HandType(int rank, String name, int videoPokerMultiplier) {
            this.rank = rank;
            this.name = name;
            this.videoPokerMultiplier = videoPokerMultiplier;
        }
    }

    public static class HandResult implements Comparable<HandResult> {
        public final HandType type;
        public final long score;
        public final String description;
        public final List<Card> bestFiveCards;

        public HandResult(HandType type, long score, String description, List<Card> bestFiveCards) {
            this.type = type;
            this.score = score;
            this.description = description;
            this.bestFiveCards = bestFiveCards;
        }

        @Override
        public int compareTo(HandResult o) {
            return Long.compare(this.score, o.score);
        }
    }

    public static HandResult evaluate7(List<Card> cards) {
        if (cards == null || cards.size() < 5) {
            return new HandResult(HandType.HIGH_CARD, 0, "Incomplete Hand", Collections.emptyList());
        }
        if (cards.size() == 5) {
            return evaluate5(cards);
        }

        // Generate all 5-card combinations from 7 cards (21 combinations)
        List<List<Card>> combos = new ArrayList<>();
        combine(cards, 5, 0, new ArrayList<>(), combos);

        HandResult best = null;
        for (List<Card> combo : combos) {
            HandResult res = evaluate5(combo);
            if (best == null || res.compareTo(best) > 0) {
                best = res;
            }
        }
        return best;
    }

    private static void combine(List<Card> src, int k, int start, List<Card> current, List<List<Card>> result) {
        if (current.size() == k) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (int i = start; i < src.size(); i++) {
            current.add(src.get(i));
            combine(src, k, i + 1, current, result);
            current.remove(current.size() - 1);
        }
    }

    public static HandResult evaluate5(List<Card> five) {
        List<Card> sorted = new ArrayList<>(five);
        Collections.sort(sorted, Collections.reverseOrder());

        Map<Integer, Integer> rankCounts = new HashMap<>();
        Map<Card.Suit, Integer> suitCounts = new HashMap<>();
        for (Card c : sorted) {
            rankCounts.put(c.rank.value, rankCounts.getOrDefault(c.rank.value, 0) + 1);
            suitCounts.put(c.suit, suitCounts.getOrDefault(c.suit, 0) + 1);
        }

        boolean isFlush = suitCounts.values().stream().anyMatch(cnt -> cnt >= 5);

        // Check straight
        List<Integer> distinctRanks = new ArrayList<>(new TreeSet<>(rankCounts.keySet()));
        Collections.sort(distinctRanks, Collections.reverseOrder());

        int straightHigh = getStraightHigh(distinctRanks);
        boolean isStraight = straightHigh > 0;

        if (isStraight && isFlush) {
            if (straightHigh == 14) {
                return new HandResult(HandType.ROYAL_FLUSH, computeScore(HandType.ROYAL_FLUSH, 14), "Royal Flush", sorted);
            }
            return new HandResult(HandType.STRAIGHT_FLUSH, computeScore(HandType.STRAIGHT_FLUSH, straightHigh), "Straight Flush", sorted);
        }

        // Four of a kind
        for (Map.Entry<Integer, Integer> e : rankCounts.entrySet()) {
            if (e.getValue() == 4) {
                int kicker = sorted.stream().mapToInt(c -> c.rank.value).filter(r -> r != e.getKey()).max().orElse(0);
                long score = computeScore(HandType.FOUR_OF_A_KIND, e.getKey(), kicker);
                return new HandResult(HandType.FOUR_OF_A_KIND, score, "Four of a Kind", sorted);
            }
        }

        // Full house
        int threeRank = 0;
        int pairRank = 0;
        for (Map.Entry<Integer, Integer> e : rankCounts.entrySet()) {
            if (e.getValue() == 3) {
                if (e.getKey() > threeRank) threeRank = e.getKey();
            } else if (e.getValue() == 2) {
                if (e.getKey() > pairRank) pairRank = e.getKey();
            }
        }
        if (threeRank > 0 && pairRank > 0) {
            long score = computeScore(HandType.FULL_HOUSE, threeRank, pairRank);
            return new HandResult(HandType.FULL_HOUSE, score, "Full House", sorted);
        }

        if (isFlush) {
            long score = computeScore(HandType.FLUSH, sorted.get(0).rank.value, sorted.get(1).rank.value, sorted.get(2).rank.value, sorted.get(3).rank.value, sorted.get(4).rank.value);
            return new HandResult(HandType.FLUSH, score, "Flush", sorted);
        }

        if (isStraight) {
            long score = computeScore(HandType.STRAIGHT, straightHigh);
            return new HandResult(HandType.STRAIGHT, score, "Straight", sorted);
        }

        if (threeRank > 0) {
            final int tR = threeRank;
            int[] kickers = sorted.stream().mapToInt(c -> c.rank.value).filter(r -> r != tR).toArray();
            long score = computeScore(HandType.THREE_OF_A_KIND, threeRank, kickers.length > 0 ? kickers[0] : 0, kickers.length > 1 ? kickers[1] : 0);
            return new HandResult(HandType.THREE_OF_A_KIND, score, "Three of a Kind", sorted);
        }

        // Pairs
        List<Integer> pairs = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : rankCounts.entrySet()) {
            if (e.getValue() == 2) pairs.add(e.getKey());
        }
        Collections.sort(pairs, Collections.reverseOrder());

        if (pairs.size() >= 2) {
            int highPair = pairs.get(0);
            int lowPair = pairs.get(1);
            int kicker = sorted.stream().mapToInt(c -> c.rank.value).filter(r -> r != highPair && r != lowPair).max().orElse(0);
            long score = computeScore(HandType.TWO_PAIR, highPair, lowPair, kicker);
            return new HandResult(HandType.TWO_PAIR, score, "Two Pair", sorted);
        } else if (pairs.size() == 1) {
            int pair = pairs.get(0);
            int[] kickers = sorted.stream().mapToInt(c -> c.rank.value).filter(r -> r != pair).toArray();
            long score = computeScore(HandType.ONE_PAIR, pair, kickers.length > 0 ? kickers[0] : 0, kickers.length > 1 ? kickers[1] : 0, kickers.length > 2 ? kickers[2] : 0);
            String desc = (pair >= 11) ? "Jacks or Better" : "One Pair";
            return new HandResult(HandType.ONE_PAIR, score, desc, sorted);
        }

        // High Card
        long score = computeScore(HandType.HIGH_CARD, sorted.get(0).rank.value, sorted.get(1).rank.value, sorted.get(2).rank.value, sorted.get(3).rank.value, sorted.get(4).rank.value);
        return new HandResult(HandType.HIGH_CARD, score, "High Card (" + sorted.get(0).rank.display + ")", sorted);
    }

    private static int getStraightHigh(List<Integer> distinctDesc) {
        if (distinctDesc.size() < 5) return 0;
        for (int i = 0; i <= distinctDesc.size() - 5; i++) {
            int top = distinctDesc.get(i);
            if (distinctDesc.get(i + 1) == top - 1 &&
                distinctDesc.get(i + 2) == top - 2 &&
                distinctDesc.get(i + 3) == top - 3 &&
                distinctDesc.get(i + 4) == top - 4) {
                return top;
            }
        }
        // Ace-low straight (A-2-3-4-5)
        if (distinctDesc.contains(14) && distinctDesc.contains(2) && distinctDesc.contains(3) && distinctDesc.contains(4) && distinctDesc.contains(5)) {
            return 5;
        }
        return 0;
    }

    private static long computeScore(HandType type, int... tiebreakers) {
        long score = (long) type.rank * 100_000_000_000L;
        long multiplier = 1_000_000_000L;
        for (int tb : tiebreakers) {
            score += (long) tb * multiplier;
            multiplier /= 100L;
        }
        return score;
    }
}
