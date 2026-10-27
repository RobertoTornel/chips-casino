package dev.casino.game;

import dev.casino.game.BaccaratEngine.BetTarget;
import dev.casino.game.BaccaratEngine.Outcome;
import dev.casino.game.BaccaratTable.Dealt;
import dev.casino.game.BaccaratTable.Phase;
import dev.casino.game.BaccaratTable.Seat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the baccarat rule set the server owns: seating, bet limits, the paced deal, the
 * naturals, the player's draw, the banker's third-card table, the commission, the push on a
 * tie and the payout queue.
 *
 * <p>{@link BaccaratTable}, {@link Card} and {@link Deck} are plain Java, so every rule is
 * checkable on a bare JVM. Hands are made deterministic with
 * {@link BaccaratTable#forceShoe(Card...)} — it pins the shoe, first element drawn first, and
 * survives the round-start reshuffle. The deal is dealer-paced: one card every
 * {@value BaccaratTable#DEAL_INTERVAL} ticks, so the four opening cards need a few dozen ticks
 * and the settled tableau another {@value BaccaratTable#SETTLE_HOLD} before betting reopens.
 * Nothing sleeps.
 *
 * <p>Tests that assert on cards switch the house bots off
 * ({@link BaccaratTable#toggleAutoFillBots()}), because they bet from an unseeded
 * {@link java.util.Random}. The two tests that are about the bots leave them on deliberately.
 */
class BaccaratTableTest {

    private static final long BET = 100L;

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Runs the server clock n times. */
    private static void tick(BaccaratTable table, int times) {
        for (int i = 0; i < times; i++) {
            table.tickServer();
        }
    }

    /**
     * Ticks until the table reaches the target phase, or the tick budget runs out. Asserts
     * nothing; returns whether the phase was reached.
     */
    private static boolean advanceUntilPhase(BaccaratTable table, Phase target, int maxTicks) {
        for (int i = 0; i < maxTicks && table.getPhase() != target; i++) {
            table.tickServer();
        }
        return table.getPhase() == target;
    }

    /**
     * As {@link #advanceUntilPhase}, and on every beat checks the hard ceiling as well: neither
     * side may ever be holding a fourth card. Returns whether the phase was reached.
     */
    private static boolean advanceWatchingCardCount(BaccaratTable table, Phase target, int maxTicks) {
        for (int i = 0; i < maxTicks && table.getPhase() != target; i++) {
            table.tickServer();
            assertTrue(table.getPlayerHand().size() <= 3, "the player was dealt a fourth card");
            assertTrue(table.getBankerHand().size() <= 3, "the banker was dealt a fourth card");
        }
        return table.getPhase() == target;
    }

    /** A fresh table whose empty chairs stay empty, so seat and deal order are fixed. */
    private static BaccaratTable tableWithoutBots() {
        BaccaratTable table = new BaccaratTable();
        if (table.isAutoFillBots()) {
            table.toggleAutoFillBots();
        }
        return table;
    }

    /** Deterministic player id; the house players live in the 0x8AC seat space, so these never collide. */
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

    /** Cards as they lie on the felt, with the arrival tick left at zero. */
    private static List<Dealt> dealt(Card... cards) {
        List<Dealt> hand = new ArrayList<>();
        for (Card card : cards) {
            hand.add(new Dealt(card, 0));
        }
        return hand;
    }

    /**
     * The published banker third-card table, one row per banker total: the point values of the
     * player's third card on which the banker draws.
     */
    private static final String[] BANKER_DRAWS_ON = {
            "0123456789", // 0 draws on anything
            "0123456789", // 1 draws on anything
            "0123456789", // 2 draws on anything
            "012345679",  // 3 draws unless the third card is an eight
            "234567",     // 4
            "4567",       // 5
            "67",         // 6
            "",           // 7 stands
            "",           // 8 and 9 are naturals: the tableau never consults the table
            ""
    };

    private static boolean publishedBankerDraw(int bankerTotal, int playerThird) {
        return BANKER_DRAWS_ON[bankerTotal].indexOf('0' + playerThird) >= 0;
    }

    /**
     * What {@link BaccaratEngine} would pay for the same line, computed from the engine's own
     * published multipliers rather than from a second copy of the table's code.
     */
    private static long enginePayout(BetTarget target, long bet, Outcome outcome) {
        if (outcome == Outcome.TIE) {
            return target == BetTarget.TIE
                    ? bet + (long) (bet * BetTarget.TIE.payoutMultiplier)
                    : bet; // the main lines push
        }
        if (outcome == Outcome.PLAYER_WIN && target == BetTarget.PLAYER) {
            return bet + (long) (bet * BetTarget.PLAYER.payoutMultiplier);
        }
        if (outcome == Outcome.BANKER_WIN && target == BetTarget.BANKER) {
            return bet + (long) (bet * BetTarget.BANKER.payoutMultiplier);
        }
        return 0L;
    }

    /**
     * Plays exactly one round: waits for the betting window, lays a fresh stake on the player
     * line and ticks until the settled hands are up. Returns false when any leg of that failed.
     */
    private static boolean playRound(BaccaratTable table, UUID bettor, long stake) {
        if (!advanceUntilPhase(table, Phase.BETTING, 800)) {
            return false;
        }
        if (!table.placeBet(bettor, BetTarget.PLAYER, stake)) {
            return false;
        }
        return advanceWatchingCardCount(table, Phase.SETTLE, 800);
    }

    // ------------------------------------------------------------------
    // seating and betting
    // ------------------------------------------------------------------

    /**
     * Seats fill in order, a re-join keeps its seat, and a seventh player is refused.
     */
    @Test
    @DisplayName("join seats players in order and refuses the seventh")
    void joinSeatsPlayersInOrderAndRefusesTheSeventh() {
        BaccaratTable table = tableWithoutBots();
        assertEquals(0, table.join(player(1), "Ada"));
        assertEquals(1, table.join(player(2), "Boris"));
        assertEquals(0, table.join(player(1), "Ada"), "re-joining must return the same seat");
        assertEquals(2, table.join(player(3), "Cleo"));
        assertEquals(3, table.join(player(4), "Dmitri"));
        assertEquals(4, table.join(player(5), "Esme"));
        assertEquals(5, table.join(player(6), "Frank"));
        assertEquals(-1, table.join(player(7), "Seventh"), "a full table must refuse");
        assertEquals(BaccaratTable.MAX_SEATS, table.getSeats().length);
        assertEquals(-1, table.seatOf(player(7)));
        assertEquals(player(1), table.getTableOwner(), "the first player owns the table");
    }

    /**
     * Leaving gives the chair back to the next player in line, and ownership passes on.
     */
    @Test
    @DisplayName("leave frees the seat for the next player")
    void leaveFreesTheSeat() {
        BaccaratTable table = tableWithoutBots();
        assertEquals(0, table.join(player(1), "Ada"));
        assertEquals(1, table.join(player(2), "Boris"));
        table.leave(player(1));
        assertEquals(-1, table.seatOf(player(1)));
        assertEquals(1, table.seatOf(player(2)), "the remaining player must not be shifted");
        assertEquals(0, table.join(player(3), "Cleo"), "the freed chair must be handed to the next player");
        assertEquals(player(2), table.getTableOwner(), "ownership must pass to the remaining player");
        assertEquals(2, table.join(player(4), "Dmitri"), "a later player takes the first free chair");
    }

    /**
     * The stakes are bounded at both ends: a penny under the minimum and a penny over the
     * maximum are both refused, and a refused stake leaves the felt untouched.
     */
    @Test
    @DisplayName("place bet rejects stakes outside the limits")
    void placeBetRejectsOutsideTheLimits() {
        BaccaratTable table = tableWithoutBots();
        UUID a = player(1);
        UUID b = player(2);
        table.join(a, "Ada");
        table.join(b, "Boris");

        assertFalse(table.placeBet(a, BetTarget.PLAYER, BaccaratTable.MIN_BET - 1),
                "a stake under the minimum must be refused");
        assertFalse(table.placeBet(a, BetTarget.PLAYER, BaccaratTable.MAX_BET + 1),
                "a stake over the maximum must be refused");
        assertFalse(table.placeBet(a, BetTarget.PLAYER, 0L), "a zero stake is not a bet");
        assertFalse(table.placeBet(a, BetTarget.PLAYER, -BET), "a negative stake must be refused");
        assertFalse(table.placeBet(player(9), BetTarget.PLAYER, BET), "an unseated player cannot bet");
        assertEquals(0L, table.getSeats()[table.seatOf(a)].bet, "a refused stake must not touch the felt");

        assertTrue(table.placeBet(a, BetTarget.PLAYER, BaccaratTable.MIN_BET),
                "the minimum must be accepted");
        assertEquals(BaccaratTable.MIN_BET, table.getSeats()[table.seatOf(a)].bet);
        assertTrue(table.placeBet(a, BetTarget.BANKER, BaccaratTable.MAX_BET),
                "the maximum must be accepted");
        assertEquals(BaccaratTable.MAX_BET, table.getSeats()[table.seatOf(a)].bet,
                "a new stake must replace the old one");
        assertEquals(BetTarget.BANKER, table.getSeats()[table.seatOf(a)].target);
        assertEquals(Phase.BETTING, table.getPhase(),
                "one bet on a table of two players must not start the deal");
    }

    // ------------------------------------------------------------------
    // the tableau
    // ------------------------------------------------------------------

    /**
     * Point values: aces count one, tens and pictures count zero, and the total never goes
     * above nine.
     */
    @Test
    @DisplayName("hand total is modulo ten")
    void handTotalIsModuloTen() {
        assertEquals(5, BaccaratTable.handTotal(dealt(spades(Card.Rank.SEVEN), hearts(Card.Rank.EIGHT))),
                "7 + 8 must total 5, not 15");
        assertEquals(0, BaccaratTable.handTotal(dealt(spades(Card.Rank.TEN), hearts(Card.Rank.KING))),
                "tens and pictures count zero");
        assertEquals(0, BaccaratTable.handTotal(dealt(spades(Card.Rank.QUEEN), hearts(Card.Rank.JACK))),
                "pictures count zero as well");
        assertEquals(7, BaccaratTable.handTotal(dealt(spades(Card.Rank.TEN), hearts(Card.Rank.SEVEN))),
                "a ten leaves the other card standing");
        assertEquals(1, BaccaratTable.handTotal(dealt(spades(Card.Rank.ACE))), "an ace counts one");
        assertEquals(7, BaccaratTable.handTotal(
                        dealt(spades(Card.Rank.NINE), hearts(Card.Rank.NINE), clubs(Card.Rank.NINE))),
                "three nines are twenty-seven, so seven");
        assertEquals(0, BaccaratTable.handTotal(dealt()), "an empty hand totals zero");
    }

    /**
     * A natural eight or nine ends the hand on the fourth card: no third card is ever dealt and
     * the table goes straight to settlement.
     */
    @Test
    @DisplayName("a natural eight or nine stops the hand")
    void naturalEightOrNineStopsTheHand() {
        BaccaratTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.FOUR), hearts(Card.Rank.TWO),
                clubs(Card.Rank.FOUR), diamonds(Card.Rank.THREE));

        table.placeBet(a, BetTarget.PLAYER, BET);
        assertTrue(advanceUntilPhase(table, Phase.SETTLE, 200),
                "a natural must settle without a third card");

        assertEquals(8, table.getPlayerTotal(), "four and four is a natural eight");
        assertEquals(5, table.getBankerTotal(), "two and three is five");
        assertEquals(2, table.getPlayerHand().size(), "a natural must not draw");
        assertEquals(2, table.getBankerHand().size(), "the banker must not draw into a natural");
        assertEquals(Outcome.PLAYER_WIN, table.getOutcome());

        tick(table, 30);
        assertEquals(Phase.SETTLE, table.getPhase(), "the hands must still be up");
        assertEquals(2, table.getPlayerHand().size(), "no third card may arrive during the hold");
        assertEquals(2, table.getBankerHand().size(), "no third card may arrive during the hold");
    }

    /**
     * The player takes a third card on five or less and stands on six or seven.
     */
    @Test
    @DisplayName("player draws on five and stands on six")
    void playerDrawsOnFiveAndStandsOnSix() {
        BaccaratTable draws = tableWithoutBots();
        UUID a = player(1);
        draws.join(a, "Ada");
        draws.forceShoe(
                spades(Card.Rank.TWO), hearts(Card.Rank.TWO),
                clubs(Card.Rank.THREE), diamonds(Card.Rank.TWO),
                hearts(Card.Rank.EIGHT));
        draws.placeBet(a, BetTarget.PLAYER, BET);

        assertTrue(advanceUntilPhase(draws, Phase.THIRD_CARD, 100));
        assertEquals(5, draws.getPlayerTotal(), "two and three is five");
        assertEquals(2, draws.getPlayerHand().size(), "the third card has not landed yet");

        assertTrue(advanceUntilPhase(draws, Phase.SETTLE, 100));
        assertEquals(3, draws.getPlayerHand().size(), "five must draw a third card");
        assertEquals(3, draws.getPlayerTotal(), "five plus eight wraps to three");

        BaccaratTable stands = tableWithoutBots();
        stands.join(a, "Ada");
        stands.forceShoe(
                spades(Card.Rank.THREE), hearts(Card.Rank.TWO),
                clubs(Card.Rank.THREE), diamonds(Card.Rank.TWO),
                hearts(Card.Rank.EIGHT));
        stands.placeBet(a, BetTarget.PLAYER, BET);

        assertTrue(advanceUntilPhase(stands, Phase.THIRD_CARD, 100));
        assertEquals(6, stands.getPlayerTotal(), "three and three is six");
        assertTrue(advanceUntilPhase(stands, Phase.SETTLE, 100));
        assertEquals(2, stands.getPlayerHand().size(), "six must stand");
        assertEquals(6, stands.getPlayerTotal(), "six must stand");
    }

    /**
     * The banker's third card at a total of three hinges on the player's third card: an eight
     * stops the draw, a seven lets the banker take one.
     */
    @Test
    @DisplayName("banker draws on three unless the player third card is eight")
    void bankerDrawsOnThreeUnlessThePlayerThirdIsEight() {
        BaccaratTable stands = tableWithoutBots();
        UUID a = player(1);
        stands.join(a, "Ada");
        stands.forceShoe(
                spades(Card.Rank.TWO), hearts(Card.Rank.TWO),
                clubs(Card.Rank.TWO), spades(Card.Rank.ACE),
                hearts(Card.Rank.EIGHT));
        stands.placeBet(a, BetTarget.PLAYER, BET);
        assertTrue(advanceUntilPhase(stands, Phase.SETTLE, 200));

        assertEquals(2, stands.getPlayerTotal(), "four plus the third-card eight wraps to two");
        assertEquals(3, stands.getPlayerHand().size(), "four must draw a third card");
        assertEquals(3, stands.getBankerTotal(), "two and an ace is three");
        assertEquals(2, stands.getBankerHand().size(),
                "an eight from the player must stop the banker on three");

        BaccaratTable draws = tableWithoutBots();
        draws.join(a, "Ada");
        draws.forceShoe(
                spades(Card.Rank.TWO), hearts(Card.Rank.TWO),
                clubs(Card.Rank.TWO), spades(Card.Rank.ACE),
                hearts(Card.Rank.SEVEN), diamonds(Card.Rank.FIVE));
        draws.placeBet(a, BetTarget.PLAYER, BET);
        assertTrue(advanceUntilPhase(draws, Phase.SETTLE, 200));

        assertEquals(3, draws.getPlayerHand().size(), "four must draw a third card");
        assertEquals(3, draws.getBankerHand().size(),
                "a seven from the player must let the banker draw");
        assertEquals(8, draws.getBankerTotal(), "three plus five is eight");
    }

    /**
     * The published banker third-card table, every banker total against every player third
     * card: 0-2 always draw, 3 draws unless the third card is an 8, 4 draws on 2-7, 5 on 4-7,
     * 6 on 6-7 and 7 never draws.
     */
    @Test
    @DisplayName("banker third card table matches the published rules")
    void bankerThirdCardTable() {
        for (int bankerTotal = 0; bankerTotal <= 9; bankerTotal++) {
            for (int playerThird = 0; playerThird <= 9; playerThird++) {
                assertEquals(publishedBankerDraw(bankerTotal, playerThird),
                        BaccaratTable.bankerDraws(bankerTotal, playerThird),
                        "banker " + bankerTotal + " against a player third card of " + playerThird);
            }
        }
    }

    // ------------------------------------------------------------------
    // payouts
    // ------------------------------------------------------------------

    /**
     * The multiplayer payout equals what {@link BaccaratEngine} would pay for the same line:
     * even money on the player, even money less the 5% commission on the banker and 8:1 on the
     * tie, with the main lines pushing on a tie.
     */
    @Test
    @DisplayName("payouts match the single player engine")
    void payoutsMatchTheSinglePlayerEngine() {
        long[] stakes = {BaccaratTable.MIN_BET, 99L, BET, 101L, BaccaratTable.MAX_BET};
        for (long stake : stakes) {
            for (BetTarget target : BetTarget.values()) {
                for (Outcome outcome : Outcome.values()) {
                    assertEquals(enginePayout(target, stake, outcome),
                            BaccaratTable.payoutFor(target, stake, outcome),
                            target + " on " + outcome + " for a stake of " + stake);
                }
            }
        }

        assertEquals(200L, BaccaratTable.payoutFor(BetTarget.PLAYER, BET, Outcome.PLAYER_WIN),
                "the player line pays even money");
        assertEquals(195L, BaccaratTable.payoutFor(BetTarget.BANKER, BET, Outcome.BANKER_WIN),
                "the banker line pays even money less the 5% commission");
        assertEquals(900L, BaccaratTable.payoutFor(BetTarget.TIE, BET, Outcome.TIE),
                "the tie pays 8:1");
        assertEquals(BET, BaccaratTable.payoutFor(BetTarget.PLAYER, BET, Outcome.TIE),
                "a main line pushes on a tie");
        assertEquals(BET, BaccaratTable.payoutFor(BetTarget.BANKER, BET, Outcome.TIE),
                "the banker line pushes on a tie too");
        assertEquals(0L, BaccaratTable.payoutFor(BetTarget.BANKER, BET, Outcome.PLAYER_WIN),
                "a losing line is owed nothing");
    }

    /**
     * On a tie both main lines get their stake back — a net change of zero — while the tie line
     * is paid eight to one on top of its stake.
     */
    @Test
    @DisplayName("main line bets push on a tie")
    void mainLineBetsPushOnATie() {
        BaccaratTable table = tableWithoutBots();
        UUID playerBettor = player(1);
        UUID bankerBettor = player(2);
        UUID tieBettor = player(3);
        table.join(playerBettor, "Ada");
        table.join(bankerBettor, "Boris");
        table.join(tieBettor, "Cleo");
        table.forceShoe(
                spades(Card.Rank.FOUR), hearts(Card.Rank.FIVE),
                clubs(Card.Rank.FOUR), diamonds(Card.Rank.THREE));

        assertTrue(table.placeBet(playerBettor, BetTarget.PLAYER, BET));
        assertTrue(table.placeBet(bankerBettor, BetTarget.BANKER, BET));
        assertTrue(table.placeBet(tieBettor, BetTarget.TIE, BET));

        assertTrue(advanceUntilPhase(table, Phase.SETTLE, 200));
        assertEquals(Outcome.TIE, table.getOutcome(), "four and four against five and three is a tie");
        assertEquals(8, table.getPlayerTotal());
        assertEquals(8, table.getBankerTotal());

        Seat playerSeat = table.getSeats()[table.seatOf(playerBettor)];
        Seat bankerSeat = table.getSeats()[table.seatOf(bankerBettor)];
        Seat tieSeat = table.getSeats()[table.seatOf(tieBettor)];

        assertEquals(BET, playerSeat.payout, "the player stake comes back");
        assertEquals(0L, playerSeat.netChange, "a push is not a loss");
        assertEquals(BET, bankerSeat.payout, "the banker stake comes back");
        assertEquals(0L, bankerSeat.netChange, "a push is not a win");
        assertEquals(BET * 9, tieSeat.payout, "the tie stake comes back plus eight to one");
        assertEquals(BET * 8, tieSeat.netChange, "the tie line nets eight times the stake");

        Map<UUID, Long> payouts = table.drainPayouts();
        long paidPlayer = payouts.getOrDefault(playerBettor, -1L);
        long paidBanker = payouts.getOrDefault(bankerBettor, -1L);
        long paidTie = payouts.getOrDefault(tieBettor, -1L);
        assertEquals(BET, paidPlayer, "the pushed player stake is still credited");
        assertEquals(BET, paidBanker, "the pushed banker stake is still credited");
        assertEquals(BET * 9, paidTie, "the tie is paid in full");
    }

    /**
     * A first drain empties the payout queue; a second returns nothing.
     */
    @Test
    @DisplayName("payouts are drained exactly once")
    void payoutsAreDrainedExactlyOnce() {
        BaccaratTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.FOUR), hearts(Card.Rank.TWO),
                clubs(Card.Rank.FIVE), diamonds(Card.Rank.THREE));

        table.placeBet(a, BetTarget.PLAYER, BET);
        assertTrue(advanceUntilPhase(table, Phase.SETTLE, 200));

        Map<UUID, Long> first = table.drainPayouts();
        assertEquals(1, first.size(), "one winning seat is queued");
        long paid = first.getOrDefault(a, -1L);
        assertEquals(BET * 2, paid, "nine beats five and pays double");

        assertTrue(table.drainPayouts().isEmpty(), "a drained queue must stay empty");
    }

    // ------------------------------------------------------------------
    // the house players and the clock
    // ------------------------------------------------------------------

    /**
     * The house players have no account, so a bot that wins must never reach the queue the
     * server credits.
     */
    @Test
    @DisplayName("bots never appear in the payout queue")
    void botsNeverAppearInThePayoutQueue() {
        BaccaratTable table = new BaccaratTable();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.FOUR), hearts(Card.Rank.TWO),
                clubs(Card.Rank.FIVE), diamonds(Card.Rank.THREE));

        // Long enough for every house player to take a stake, so the round has bots with money on it.
        tick(table, 40);
        assertTrue(table.placeBet(a, BetTarget.PLAYER, BET), "the human must be able to bet");
        assertTrue(advanceUntilPhase(table, Phase.SETTLE, 800), "the round must settle");

        Map<UUID, Long> payouts = table.drainPayouts();
        assertFalse(payouts.isEmpty(), "the winning human must be paid");
        assertTrue(payouts.containsKey(a), "the human's win must be queued");
        for (UUID id : payouts.keySet()) {
            int seat = table.seatOf(id);
            assertTrue(seat >= 0, "a payout for a player who is not seated");
            assertFalse(table.getSeats()[seat].isBot, "a house player must never be credited");
        }
    }

    /**
     * The settled tableau stays up for the hold and then the table reopens for betting with the
     * felt swept clean.
     */
    @Test
    @DisplayName("settle returns to betting after the hold")
    void settleReturnsToBettingAfterTheHold() {
        BaccaratTable table = tableWithoutBots();
        UUID a = player(1);
        table.join(a, "Ada");
        table.forceShoe(
                spades(Card.Rank.FOUR), hearts(Card.Rank.TWO),
                clubs(Card.Rank.FIVE), diamonds(Card.Rank.THREE));

        table.placeBet(a, BetTarget.PLAYER, BET);
        assertTrue(advanceUntilPhase(table, Phase.SETTLE, 200));
        assertEquals(2, table.getPlayerHand().size(), "nine is a natural");

        tick(table, BaccaratTable.SETTLE_HOLD - 1);
        assertEquals(Phase.SETTLE, table.getPhase(), "the hands must stay up for the whole hold");
        assertEquals(2, table.getPlayerHand().size(), "the tableau must not move during the hold");

        table.tickServer();
        assertEquals(Phase.BETTING, table.getPhase(), "betting must reopen once the hold expires");
        assertEquals(0L, table.getSeats()[table.seatOf(a)].bet, "the stake must be off the felt");
        assertEquals(0, table.getPlayerHand().size(), "the tableau must be swept");
        assertEquals(0, table.getBankerHand().size(), "the tableau must be swept");
        assertEquals(0, table.getPlayerTotal());
        assertEquals(0, table.getBankerTotal());
    }

    /**
     * Two hundred rounds with the bots on and a scripted human: every round must settle and hand
     * the table back inside the tick budget, and no hand may ever pass three cards.
     */
    @Test
    @DisplayName("a full round always terminates")
    void aFullRoundAlwaysTerminates() {
        BaccaratTable table = new BaccaratTable();
        UUID human = player(1);
        table.join(human, "Ada");

        for (int round = 1; round <= 200; round++) {
            assertTrue(playRound(table, human, BET), "round " + round + " never settled");
            assertEquals(Phase.SETTLE, table.getPhase(),
                    "round " + round + " must end on the settled hands");
            assertTrue(table.getPlayerHand().size() <= 3,
                    "round " + round + " dealt the player a fourth card");
            assertTrue(table.getBankerHand().size() <= 3,
                    "round " + round + " dealt the banker a fourth card");
            assertTrue(table.getRoundNumber() >= round, "round " + round + " never started");
        }
        assertTrue(table.getRoundNumber() >= 200, "two hundred rounds must all have been dealt");
    }

    /**
     * Three hundred played rounds: the tableau is capped at three cards a side, and no hand is
     * ever shorter than the two it opens with.
     */
    @Test
    @DisplayName("three cards a side is the maximum")
    void threeCardsPerSideIsTheMaximum() {
        BaccaratTable table = new BaccaratTable();
        UUID human = player(1);
        table.join(human, "Ada");

        int tallestPlayerHand = 0;
        int tallestBankerHand = 0;
        for (int round = 1; round <= 300; round++) {
            assertTrue(playRound(table, human, BET), "round " + round + " never settled");
            tallestPlayerHand = Math.max(tallestPlayerHand, table.getPlayerHand().size());
            tallestBankerHand = Math.max(tallestBankerHand, table.getBankerHand().size());
            assertTrue(tallestPlayerHand <= 3, "the player was dealt a fourth card in round " + round);
            assertTrue(tallestBankerHand <= 3, "the banker was dealt a fourth card in round " + round);
        }
        assertTrue(tallestPlayerHand >= 2, "a player hand is never shorter than two cards");
        assertTrue(tallestBankerHand >= 2, "a banker hand is never shorter than two cards");
    }
}
