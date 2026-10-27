package dev.casino.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Multiplayer blackjack table: six seats on a semicircle facing one dealer.
 *
 * <p>Everything here is pure Java with no Minecraft types, so the whole rule set —
 * dealing order, split/double restrictions, the dealer's S17 draw loop, payouts and
 * the animation clock — is unit-testable on a plain JVM.
 *
 * <h2>Authority</h2>
 * The server owns the shoe ({@link #deck}), every shuffle, and every payout. Clients
 * submit a bet and one of five verbs (hit, stand, double, split, leave); they never
 * see the hole card until {@link #getDealerHoleRevealed()} turns true.
 *
 * <h2>Animation clock</h2>
 * {@link #tickServer()} runs 20 times a second and drives the table as a state machine
 * with explicit beats: one card every {@value #DEAL_INTERVAL} ticks, the hole card
 * flip at {@value #REVEAL_DELAY} ticks into the dealer's turn, one dealer draw per
 * {@value #DEALER_DRAW_INTERVAL} ticks. Every dealt card records the tick it landed
 * ({@link Dealt#tick}), so the client can slide it in from the shoe and the result is
 * identical on every screen at the table.
 */
public class BlackjackTable {
    private static final Gson GSON = new GsonBuilder().create();

    public static final int MAX_SEATS = 6;

    /** One card leaves the shoe every this many ticks (0.25 s). */
    public static final int DEAL_INTERVAL = 5;
    /** Ticks between the dealer's hole-card flip and its first draw. */
    public static final int REVEAL_DELAY = 10;
    /** Ticks between successive dealer draws. */
    public static final int DEALER_DRAW_INTERVAL = 8;
    /** How long the settled table stays on screen before the next betting window. */
    public static final int SETTLE_HOLD = 60;
    /** Betting window length before the table deals on its own. */
    public static final int BETTING_TICKS = 400;
    /** A bot takes this long to decide. */
    public static final int BOT_THINK = 14;
    /** Dealer stands on all 17 (S17). */
    public static final int DEALER_STANDS_ON = 17;

    public static final long MIN_BET = 10;
    public static final long MAX_BET = 10_000;

    public enum Phase {
        WAITING_FOR_PLAYERS,
        BETTING,
        DEALING,
        PLAYER_TURNS,
        DEALER_TURN,
        SETTLE
    }

    public enum HandState {
        ACTIVE,
        STOOD,
        BUST,
        BLACKJACK,
        SURRENDERED
    }

    public enum Outcome {
        NONE,
        WIN,
        LOSE,
        PUSH,
        BLACKJACK_WIN,
        DEALER_BUST,
        BUST
    }

    /** A card plus the tick it landed on, so the client can animate its arrival. */
    public static class Dealt {
        public final Card card;
        public final int tick;
        public final boolean faceDown;

        public Dealt(Card card, int tick, boolean faceDown) {
            this.card = card;
            this.tick = tick;
            this.faceDown = faceDown;
        }
    }

    /** One hand. A seat has several only after a split. */
    public static class Hand {
        public final List<Dealt> cards = new ArrayList<>();
        public long bet;
        public boolean doubled;
        public boolean fromSplit;
        public HandState state = HandState.ACTIVE;
        public Outcome outcome = Outcome.NONE;
        public long payout;
        /** Hand index this split produced (0 = original), used for layout on the client. */
        public int handIndex;

        public void add(Card card, int tick, boolean faceDown) {
            cards.add(new Dealt(card, tick, faceDown));
        }

        public int calculateValue() {
            int total = 0;
            int aces = 0;
            for (Dealt d : cards) {
                Card.Rank rank = d.card.rank;
                if (rank == Card.Rank.ACE) {
                    aces++;
                    total += 11;
                } else if (rank.value >= 10) {
                    total += 10;
                } else {
                    total += rank.value;
                }
            }
            while (total > 21 && aces > 0) {
                total -= 10;
                aces--;
            }
            return total;
        }

        /** Value with aces counted as 11 — only correct for display of a soft hand. */
        public boolean isSoft() {
            int total = 0;
            int aces = 0;
            for (Dealt d : cards) {
                Card.Rank rank = d.card.rank;
                if (rank == Card.Rank.ACE) {
                    aces++;
                    total += 11;
                } else if (rank.value >= 10) {
                    total += 10;
                } else {
                    total += rank.value;
                }
            }
            while (total > 21 && aces > 0) {
                total -= 10;
                aces--;
            }
            return aces > 0 && total <= 21;
        }

        public boolean isBust() {
            return calculateValue() > 21;
        }

        public boolean isBlackjack() {
            return cards.size() == 2 && !fromSplit && calculateValue() == 21;
        }

        public boolean canDouble() {
            return !doubled && cards.size() == 2;
        }

        public boolean canSplit() {
            return cards.size() == 2
                    && cards.get(0).card.rank == cards.get(1).card.rank
                    && !fromSplit;
        }

        public boolean isTerminal() {
            return state != HandState.ACTIVE;
        }

        /** Ace showing on the first card — what the client paints the corner with. */
        public Card firstCard() {
            return cards.isEmpty() ? null : cards.get(0).card;
        }
    }

    public static class Seat {
        public final int index;
        public UUID playerId;
        public String name = "";
        public boolean isBot;
        public long bet;
        public final List<Hand> hands = new ArrayList<>();
        public int activeHand;
        /** Net chips won/lost this round, for the client's floating +/- label. */
        public long netChange;
        /** Ticks since this seat last acted, used to pace bots. */
        public int sinceAction;
        /** True once the server has paid this round out; prevents double-credit. */
        public boolean settled;
        public int joinedAtTick;

        public Seat(int index) {
            this.index = index;
        }

        public boolean isOccupied() {
            return playerId != null;
        }

        public Hand activeHand() {
            if (activeHand < 0 || activeHand >= hands.size()) {
                return null;
            }
            return hands.get(activeHand);
        }

        public boolean hasBet() {
            return bet > 0;
        }

        /** True once every hand is resolved (bust / stood / blackjack / surrendered). */
        public boolean isFinished() {
            if (hands.isEmpty()) {
                return false;
            }
            for (Hand h : hands) {
                if (!h.isTerminal()) {
                    return false;
                }
            }
            return true;
        }

        public int totalValue() {
            int v = 0;
            for (Hand h : hands) {
                v = Math.max(v, h.calculateValue());
            }
            return v;
        }
    }

    /** Something that happened, for the client's ticker line. */
    public static class TableEvent {
        public final int tick;
        public final String kind;
        public final int seat;
        public final String text;

        public TableEvent(int tick, String kind, int seat, String text) {
            this.tick = tick;
            this.kind = kind;
            this.seat = seat;
            this.text = text;
        }
    }

    private final Seat[] seats = new Seat[MAX_SEATS];
    private final Deck deck = new Deck();
    private final Hand dealerHand = new Hand();
    private final Random botRandom = new Random();
    private final List<TableEvent> events = new ArrayList<>();
    /** Chips that the block entity must still credit, drained once by the server. */
    private final Map<UUID, Long> pendingPayouts = new LinkedHashMap<>();

    private Phase phase = Phase.WAITING_FOR_PLAYERS;
    private int animClock = 0;
    private int phaseTicks = 0;
    private int nextDealTick = 0;
    private int dealersDrawAtTick = 0;
    private int activeSeat = -1;
    private boolean dealerHoleRevealed = false;
    private boolean autoFillBots = true;
    private boolean dealRequested = false;
    private int roundNumber = 0;
    private UUID tableOwner;
    private String dealerSay = "";
    /** Set by {@link #forceShoe} so a pinned test shoe survives the round-start reshuffle. */
    private boolean shoePinned = false;

    public BlackjackTable() {
        for (int i = 0; i < MAX_SEATS; i++) {
            seats[i] = new Seat(i);
        }
    }

    // ---------------------------------------------------------------- seats

    public int seatOf(UUID playerId) {
        for (Seat s : seats) {
            if (s.playerId != null && s.playerId.equals(playerId)) {
                return s.index;
            }
        }
        return -1;
    }

    /**
     * Seats a player at the first free chair.
     *
     * @return the seat index, or -1 when the table is full or the player is already seated
     */
    public int join(UUID playerId, String name) {
        int existing = seatOf(playerId);
        if (existing >= 0) {
            return existing;
        }
        for (Seat s : seats) {
            if (!s.isOccupied()) {
                s.playerId = playerId;
                s.name = name == null ? "" : name;
                s.isBot = false;
                s.bet = 0;
                s.hands.clear();
                s.netChange = 0;
                s.settled = false;
                s.joinedAtTick = animClock;
                if (tableOwner == null) {
                    tableOwner = playerId;
                }
                pushEvent("JOIN", s.index, s.name + " sits down");
                if (phase == Phase.WAITING_FOR_PLAYERS) {
                    enterBetting();
                }
                return s.index;
            }
        }
        return -1;
    }

    public void leave(UUID playerId) {
        int i = seatOf(playerId);
        if (i < 0) {
            return;
        }
        Seat s = seats[i];
        String who = s.name;
        // A player walking away mid-hand forfeits: their bet is already gone and the
        // hand is marked so the dealer does not wait on them.
        for (Hand h : s.hands) {
            if (!h.isTerminal()) {
                h.state = HandState.SURRENDERED;
            }
        }
        s.playerId = null;
        s.name = "";
        s.isBot = false;
        s.bet = 0;
        s.hands.clear();
        s.netChange = 0;
        s.settled = false;
        pushEvent("LEAVE", i, who + " leaves");
        if (tableOwner != null && tableOwner.equals(playerId)) {
            tableOwner = null;
            for (Seat other : seats) {
                if (other.isOccupied() && !other.isBot) {
                    tableOwner = other.playerId;
                    break;
                }
            }
        }
        if (activeSeat == i && phase == Phase.PLAYER_TURNS) {
            advanceTurn();
        }
        if (countSeatedHumans() == 0 && countSeatedBots() == 0) {
            resetToWaiting();
        }
    }

    public void toggleAutoFillBots() {
        autoFillBots = !autoFillBots;
    }

    public boolean isAutoFillBots() {
        return autoFillBots;
    }

    public UUID getTableOwner() {
        return tableOwner;
    }

    private int countSeatedHumans() {
        int n = 0;
        for (Seat s : seats) {
            if (s.isOccupied() && !s.isBot) {
                n++;
            }
        }
        return n;
    }

    private int countSeatedBots() {
        int n = 0;
        for (Seat s : seats) {
            if (s.isOccupied() && s.isBot) {
                n++;
            }
        }
        return n;
    }

    /** Fills empty chairs with house players so a lone player still has a table. */
    private void fillBots() {
        if (!autoFillBots) {
            return;
        }
        String[] names = {"Ada", "Boris", "Cleo", "Dmitri", "Esme", "Frank"};
        for (Seat s : seats) {
            if (!s.isOccupied()) {
                s.playerId = botId(s.index);
                s.name = names[s.index % names.length];
                s.isBot = true;
                s.bet = 0;
                s.hands.clear();
                s.netChange = 0;
                s.settled = false;
                s.joinedAtTick = animClock;
                pushEvent("JOIN", s.index, s.name + " (house) sits down");
            }
        }
    }

    /** Stable synthetic id per seat, so bots survive restarts predictably. */
    private static UUID botId(int seat) {
        return new UUID(0xB07L, seat);
    }

    private void clearBots() {
        if (!autoFillBots) {
            return;
        }
        for (Seat s : seats) {
            if (s.isOccupied() && s.isBot) {
                s.playerId = null;
                s.name = "";
                s.isBot = false;
                s.hands.clear();
                s.bet = 0;
            }
        }
    }

    // ---------------------------------------------------------------- betting

    public boolean placeBet(UUID playerId, long amount) {
        int i = seatOf(playerId);
        if (i < 0) {
            return false;
        }
        if (phase != Phase.BETTING && phase != Phase.WAITING_FOR_PLAYERS) {
            return false;
        }
        if (amount < MIN_BET || amount > MAX_BET) {
            return false;
        }
        Seat s = seats[i];
        s.bet = amount;
        pushEvent("BET", i, s.name + " bets " + amount);
        maybeStartDeal();
        return true;
    }

    /** Any seated human may call the deal; the table also deals itself when everyone has bet. */
    public void requestDeal() {
        dealRequested = true;
        maybeStartDeal();
    }

    private void maybeStartDeal() {
        if (phase != Phase.BETTING) {
            return;
        }
        int withBets = 0;
        int occupied = 0;
        for (Seat s : seats) {
            if (s.isOccupied()) {
                occupied++;
                if (s.hasBet()) {
                    withBets++;
                }
            }
        }
        if (withBets > 0 && (withBets == occupied || dealRequested)) {
            startRound();
        }
    }

    // ---------------------------------------------------------------- round flow

    private void startRound() {
        fillBots();
        // Reshuffle between rounds, never mid-hand: a shoe with fewer than 52 cards left
        // would otherwise run dry during the dealer's draw and silently re-deal the table.
        if (!shoePinned && deck.remaining() < 52) {
            deck.reset();
        }
        dealerHand.cards.clear();
        dealerHand.state = HandState.ACTIVE;
        dealerHand.outcome = Outcome.NONE;
        dealerHand.payout = 0;
        dealerHand.bet = 0;
        dealerHand.doubled = false;
        dealerHand.fromSplit = false;

        int betting = 0;
        for (Seat s : seats) {
            if (!s.isOccupied()) {
                continue;
            }
            s.hands.clear();
            s.activeHand = 0;
            s.netChange = 0;
            s.settled = false;
            s.sinceAction = 0;
            if (s.hasBet()) {
                Hand h = new Hand();
                h.bet = s.bet;
                h.handIndex = 0;
                s.hands.add(h);
                betting++;
            }
        }
        if (betting == 0) {
            return;
        }

        roundNumber++;
        dealRequested = false;
        dealerHoleRevealed = false;
        animClock = 0;
        phaseTicks = 0;
        phase = Phase.DEALING;
        nextDealTick = DEAL_INTERVAL;
        dealerSay = "Cards are in the air";
        pushEvent("ROUND", -1, "Round " + roundNumber + " dealing");
    }

    /** Recipients of the opening deal: each seat twice, dealer twice, seat order first. */
    private void dealOneCard() {
        int dealt = countDealtCards();
        // 2 cards per betting seat, then dealer's up card and hole card, interleaved
        // round-robin the way a real shoe deals: everyone gets one, then everyone again.
        int seatedCount = 0;
        for (Seat s : seats) {
            if (s.isOccupied() && !s.hands.isEmpty()) {
                seatedCount++;
            }
        }
        int totalNeeded = seatedCount * 2 + 2;
        if (dealt >= totalNeeded) {
            beginPlayerTurns();
            return;
        }
        // Round 1: seat0..seatN, dealer up.  Round 2: seat0..seatN, dealer hole.
        int round = dealt / (seatedCount + 1);
        int slot = dealt % (seatedCount + 1);
        if (slot < seatedCount) {
            Seat target = nthPlayingSeat(slot);
            if (target != null) {
                Hand h = target.hands.get(0);
                h.add(deck.draw(), animClock, false);
                checkHandTerminal(target, h);
            }
        } else {
            boolean hole = round >= 1;
            dealerHand.add(deck.draw(), animClock, hole);
        }
    }

    private int countDealtCards() {
        int n = 0;
        for (Seat s : seats) {
            if (s.isOccupied() && !s.hands.isEmpty()) {
                n += s.hands.get(0).cards.size();
            }
        }
        n += dealerHand.cards.size();
        return n;
    }

    private Seat nthPlayingSeat(int n) {
        int seen = 0;
        for (Seat s : seats) {
            if (s.isOccupied() && !s.hands.isEmpty()) {
                if (seen == n) {
                    return s;
                }
                seen++;
            }
        }
        return null;
    }

    private void checkHandTerminal(Seat seat, Hand hand) {
        if (hand.isBust()) {
            hand.state = HandState.BUST;
            hand.outcome = Outcome.BUST;
            pushEvent("BUST", seat.index, seat.name + " busts on " + hand.calculateValue());
        } else if (hand.isBlackjack()) {
            hand.state = HandState.BLACKJACK;
            hand.outcome = Outcome.BLACKJACK_WIN;
            pushEvent("BLACKJACK", seat.index, seat.name + " has blackjack");
        } else if (hand.calculateValue() == 21) {
            hand.state = HandState.STOOD;
        }
    }

    private void beginPlayerTurns() {
        phase = Phase.PLAYER_TURNS;
        phaseTicks = 0;
        activeSeat = -1;
        // Dealer holding a natural ends the hand immediately — no reason to make
        // everyone hit into a 21.
        if (dealerHand.isBlackjack()) {
            dealerHoleRevealed = true;
            dealerSay = "Dealer has blackjack";
            pushEvent("DEALER_BJ", -1, "Dealer shows blackjack");
            settle();
            return;
        }
        advanceTurn();
    }

    private void advanceTurn() {
        // Every live (ACTIVE) hand still needs to act, and an ACTIVE hand by definition has
        // not acted yet. So the turn order is just "the next live hand after the cursor,
        // seat order then hand order" — no separate bookkeeping to get wrong.
        java.util.List<int[]> live = new java.util.ArrayList<>();
        for (Seat s : seats) {
            if (!s.isOccupied()) {
                continue;
            }
            for (int h = 0; h < s.hands.size(); h++) {
                if (s.hands.get(h).state == HandState.ACTIVE) {
                    live.add(new int[]{s.index, h});
                }
            }
        }
        if (live.isEmpty()) {
            activeSeat = -1;
            beginDealerTurn();
            return;
        }
        int curSeat = activeSeat;
        int curHand = activeSeat >= 0 ? seats[activeSeat].activeHand : -1;
        for (int[] pair : live) {
            if (pair[0] > curSeat || (pair[0] == curSeat && pair[1] > curHand)) {
                activeSeat = pair[0];
                seats[activeSeat].activeHand = pair[1];
                seats[activeSeat].sinceAction = 0;
                return;
            }
        }
        // Safety net (a split can insert a hand ahead of the cursor): start over from the
        // earliest live hand. Cannot loop forever, because acting always closes a hand.
        activeSeat = live.get(0)[0];
        seats[activeSeat].activeHand = live.get(0)[1];
        seats[activeSeat].sinceAction = 0;
    }

    /** A seat can act only if its active hand is live. */
    private boolean seatCanAct(Seat s) {
        Hand h = s.activeHand();
        return h != null && !h.isTerminal();
    }

    private void beginDealerTurn() {
        if (phase == Phase.DEALER_TURN || phase == Phase.SETTLE) {
            return;
        }
        phase = Phase.DEALER_TURN;
        phaseTicks = 0;
        dealerHoleRevealed = false;
        dealerSay = "Dealer reveals";
        // If every hand busted (or surrendered) the dealer does not need to draw.
        // The dealer only plays when a hand made it to a stand-off. If every hand busted,
        // or every hand is a natural, its hole card is revealed and the round is over.
        boolean anyStood = false;
        for (Seat s : seats) {
            for (Hand h : s.hands) {
                if (h.state == HandState.STOOD) {
                    anyStood = true;
                }
            }
        }
        if (!anyStood) {
            dealerSay = "Round over";
            settle();
            return;
        }
        dealersDrawAtTick = REVEAL_DELAY;
    }

    private void settle() {
        phase = Phase.SETTLE;
        phaseTicks = 0;
        dealerHoleRevealed = true;
        activeSeat = -1;
        int dealerValue = dealerHand.calculateValue();
        boolean dealerNatural = dealerHand.isBlackjack();
        boolean dealerBust = dealerValue > 21;

        for (Seat s : seats) {
            if (!s.isOccupied() || s.hands.isEmpty()) {
                continue;
            }
            long net = 0;
            for (Hand h : s.hands) {
                if (h.state == HandState.SURRENDERED) {
                    h.outcome = Outcome.LOSE;
                    h.payout = 0;
                    net -= h.bet;
                    continue;
                }
                if (h.state == HandState.BUST) {
                    h.outcome = Outcome.BUST;
                    h.payout = 0;
                    net -= h.bet;
                    continue;
                }
                int v = h.calculateValue();
                if (h.state == HandState.BLACKJACK && !dealerNatural) {
                    // 3:2 — profit is one and a half times the bet, plus the stake back.
                    h.payout = h.bet + (h.bet * 3 / 2);
                    h.outcome = Outcome.BLACKJACK_WIN;
                    net += h.bet * 3 / 2;
                } else if (dealerNatural) {
                    h.payout = h.bet;
                    h.outcome = Outcome.PUSH;
                } else if (dealerBust) {
                    h.payout = h.bet * 2;
                    h.outcome = Outcome.DEALER_BUST;
                    net += h.bet;
                } else if (v > dealerValue) {
                    h.payout = h.bet * 2;
                    h.outcome = Outcome.WIN;
                    net += h.bet;
                } else if (v == dealerValue) {
                    h.payout = h.bet;
                    h.outcome = Outcome.PUSH;
                } else {
                    h.payout = 0;
                    h.outcome = Outcome.LOSE;
                    net -= h.bet;
                }
            }
            s.netChange = net;
            // House players have no account: crediting their synthetic UUID would pollute
            // the saved economy file with phantom balances that grow every round.
            if (s.playerId != null && !s.isBot) {
                long total = 0;
                for (Hand h : s.hands) {
                    total += h.payout;
                }
                if (total > 0) {
                    pendingPayouts.merge(s.playerId, total, Long::sum);
                }
                s.settled = true;
            }
        }
        String d = dealerBust ? "Dealer busts with " + dealerValue
                : dealerNatural ? "Dealer has blackjack"
                : "Dealer stands on " + dealerValue;
        dealerSay = d;
        pushEvent("SETTLE", -1, d);
    }

    // ---------------------------------------------------------------- player verbs

    private Seat actingSeat(UUID playerId) {
        int i = seatOf(playerId);
        if (i < 0 || phase != Phase.PLAYER_TURNS) {
            return null;
        }
        if (i != activeSeat) {
            return null;
        }
        Seat s = seats[i];
        if (!seatCanAct(s)) {
            return null;
        }
        return s;
    }

    public boolean hit(UUID playerId) {
        Seat s = actingSeat(playerId);
        if (s == null) {
            return false;
        }
        Hand h = s.activeHand();
        h.add(deck.draw(), animClock, false);
        pushEvent("HIT", s.index, s.name + " hits " + h.calculateValue());
        if (h.isBust()) {
            h.state = HandState.BUST;
            h.outcome = Outcome.BUST;
            pushEvent("BUST", s.index, s.name + " busts");
            advanceTurn();
        } else if (h.calculateValue() == 21) {
            h.state = HandState.STOOD;
            advanceTurn();
        }
        return true;
    }

    public boolean stand(UUID playerId) {
        Seat s = actingSeat(playerId);
        if (s == null) {
            return false;
        }
        Hand h = s.activeHand();
        h.state = HandState.STOOD;
        pushEvent("STAND", s.index, s.name + " stands on " + h.calculateValue());
        advanceTurn();
        return true;
    }

    public boolean doubleDown(UUID playerId) {
        Seat s = actingSeat(playerId);
        if (s == null) {
            return false;
        }
        Hand h = s.activeHand();
        if (!h.canDouble()) {
            return false;
        }
        h.bet *= 2;
        h.doubled = true;
        h.add(deck.draw(), animClock, false);
        pushEvent("DOUBLE", s.index, s.name + " doubles to " + h.bet);
        if (h.isBust()) {
            h.state = HandState.BUST;
            h.outcome = Outcome.BUST;
        } else {
            h.state = HandState.STOOD;
        }
        advanceTurn();
        return true;
    }

    public boolean split(UUID playerId) {
        Seat s = actingSeat(playerId);
        if (s == null) {
            return false;
        }
        Hand h = s.activeHand();
        if (!h.canSplit() || s.hands.size() >= 4) {
            return false;
        }
        Dealt a = h.cards.get(0);
        Dealt b = h.cards.get(1);
        h.cards.clear();
        // Both halves are now post-split hands: a 21 on two cards is *not* a natural, so
        // it must not pay 3:2 and must not be splittable again. Marking only the second
        // half left the first one masquerading as a natural.
        h.fromSplit = true;
        h.add(a.card, animClock, false);
        h.add(deck.draw(), animClock, false);

        Hand second = new Hand();
        second.bet = h.bet;
        second.fromSplit = true;
        second.add(b.card, animClock, false);
        second.add(deck.draw(), animClock, false);

        int insertAt = s.activeHand + 1;
        s.hands.add(insertAt, second);
        for (int i = 0; i < s.hands.size(); i++) {
            s.hands.get(i).handIndex = i;
        }
        // A split ace receives exactly one card each and is done.
        if (a.card.rank == Card.Rank.ACE) {
            h.state = HandState.STOOD;
            second.state = HandState.STOOD;
            pushEvent("SPLIT", s.index, s.name + " splits aces");
            advanceTurn();
            return true;
        }
        pushEvent("SPLIT", s.index, s.name + " splits");
        // Either half can be dealt to 21 straight away; a hand sitting on 21 must be
        // closed, or it stays ACTIVE and the player can hit it into a bust.
        boolean firstDone = false;
        if (h.calculateValue() == 21) {
            h.state = HandState.STOOD;
            firstDone = true;
        }
        if (second.calculateValue() == 21) {
            second.state = HandState.STOOD;
        }
        if (firstDone) {
            advanceTurn();
        }
        return true;
    }

    // ---------------------------------------------------------------- ticking

    public void tickServer() {
        animClock++;
        phaseTicks++;
        for (Seat s : seats) {
            if (s.isOccupied()) {
                s.sinceAction++;
            }
        }

        switch (phase) {
            case WAITING_FOR_PLAYERS -> {
                if (countSeatedHumans() > 0) {
                    enterBetting();
                }
            }
            case BETTING -> {
                botBetting();
                if (countSeatedHumans() == 0 && countSeatedBots() == 0) {
                    resetToWaiting();
                    return;
                }
                if (phaseTicks >= BETTING_TICKS) {
                    // Everyone who bet plays; the window simply closes.
                    if (anyBetPlaced()) {
                        startRound();
                    } else {
                        phaseTicks = 0;
                    }
                }
            }
            case DEALING -> {
                if (animClock >= nextDealTick) {
                    nextDealTick = animClock + DEAL_INTERVAL;
                    dealOneCard();
                }
            }
            case PLAYER_TURNS -> {
                botActing();
                if (activeSeat >= 0) {
                    Seat s = seats[activeSeat];
                    if (s.isBot && s.sinceAction >= BOT_THINK) {
                        botPlaySeat(s);
                    }
                }
            }
            case DEALER_TURN -> {
                dealerPlay();
            }
            case SETTLE -> {
                if (phaseTicks >= SETTLE_HOLD) {
                    finishRound();
                }
            }
        }
        trimEvents();
    }

    private boolean anyBetPlaced() {
        for (Seat s : seats) {
            if (s.isOccupied() && s.hasBet()) {
                return true;
            }
        }
        return false;
    }

    private void enterBetting() {
        phase = Phase.BETTING;
        phaseTicks = 0;
        dealRequested = false;
        dealerSay = "Place your bets";
        for (Seat s : seats) {
            s.hands.clear();
            s.netChange = 0;
            s.settled = false;
            s.activeHand = 0;
            if (!s.isOccupied()) {
                s.bet = 0;
            }
        }
        dealerHand.cards.clear();
        dealerHoleRevealed = false;
    }

    private void resetToWaiting() {
        clearBots();
        phase = Phase.WAITING_FOR_PLAYERS;
        phaseTicks = 0;
        activeSeat = -1;
        dealerSay = "";
        dealerHand.cards.clear();
        for (Seat s : seats) {
            s.hands.clear();
            s.bet = 0;
            s.netChange = 0;
        }
    }

    private void finishRound() {
        // Keep seats, wipe hands, return to betting.
        for (Seat s : seats) {
            s.hands.clear();
            s.bet = 0;
            s.activeHand = 0;
        }
        dealerHand.cards.clear();
        dealerHand.outcome = Outcome.NONE;
        dealerHoleRevealed = false;
        enterBetting();
    }

    private void dealerPlay() {
        int v = dealerHand.calculateValue();
        if (!dealerHoleRevealed && phaseTicks >= REVEAL_DELAY) {
            dealerHoleRevealed = true;
            dealerSay = "Dealer shows " + v;
            dealersDrawAtTick = animClock + DEALER_DRAW_INTERVAL;
            pushEvent("REVEAL", -1, "Dealer reveals " + v);
            return;
        }
        if (!dealerHoleRevealed) {
            return;
        }
        if (v >= DEALER_STANDS_ON) {
            settle();
            return;
        }
        if (animClock >= dealersDrawAtTick) {
            dealerHand.add(deck.draw(), animClock, false);
            dealersDrawAtTick = animClock + DEALER_DRAW_INTERVAL;
            int nv = dealerHand.calculateValue();
            dealerSay = "Dealer draws " + nv;
            pushEvent("DEALER_DRAW", -1, "Dealer draws to " + nv);
        }
    }

    // ---------------------------------------------------------------- bots

    private void botBetting() {
        if (!autoFillBots) {
            return;
        }
        if (countSeatedHumans() > 0 && countSeatedBots() == 0) {
            fillBots();
        }
        long[] stakes = {25, 50, 100, 200};
        for (Seat s : seats) {
            if (s.isOccupied() && s.isBot && !s.hasBet() && phaseTicks > 20) {
                s.bet = stakes[botRandom.nextInt(stakes.length)];
                pushEvent("BET", s.index, s.name + " bets " + s.bet);
            }
        }
    }

    private void botActing() {
        if (activeSeat < 0) {
            return;
        }
        Seat s = seats[activeSeat];
        if (!s.isBot || !seatCanAct(s)) {
            return;
        }
        if (s.sinceAction < BOT_THINK) {
            return;
        }
        botPlaySeat(s);
    }

    /** Basic-strategy-shaped house play: hit below 17, stand on 17+, double 10/11, split pairs. */
    private void botPlaySeat(Seat s) {
        Hand h = s.activeHand();
        if (h == null || h.isTerminal()) {
            advanceTurn();
            return;
        }
        s.sinceAction = 0;
        int v = h.calculateValue();
        if (h.canSplit() && botWantsSplit(h)) {
            if (split(s.playerId)) {
                return;
            }
        }
        if (h.canDouble() && (v == 10 || v == 11) && botRandom.nextDouble() < 0.55) {
            if (doubleDown(s.playerId)) {
                return;
            }
        }
        if (v < 17) {
            if (hit(s.playerId)) {
                return;
            }
        }
        stand(s.playerId);
    }

    private boolean botWantsSplit(Hand h) {
        Card.Rank r = h.cards.get(0).card.rank;
        if (r == Card.Rank.ACE || r == Card.Rank.EIGHT) {
            return true;
        }
        return (r == Card.Rank.NINE || r == Card.Rank.SEVEN || r == Card.Rank.SIX)
                && botRandom.nextDouble() < 0.4;
    }

    // ---------------------------------------------------------------- events

    private void pushEvent(String kind, int seat, String text) {
        events.add(new TableEvent(animClock, kind, seat, text));
        if (events.size() > 8) {
            events.remove(0);
        }
    }

    private void trimEvents() {
        while (events.size() > 8) {
            events.remove(0);
        }
    }

    public List<TableEvent> getRecentEvents() {
        return events;
    }

    /** Pulls every payout the server still owes and clears the queue. */
    public Map<UUID, Long> drainPayouts() {
        if (pendingPayouts.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> copy = new LinkedHashMap<>(pendingPayouts);
        pendingPayouts.clear();
        return copy;
    }

    // ---------------------------------------------------------------- getters

    public Phase getPhase() {
        return phase;
    }

    public int getAnimClock() {
        return animClock;
    }

    public int getPhaseTicks() {
        return phaseTicks;
    }

    public Hand getDealerHand() {
        return dealerHand;
    }

    public Seat[] getSeats() {
        return seats;
    }

    public int getActiveSeat() {
        return activeSeat;
    }

    public boolean getDealerHoleRevealed() {
        return dealerHoleRevealed;
    }

    public String getDealerSay() {
        return dealerSay;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public long getMinBet() {
        return MIN_BET;
    }

    public long getMaxBet() {
        return MAX_BET;
    }

    /** Shoe depth, so the client can draw a shrinking shoe. */
    public int getShoeRemaining() {
        return deck.remaining();
    }

    // ---------------------------------------------------------------- serialisation

    /**
     * State for one viewer.
     *
     * <p>The hole card is stripped for everyone until the dealer actually turns it over,
     * so the secret cannot leak through the sync packet.
     */
    public JsonObject toJson(UUID viewer, boolean isSeated) {
        JsonObject obj = new JsonObject();
        obj.addProperty("phase", phase.name());
        obj.addProperty("animClock", animClock);
        obj.addProperty("phaseTicks", phaseTicks);
        obj.addProperty("activeSeat", activeSeat);
        obj.addProperty("dealerRevealed", dealerHoleRevealed);
        obj.addProperty("dealerSay", dealerSay);
        obj.addProperty("round", roundNumber);
        obj.addProperty("seated", isSeated);
        obj.addProperty("mySeat", viewer == null ? -1 : seatOf(viewer));
        obj.addProperty("autoFillBots", autoFillBots);
        obj.addProperty("isOwner", viewer != null && viewer.equals(tableOwner));
        obj.addProperty("minBet", MIN_BET);
        obj.addProperty("maxBet", MAX_BET);
        obj.addProperty("shoe", deck.remaining());
        obj.addProperty("maxSeats", MAX_SEATS);

        // Dealer hand: hide the hole card until reveal.
        JsonObject dealer = new JsonObject();
        JsonArray dCards = new JsonArray();
        int shown = 0;
        for (int i = 0; i < dealerHand.cards.size(); i++) {
            Dealt d = dealerHand.cards.get(i);
            boolean hidden = d.faceDown && !dealerHoleRevealed;
            JsonObject c = new JsonObject();
            c.addProperty("c", hidden ? "??" : d.card.toShortString());
            c.addProperty("t", d.tick);
            c.addProperty("hidden", hidden);
            dCards.add(c);
            if (!hidden) {
                shown += cardScore(d.card);
            }
        }
        dealer.add("cards", dCards);
        dealer.addProperty("shown", shown);
        dealer.addProperty("value", dealerHoleRevealed ? dealerHand.calculateValue() : shown);
        obj.add("dealer", dealer);

        JsonArray seatArr = new JsonArray();
        for (Seat s : seats) {
            JsonObject o = new JsonObject();
            o.addProperty("index", s.index);
            o.addProperty("occupied", s.isOccupied());
            o.addProperty("name", s.name);
            o.addProperty("bot", s.isBot);
            o.addProperty("bet", s.bet);
            o.addProperty("net", s.netChange);
            o.addProperty("active", s.index == activeSeat);
            o.addProperty("finished", s.isFinished());
            JsonArray hands = new JsonArray();
            for (Hand h : s.hands) {
                JsonObject ho = new JsonObject();
                ho.addProperty("bet", h.bet);
                ho.addProperty("doubled", h.doubled);
                ho.addProperty("state", h.state.name());
                ho.addProperty("outcome", h.outcome.name());
                ho.addProperty("value", h.calculateValue());
                ho.addProperty("soft", h.isSoft());
                ho.addProperty("index", h.handIndex);
                JsonArray hc = new JsonArray();
                for (Dealt d : h.cards) {
                    JsonObject c = new JsonObject();
                    c.addProperty("c", d.card.toShortString());
                    c.addProperty("t", d.tick);
                    hc.add(c);
                }
                ho.add("cards", hc);
                hands.add(ho);
            }
            o.add("hands", hands);
            // The seat that belongs to the viewer is flagged so the client can highlight it.
            o.addProperty("mine", s.playerId != null && s.playerId.equals(viewer));
            seatArr.add(o);
        }
        obj.add("seats", seatArr);

        JsonArray ev = new JsonArray();
        for (TableEvent e : events) {
            JsonObject o = new JsonObject();
            o.addProperty("t", e.tick);
            o.addProperty("k", e.kind);
            o.addProperty("s", e.seat);
            o.addProperty("x", e.text);
            ev.add(o);
        }
        obj.add("events", ev);
        return obj;
    }

    private static int cardScore(Card card) {
        if (card.rank == Card.Rank.ACE) {
            return 11;
        }
        return Math.min(10, card.rank.value);
    }

    /**
     * Test hook: fixes the shoe so the next draws come out in exactly this order
     * (first element first). Production code never calls it.
     */
    public void forceShoe(Card... cards) {
        shoePinned = true;
        deck.setDrawOrder(java.util.Arrays.asList(cards));
    }

    public String toJsonString(UUID viewer, boolean isSeated) {
        return GSON.toJson(toJson(viewer, isSeated));
    }
}
