package dev.casino.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.casino.game.BlackjackTable.Hand;
import dev.casino.game.BlackjackTable.HandState;
import dev.casino.game.BlackjackTable.Outcome;
import dev.casino.game.BlackjackTable.Phase;
import dev.casino.game.BlackjackTable.Seat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Locks the blackjack rule set the server owns: seating, bet limits, the paced deal, the
 * hidden hole card, S17 dealer play, doubles, splits, 3:2 naturals and the payout queue.
 *
 * <p>{@link BlackjackTable}, {@link Card} and {@link Deck} are plain Java, so every rule
 * is checkable on a bare JVM. Hands are made deterministic with
 * {@link BlackjackTable#forceShoe(Card...)} — it pins the shoe and survives the round-start
 * reshuffle. The animation clock is driven by ticking the table by hand: one card every
 * {@value BlackjackTable#DEAL_INTERVAL} ticks, so a deal needs a few dozen ticks and the
 * dealer's turn a few dozen more. Nothing sleeps.
 *
 * <p>Tests that assert on cards switch the house bots off
 * ({@link BlackjackTable#toggleAutoFillBots()}), because they bet and play from an unseeded
 * {@link java.util.Random} and would make the seat/deal order non-deterministic.
 */
class BlackjackTableTest {

    private static final long BET = 100L;

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Runs the server clock n times. */
    private static void tick(BlackjackTable table, int times) {
        for (int i = 0; i < times; i++) {
            table.tickServer();
        }
    }

    /** Ticks until the table reaches the target phase, or the tick budget runs out. Asserts nothing. */
    private static void advanceUntilPhase(BlackjackTable table, Phase target, int maxTicks) {
        for (int i = 0; i < maxTicks && table.getPhase() != target; i++) {
            table.tickServer();
        }
    }

    /** A fresh table whose empty chairs stay empty, so seat and deal order are fixed. */
    private static BlackjackTable tableWithoutBots() {
        BlackjackTable table = new BlackjackTable();
        if (table.isAutoFillBots()) {
            table.toggleAutoFillBots();
        }
        return table;
    }

    /** Deterministic player id; the bot ids live in the 0xB07 space, so these never collide. */
    private static UUID player(int n) {
        return new UUID(0xCAFE_0000L, n);
    }

    private static Card spades(Card.Rank rank) {
        return new Card(Card.Suit.SPADES, rank);
    }

    private static Card hearts(Card.Rank rank) {
        return new Card(Card.Suit.HEARTS, rank);
    }

    private static Card clubs(Card.Rank rank) {
        return new Card(Card.Suit.CLUBS, rank);
    }

    private static Card diamonds(Card.Rank rank) {
        return new Card(Card.Suit.DIAMONDS, rank);
    }

    // ------------------------------------------------------------------
    // seating and betting
    // ------------------------------------------------------------------

    /**
     * Seats fill in order, a re-join is idempotent, and a seventh player is refused.
     */
    @Test
    @DisplayName("join seats players in order, re-join keeps the seat, seventh is refused")
    void join_seatsPlayersInOrderAndRefusesDuplicates() {
        BlackjackTable table = tableWithoutBots();
        assertEquals(0, table.join(player(1), "Ada"));
        assertEquals(1, table.join(player(2), "Boris"));
        assertEquals(0, table.join(player(1), "Ada"), "re-joining must return the same seat");
        assertEquals(2, table.join(player(3), "Cleo"));
        assertEquals(3, table.join(player(4), "Dmitri"));
        assertEquals(4, table.join(player(5), "Esme"));
        assertEquals(5, table.join(player(6), "Frank"));
        assertEquals(-1, table.join(player(7), "Seventh"), "a full table must refuse");
        assertEquals(BlackjackTable.MAX_SEATS, table.getSeats().length);
        assertEquals(-1, table.seatOf(player(7)));
    }

    /**
     * Leaving gives the chair back to the next player in line.
     */
    @Test
    @DisplayName("leave frees the seat for the next player")
    void leave_freesTheSeatForTheNextPlayer() {
        BlackjackTable table = tableWithoutBots();
        assertEquals(0, table.join(player(1), "Ada"));
        assertEquals(1, table.join(player(2), "Boris"));
        table.leave(player(1));
        assertEquals(-1, table.seatOf(player(1)));
        assertEquals(1, table.seatOf(player(2)), "the other player must not be shifted");
        assertEquals(0, table.join(player(3), "Cleo"), "the freed chair must be reused first");
        assertEquals(0, table.seatOf(player(3)));
    }

    /**
     * Bets outside MIN_BET..MAX_BET are refused, the boundaries are accepted.
     */
    @Test
    @DisplayName("placeBet rejects below minimum and above maximum")
    void placeBet_rejectsBelowMinimumAndAboveMaximum() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        UUID b = player(2);
        table.join(a, "Ada");
        table.join(b, "Boris");

        assertFalse(table.placeBet(a, 9L), "9 is below MIN_BET");
        assertEquals(0L, table.getSeats()[0].bet, "a refused bet must not be stored");
        assertFalse(table.placeBet(a, 20_001L), "20001 is above MAX_BET");
        assertEquals(0L, table.getSeats()[0].bet, "a refused bet must not be stored");

        assertTrue(table.placeBet(a, BlackjackTable.MIN_BET), "MIN_BET must be accepted");
        assertEquals(BlackjackTable.MIN_BET, table.getSeats()[0].bet);
        assertTrue(table.placeBet(a, BlackjackTable.MAX_BET), "MAX_BET must be accepted");
        assertEquals(BlackjackTable.MAX_BET, table.getSeats()[0].bet);
        assertEquals(10L, BlackjackTable.MIN_BET);
        assertEquals(10_000L, BlackjackTable.MAX_BET);
    }

    // ------------------------------------------------------------------
    // the deal
    // ------------------------------------------------------------------

    /**
     * Two players who bet both get two cards, and the dealer gets an up card and a hole card.
     */
    @Test
    @DisplayName("dealing gives every seated player two cards and the dealer two")
    void dealing_givesEverySeatedPlayerTwoCardsAndDealerTwo() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        UUID b = player(2);
        table.join(a, "Ada");
        table.join(b, "Boris");
        // Deal order with two betting seats: seat, seat, dealer up, seat, seat, dealer hole.
        table.forceShoe(
                spades(Card.Rank.TEN), clubs(Card.Rank.NINE),
                hearts(Card.Rank.SIX),
                spades(Card.Rank.FIVE), clubs(Card.Rank.SEVEN),
                hearts(Card.Rank.SEVEN));

        assertTrue(table.placeBet(a, BET));
        assertTrue(table.placeBet(b, BET), "the last missing bet starts the round");
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);

        assertEquals(Phase.PLAYER_TURNS, table.getPhase());
        Seat seatA = table.getSeats()[table.seatOf(a)];
        Seat seatB = table.getSeats()[table.seatOf(b)];
        assertEquals(1, seatA.hands.size());
        assertEquals(1, seatB.hands.size());
        assertEquals(2, seatA.hands.get(0).cards.size(), "seat A must hold two cards");
        assertEquals(2, seatB.hands.get(0).cards.size(), "seat B must hold two cards");
        assertEquals(2, table.getDealerHand().cards.size(), "the dealer must hold two cards");
        assertEquals(15, seatA.hands.get(0).calculateValue());
        assertEquals(16, seatB.hands.get(0).calculateValue());
        assertEquals(Card.Rank.TEN, seatA.hands.get(0).cards.get(0).card.rank);
        assertEquals(Card.Rank.NINE, seatB.hands.get(0).cards.get(0).card.rank);
    }

    /**
     * The hole card is face down in the packet and masked in the JSON until the dealer turns it.
     */
    @Test
    @DisplayName("hole card is hidden until the dealer turn")
    void holeCardIsHiddenUntilDealerTurn() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.TEN), hearts(Card.Rank.NINE),
                spades(Card.Rank.EIGHT), diamonds(Card.Rank.SEVEN));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);

        assertEquals(2, table.getDealerHand().cards.size());
        assertTrue(table.getDealerHand().cards.get(1).faceDown, "the hole card must be face down");
        assertFalse(table.getDealerHoleRevealed());

        JsonObject json = table.toJson(a, true);
        JsonArray dealerCards = json.getAsJsonObject("dealer").getAsJsonArray("cards");
        assertEquals("??", dealerCards.get(1).getAsJsonObject().get("c").getAsString());
        assertTrue(dealerCards.get(1).getAsJsonObject().get("hidden").getAsBoolean());
        assertFalse(dealerCards.get(0).getAsJsonObject().get("hidden").getAsBoolean());
        assertEquals("9\u2665", dealerCards.get(0).getAsJsonObject().get("c").getAsString(),
                "the up card is never masked");

        assertTrue(table.stand(a), "the player must be able to stand");
        advanceUntilPhase(table, Phase.DEALER_TURN, 50);
        assertEquals(Phase.DEALER_TURN, table.getPhase());
        tick(table, BlackjackTable.REVEAL_DELAY + 1);

        assertTrue(table.getDealerHoleRevealed(), "the hole card must be revealed on the dealer turn");
        JsonObject revealed = table.toJson(a, true);
        JsonArray revealedCards = revealed.getAsJsonObject("dealer").getAsJsonArray("cards");
        assertFalse(revealedCards.get(1).getAsJsonObject().get("hidden").getAsBoolean());
        assertEquals("7\u2666", revealedCards.get(1).getAsJsonObject().get("c").getAsString());
    }

    // ------------------------------------------------------------------
    // payouts
    // ------------------------------------------------------------------

    /**
     * A natural pays 3:2: the stake back plus one and a half times the stake.
     */
    @Test
    @DisplayName("player blackjack pays three to two")
    void playerBlackjackPaysThreeToTwo() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.ACE), hearts(Card.Rank.NINE),
                hearts(Card.Rank.KING), clubs(Card.Rank.SEVEN));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.SETTLE, 400);

        assertEquals(Phase.SETTLE, table.getPhase());
        Hand hand = table.getSeats()[table.seatOf(a)].hands.get(0);
        assertEquals(HandState.BLACKJACK, hand.state);
        assertEquals(Outcome.BLACKJACK_WIN, hand.outcome);
        long expected = BET + BET * 3 / 2;
        Map<UUID, Long> payouts = table.drainPayouts();
        long paid = payouts.getOrDefault(a, -1L);
        assertEquals(expected, paid, "3:2 must be credited exactly once");
        assertEquals(250L, expected, "a 100 bet must pay 250 in total");
    }

    /**
     * Two naturals are a push: the stake comes back and nothing else.
     */
    @Test
    @DisplayName("both blackjack is a push")
    void bothBlackjackIsAPush() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.ACE), hearts(Card.Rank.ACE),
                hearts(Card.Rank.KING), clubs(Card.Rank.KING));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.SETTLE, 400);

        assertEquals(Phase.SETTLE, table.getPhase());
        assertTrue(table.getDealerHand().isBlackjack(), "the dealer must hold a natural");
        Hand hand = table.getSeats()[table.seatOf(a)].hands.get(0);
        assertEquals(Outcome.PUSH, hand.outcome);
        assertEquals(BET, hand.payout, "a push returns the stake only");
        long paid = table.drainPayouts().getOrDefault(a, -1L);
        assertEquals(BET, paid, "a push credits the stake and nothing more");
    }

    /**
     * The dealer stands on soft 17 (A+6) and draws no further card.
     */
    @Test
    @DisplayName("dealer stands on soft seventeen")
    void dealerStandsOnSoftSeventeen() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.TEN), hearts(Card.Rank.ACE),
                spades(Card.Rank.EIGHT), clubs(Card.Rank.SIX));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);
        assertTrue(table.stand(a));

        advanceUntilPhase(table, Phase.SETTLE, 400);

        Hand dealer = table.getDealerHand();
        assertEquals(Phase.SETTLE, table.getPhase(), "the dealer must settle instead of drawing");
        assertEquals(2, dealer.cards.size(), "soft 17 stands: the dealer must not draw");
        assertEquals(17, dealer.calculateValue());
        assertTrue(dealer.isSoft());
        assertEquals(Outcome.WIN, table.getSeats()[table.seatOf(a)].hands.get(0).outcome);
    }

    /**
     * The dealer draws while under 17 and then settles.
     */
    @Test
    @DisplayName("dealer draws until seventeen")
    void dealerDrawsUntilSeventeen() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.TEN), hearts(Card.Rank.SIX),
                spades(Card.Rank.EIGHT), clubs(Card.Rank.FIVE),
                diamonds(Card.Rank.KING));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);
        assertTrue(table.stand(a));

        advanceUntilPhase(table, Phase.SETTLE, 400);

        assertEquals(Phase.SETTLE, table.getPhase());
        assertEquals(3, table.getDealerHand().cards.size(), "11 must draw exactly one card to 21");
        assertEquals(21, table.getDealerHand().calculateValue());
        assertEquals(Outcome.LOSE, table.getSeats()[table.seatOf(a)].hands.get(0).outcome);
    }

    /**
     * A busted player loses the stake and is owed nothing.
     */
    @Test
    @DisplayName("player bust loses the stake")
    void playerBustLosesTheStake() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.TEN), hearts(Card.Rank.NINE),
                clubs(Card.Rank.SIX), clubs(Card.Rank.SEVEN),
                diamonds(Card.Rank.TEN));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);
        assertTrue(table.hit(a), "16 must be able to hit");

        Hand hand = table.getSeats()[table.seatOf(a)].hands.get(0);
        assertEquals(HandState.BUST, hand.state);
        assertEquals(Outcome.BUST, hand.outcome);
        assertEquals(26, hand.calculateValue());
        assertEquals(0L, hand.payout, "a bust is never paid");
        assertFalse(table.drainPayouts().containsKey(a), "a busted player must not be credited");
    }

    /**
     * A first drain empties the payout queue; a second returns nothing.
     */
    @Test
    @DisplayName("payouts are drained exactly once")
    void payoutsAreDrainedExactlyOnce() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.TEN), hearts(Card.Rank.NINE),
                hearts(Card.Rank.KING), clubs(Card.Rank.QUEEN));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);
        assertTrue(table.stand(a));
        advanceUntilPhase(table, Phase.SETTLE, 400);
        assertEquals(Phase.SETTLE, table.getPhase());

        Map<UUID, Long> first = table.drainPayouts();
        assertFalse(first.isEmpty(), "the winning stake must be queued");
        long paid = first.getOrDefault(a, -1L);
        assertEquals(BET * 2, paid, "20 beats 19 and pays double");

        Map<UUID, Long> second = table.drainPayouts();
        assertTrue(second.isEmpty(), "a drained queue must stay empty");
    }

    // ------------------------------------------------------------------
    // double and split
    // ------------------------------------------------------------------

    /**
     * Doubling doubles the stake and deals exactly one more card, closing the hand.
     */
    @Test
    @DisplayName("double down doubles the stake and draws exactly one card")
    void doubleDownDoublesTheStakeAndDrawsExactlyOneCard() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.FIVE), hearts(Card.Rank.NINE),
                clubs(Card.Rank.SIX), clubs(Card.Rank.SEVEN),
                diamonds(Card.Rank.KING));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);
        Hand hand = table.getSeats()[table.seatOf(a)].hands.get(0);
        assertEquals(11, hand.calculateValue());

        assertTrue(table.doubleDown(a));

        assertEquals(BET * 2, hand.bet, "the stake must be doubled");
        assertEquals(3, hand.cards.size(), "doubling draws exactly one card");
        assertTrue(hand.doubled);
        assertTrue(hand.isTerminal(), "a doubled hand is closed to further action");
        assertEquals(HandState.STOOD, hand.state);
        assertEquals(21, hand.calculateValue());
    }

    /**
     * Doubling after a hit is refused and leaves the stake untouched.
     */
    @Test
    @DisplayName("double down is rejected after a hit")
    void doubleDownRejectedAfterHit() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.FIVE), hearts(Card.Rank.NINE),
                clubs(Card.Rank.SIX), clubs(Card.Rank.SEVEN),
                diamonds(Card.Rank.TWO));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);
        assertTrue(table.hit(a));

        Hand hand = table.getSeats()[table.seatOf(a)].hands.get(0);
        assertEquals(3, hand.cards.size());
        assertEquals(BET, hand.bet);

        assertFalse(table.doubleDown(a), "a three card hand cannot double");
        assertEquals(BET, hand.bet, "a refused double must not touch the stake");
        assertEquals(3, hand.cards.size());
    }

    /**
     * Splitting a pair produces two hands, each holding one of the original cards.
     */
    @Test
    @DisplayName("split creates a second hand with the matching card")
    void splitCreatesASecondHandWithTheMatchingCard() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.EIGHT), hearts(Card.Rank.NINE),
                clubs(Card.Rank.EIGHT), clubs(Card.Rank.SEVEN),
                diamonds(Card.Rank.TWO), diamonds(Card.Rank.THREE));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);

        assertTrue(table.split(a));

        Seat seat = table.getSeats()[table.seatOf(a)];
        assertEquals(2, seat.hands.size(), "a split must add a second hand");
        for (Hand hand : seat.hands) {
            assertEquals(2, hand.cards.size(), "each split hand is dealt one new card");
            assertEquals(BET, hand.bet, "both hands carry the original stake");
            assertEquals(Card.Rank.EIGHT, hand.cards.get(0).card.rank,
                    "each hand keeps one of the original eights");
        }
        assertEquals(1, seat.hands.get(1).handIndex, "the split hand is laid out second");
    }

    /**
     * Unpaired cards cannot be split.
     */
    @Test
    @DisplayName("split is rejected on unpaired cards")
    void splitRejectedOnUnpairedCards() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.EIGHT), hearts(Card.Rank.NINE),
                clubs(Card.Rank.NINE), clubs(Card.Rank.SEVEN));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);

        assertFalse(table.split(a), "8 and 9 are not a pair");
        assertEquals(1, table.getSeats()[table.seatOf(a)].hands.size());
        assertEquals(17, table.getSeats()[table.seatOf(a)].hands.get(0).calculateValue());
    }

    /**
     * Split aces get one card each and are closed immediately.
     */
    @Test
    @DisplayName("split aces are dealt one card and closed")
    void splitAcesAreDealtOneCardAndClosed() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.ACE), hearts(Card.Rank.NINE),
                clubs(Card.Rank.ACE), clubs(Card.Rank.SEVEN),
                diamonds(Card.Rank.TWO), diamonds(Card.Rank.THREE));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);

        assertTrue(table.split(a));

        Seat seat = table.getSeats()[table.seatOf(a)];
        assertEquals(2, seat.hands.size());
        for (Hand hand : seat.hands) {
            assertEquals(2, hand.cards.size(), "a split ace gets exactly one card");
            assertEquals(HandState.STOOD, hand.state, "a split ace hand is closed at once");
        }
        assertTrue(seat.isFinished(), "the player has no decision left after splitting aces");
    }

    // ------------------------------------------------------------------
    // table flow
    // ------------------------------------------------------------------

    /**
     * A busted seat is skipped, so the turn moves on to the next player instead of hanging.
     */
    @Test
    @DisplayName("busted seats are skipped so the table advances")
    void bustSeatsAreSkippedSoTheTableAdvances() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        UUID b = player(2);
        UUID c = player(3);
        table.join(a, "Ada");
        table.join(b, "Boris");
        table.join(c, "Cleo");
        // Seat, seat, seat, dealer up; seat, seat, seat, dealer hole; then the hit.
        table.forceShoe(
                spades(Card.Rank.TEN), diamonds(Card.Rank.QUEEN), hearts(Card.Rank.JACK),
                hearts(Card.Rank.NINE),
                clubs(Card.Rank.SIX), spades(Card.Rank.NINE), spades(Card.Rank.EIGHT),
                clubs(Card.Rank.SEVEN),
                diamonds(Card.Rank.TEN));

        assertTrue(table.placeBet(a, BET));
        assertTrue(table.placeBet(b, BET));
        assertTrue(table.placeBet(c, BET), "the last missing bet starts the round");
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);

        assertEquals(table.seatOf(a), table.getActiveSeat(), "the first seat acts first");
        assertTrue(table.hit(a), "16 must be able to hit");

        Hand busted = table.getSeats()[table.seatOf(a)].hands.get(0);
        assertEquals(HandState.BUST, busted.state);
        assertEquals(26, busted.calculateValue());
        assertEquals(1, table.getActiveSeat(), "the busted seat must be skipped");
        assertEquals(Phase.PLAYER_TURNS, table.getPhase());
    }

    /**
     * After the settle hold the table wipes the hands and reopens betting.
     */
    @Test
    @DisplayName("settle returns to betting after the hold")
    void settleReturnsToBettingAfterTheHold() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.TEN), hearts(Card.Rank.NINE),
                hearts(Card.Rank.KING), clubs(Card.Rank.QUEEN));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);
        assertTrue(table.stand(a));
        advanceUntilPhase(table, Phase.SETTLE, 400);
        assertEquals(Phase.SETTLE, table.getPhase());

        tick(table, BlackjackTable.SETTLE_HOLD + 2);

        assertEquals(Phase.BETTING, table.getPhase(), "the table must reopen for bets");
        for (Seat seat : table.getSeats()) {
            assertTrue(seat.hands.isEmpty(), "settled hands must be cleared");
        }
        assertTrue(table.getDealerHand().cards.isEmpty(), "the dealer hand must be cleared");
        assertFalse(table.getDealerHoleRevealed());
    }

    /**
     * A viewer who never sat down sees no seat and no hole card.
     */
    @Test
    @DisplayName("a spectator sees no seat and no hole card")
    void spectatorSeesNoSeatAndNoHoleCard() {
        BlackjackTable table = tableWithoutBots();
        UUID a = player(1);
        UUID spectator = player(99);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.TEN), hearts(Card.Rank.NINE),
                spades(Card.Rank.EIGHT), diamonds(Card.Rank.SEVEN));

        table.placeBet(a, BET);
        advanceUntilPhase(table, Phase.PLAYER_TURNS, 200);

        JsonObject json = table.toJson(spectator, false);

        assertFalse(json.get("seated").getAsBoolean(), "a spectator is not seated");
        assertEquals(-1, json.get("mySeat").getAsInt());
        JsonArray dealerCards = json.getAsJsonObject("dealer").getAsJsonArray("cards");
        assertEquals(2, dealerCards.size());
        assertTrue(dealerCards.get(1).getAsJsonObject().get("hidden").getAsBoolean(),
                "the hole card must stay hidden from a spectator");
        assertEquals("??", dealerCards.get(1).getAsJsonObject().get("c").getAsString());
        assertEquals(-1, table.seatOf(spectator));
    }

    /**
     * One full round with a live player terminates and never throws.
     */
    @Test
    @DisplayName("a full round terminates")
    void fullRoundTerminates() {
        BlackjackTable table = new BlackjackTable();
        UUID a = player(1);
        int mySeat = table.join(a, "Ada");
        assertEquals(0, mySeat);
        assertTrue(table.placeBet(a, BET), "the only player betting starts the round");
        table.requestDeal();
        assertEquals(Phase.DEALING, table.getPhase(), "the table must be dealing");

        boolean sawSettle = false;
        try {
            for (int i = 0; i < 4000 && !sawSettle; i++) {
                if (table.getPhase() == Phase.PLAYER_TURNS && table.getActiveSeat() == mySeat) {
                    Hand hand = table.getSeats()[mySeat].activeHand();
                    if (hand != null && hand.state == HandState.ACTIVE && hand.calculateValue() < 17) {
                        table.hit(a);
                    } else {
                        table.stand(a);
                    }
                }
                table.tickServer();
                sawSettle = table.getPhase() == Phase.SETTLE;
            }
        } catch (RuntimeException e) {
            fail("the table threw while playing a round: " + e);
        }

        assertTrue(sawSettle, "the round must reach SETTLE within 4000 ticks");
        Phase phase = table.getPhase();
        assertTrue(phase == Phase.SETTLE || phase == Phase.BETTING,
                "the table must end up settled or back in betting, was " + phase);
    }
}
