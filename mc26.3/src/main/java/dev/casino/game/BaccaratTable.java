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
 * Multiplayer baccarat table: six bettors on a semicircle around one dealer shoe.
 *
 * <p>Pure Java, no Minecraft types, so the whole tableau — the naturals, the player's
 * draw, the banker's third-card table, the commission and the animation clock — is
 * unit-testable on a bare JVM.
 *
 * <p>Unlike blackjack, the bettors here have no decisions to make: they pick PLAYER,
 * BANKER or TIE before the deal and then watch. That makes this table entirely
 * dealer-paced, which is what the {@link #tickServer()} beats exist for — one card
 * every {@value #DEAL_INTERVAL} ticks, so the third card that decides the hand lands
 * visibly rather than appearing in the same frame as the first two.
 */
public class BaccaratTable {
    private static final Gson GSON = new GsonBuilder().create();

    public static final int MAX_SEATS = 6;

    /** One card leaves the shoe every this many ticks (0.25 s). */
    public static final int DEAL_INTERVAL = 5;
    /** How long the settled hands stay up before the next betting window. */
    public static final int SETTLE_HOLD = 70;
    /** The betting window before the shoe deals itself. */
    public static final int BETTING_TICKS = 400;
    /** A house player's thinking time. */
    public static final int BOT_THINK = 18;

    public static final long MIN_BET = 10;
    public static final long MAX_BET = 10_000;

    /** Baccarat point value: aces are 1, tens and pictures are 0, the rest face value. */
    public static int cardValue(Card card) {
        if (card.rank == Card.Rank.ACE) {
            return 1;
        }
        if (card.rank.value >= 10) {
            return 0;
        }
        return card.rank.value;
    }

    /** The hand total, modulo ten. Two cards of 7 and 8 total 5, not 15. */
    public static int handTotal(List<Dealt> hand) {
        int sum = 0;
        for (Dealt d : hand) {
            sum += cardValue(d.card);
        }
        return sum % 10;
    }

    public enum Phase {
        WAITING_FOR_PLAYERS,
        BETTING,
        DEALING,
        THIRD_CARD,
        SETTLE
    }

    /** A card plus the tick it landed on, so every client animates the same arrival. */
    public static class Dealt {
        public final Card card;
        public final int tick;

        public Dealt(Card card, int tick) {
            this.card = card;
            this.tick = tick;
        }
    }

    public static class Seat {
        public final int index;
        public UUID playerId;
        public String name = "";
        public boolean isBot;
        /** Which of the three lines this seat backed. */
        public BaccaratEngine.BetTarget target = BaccaratEngine.BetTarget.PLAYER;
        public long bet;
        public long payout;
        public long netChange;
        public BaccaratEngine.Outcome outcome;
        public boolean settled;
        public int sinceAction;

        public Seat(int index) {
            this.index = index;
        }

        public boolean isOccupied() {
            return playerId != null;
        }

        public boolean hasBet() {
            return bet > 0;
        }
    }

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
    private final List<Dealt> playerHand = new ArrayList<>();
    private final List<Dealt> bankerHand = new ArrayList<>();
    private final Random botRandom = new Random();
    private final List<TableEvent> events = new ArrayList<>();
    private final Map<UUID, Long> pendingPayouts = new LinkedHashMap<>();

    private Phase phase = Phase.WAITING_FOR_PLAYERS;
    private int animClock = 0;
    private int phaseTicks = 0;
    private int nextDealTick = 0;
    private boolean autoFillBots = true;
    private boolean dealRequested = false;
    private int roundNumber = 0;
    private UUID tableOwner;
    private String dealerSay = "";
    private int playerTotal = 0;
    private int bankerTotal = 0;
    private BaccaratEngine.Outcome outcome = null;
    private boolean shoePinned = false;
    /** Set once the dealer has decided whether a third card is coming, per side. */
    private boolean playerDrewThird;
    private boolean bankerDrewThird;
    private int dealerCardsDealt;

    public BaccaratTable() {
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
                s.target = BaccaratEngine.BetTarget.PLAYER;
                s.netChange = 0;
                s.payout = 0;
                s.outcome = null;
                s.settled = false;
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
        // A bettor who walks away mid-hand forfeits the stake already on the felt.
        s.playerId = null;
        s.name = "";
        s.isBot = false;
        s.bet = 0;
        s.payout = 0;
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
                s.target = BaccaratEngine.BetTarget.PLAYER;
                s.netChange = 0;
                s.payout = 0;
                s.outcome = null;
                s.settled = false;
                pushEvent("JOIN", s.index, s.name + " (house) sits down");
            }
        }
    }

    private static UUID botId(int seat) {
        return new UUID(0x8ACL, seat);
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
                s.bet = 0;
            }
        }
    }

    // ---------------------------------------------------------------- betting

    /**
     * Places or replaces a seat's bet.
     *
     * @return false when the seat is unknown, the window is closed, or the stake is
     *         outside {@link #MIN_BET}..{@link #MAX_BET}
     */
    public boolean placeBet(UUID playerId, BaccaratEngine.BetTarget target, long amount) {
        int i = seatOf(playerId);
        if (i < 0 || target == null) {
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
        s.target = target;
        pushEvent("BET", i, s.name + " backs " + target.name() + " for " + amount);
        maybeStartDeal();
        return true;
    }

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
        // Between rounds only, and never below 52 cards: a shoe that runs dry mid-tableau
        // would silently re-deal the hands.
        if (!shoePinned && deck.remaining() < 52) {
            deck.reset();
        }
        playerHand.clear();
        bankerHand.clear();
        playerTotal = 0;
        bankerTotal = 0;
        outcome = null;
        playerDrewThird = false;
        bankerDrewThird = false;
        dealerCardsDealt = 0;

        int betting = 0;
        for (Seat s : seats) {
            if (!s.isOccupied()) {
                continue;
            }
            s.settled = false;
            s.netChange = 0;
            s.payout = 0;
            s.outcome = null;
            s.sinceAction = 0;
            if (s.hasBet()) {
                betting++;
            }
        }
        if (betting == 0) {
            return;
        }

        roundNumber++;
        dealRequested = false;
        animClock = 0;
        phaseTicks = 0;
        phase = Phase.DEALING;
        nextDealTick = DEAL_INTERVAL;
        dealerSay = "No more bets";
        pushEvent("ROUND", -1, "Round " + roundNumber + " dealing");
    }

    /** Deals the opening four cards, one per beat, then hands over to the tableau. */
    private void dealOpeningCard() {
        int tick = animClock;
        switch (dealerCardsDealt) {
            case 0 -> playerHand.add(new Dealt(deck.draw(), tick));
            case 1 -> bankerHand.add(new Dealt(deck.draw(), tick));
            case 2 -> playerHand.add(new Dealt(deck.draw(), tick));
            default -> bankerHand.add(new Dealt(deck.draw(), tick));
        }
        dealerCardsDealt++;
        if (dealerCardsDealt >= 4) {
            playerTotal = handTotal(playerHand);
            bankerTotal = handTotal(bankerHand);
            if (playerTotal >= 8 || bankerTotal >= 8) {
                dealerSay = "Natural " + playerTotal + " to " + bankerTotal;
                pushEvent("NATURAL", -1, "Natural: P" + playerTotal + " / B" + bankerTotal);
                settle();
                return;
            }
            phase = Phase.THIRD_CARD;
            phaseTicks = 0;
            dealerSay = "P" + playerTotal + " / B" + bankerTotal;
            pushEvent("SHOW", -1, "Player " + playerTotal + ", banker " + bankerTotal);
        }
    }

    /**
     * The Punto Banco tableau.
     *
     * <p>Player draws on 0-5 and stands on 6-7. If the player stood, the banker draws on
     * 0-5 too. If the player drew, the banker's action depends on its own total and on
     * the value of the player's third card — the table below is the standard one.
     */
    private void runTableau() {
        // Structure matters here: each pass draws at most one card and returns, and the
        // branches are keyed on how many cards each side already holds. An earlier version
        // keyed only on the player's action, so a banker that drew yet was still showing
        // five or less would draw a fourth card on the next beat.
        if (playerHand.size() == 2) {
            if (playerTotal <= 5) {
                int before = playerTotal;
                playerHand.add(new Dealt(deck.draw(), animClock));
                playerDrewThird = true;
                playerTotal = handTotal(playerHand);
                dealerSay = "Player draws " + before + " to " + playerTotal;
                pushEvent("DRAW", -1, "Player draws to " + playerTotal);
                return;
            }
            // The player stood on 6 or 7, so the banker simply plays its own total.
            if (bankerHand.size() == 2 && bankerTotal <= 5) {
                int before = bankerTotal;
                bankerHand.add(new Dealt(deck.draw(), animClock));
                bankerDrewThird = true;
                bankerTotal = handTotal(bankerHand);
                dealerSay = "Banker draws " + before + " to " + bankerTotal;
                pushEvent("DRAW", -1, "Banker draws to " + bankerTotal);
                return;
            }
            settle();
            return;
        }

        if (playerHand.size() == 3 && bankerHand.size() == 2) {
            int playerThird = cardValue(playerHand.get(2).card);
            if (bankerDraws(bankerTotal, playerThird)) {
                int before = bankerTotal;
                bankerHand.add(new Dealt(deck.draw(), animClock));
                bankerDrewThird = true;
                bankerTotal = handTotal(bankerHand);
                dealerSay = "Banker draws " + before + " to " + bankerTotal;
                pushEvent("DRAW", -1, "Banker draws to " + bankerTotal);
                return;
            }
            dealerSay = "Banker stands on " + bankerTotal;
        }
        settle();
    }

    /** The banker's third-card table, given the banker total and the player's third card. */
    public static boolean bankerDraws(int bankerTotal, int playerThirdValue) {
        if (bankerTotal <= 2) {
            return true;
        }
        if (bankerTotal == 3) {
            return playerThirdValue != 8;
        }
        if (bankerTotal == 4) {
            return playerThirdValue >= 2 && playerThirdValue <= 7;
        }
        if (bankerTotal == 5) {
            return playerThirdValue >= 4 && playerThirdValue <= 7;
        }
        if (bankerTotal == 6) {
            return playerThirdValue == 6 || playerThirdValue == 7;
        }
        // 7 stands, 8/9 never reach here (natural), and a total of 0-2 was handled above.
        return false;
    }

    private void settle() {
        phase = Phase.SETTLE;
        phaseTicks = 0;
        if (playerTotal > bankerTotal) {
            outcome = BaccaratEngine.Outcome.PLAYER_WIN;
        } else if (bankerTotal > playerTotal) {
            outcome = BaccaratEngine.Outcome.BANKER_WIN;
        } else {
            outcome = BaccaratEngine.Outcome.TIE;
        }

        for (Seat s : seats) {
            if (!s.isOccupied() || !s.hasBet()) {
                continue;
            }
            long payout = payoutFor(s.target, s.bet, outcome);
            s.payout = payout;
            s.outcome = outcome;
            s.settled = true;
            long net;
            if (outcome == BaccaratEngine.Outcome.TIE && s.target != BaccaratEngine.BetTarget.TIE) {
                net = 0; // main lines push
            } else if (payout > 0) {
                net = payout - s.bet;
            } else {
                net = -s.bet;
            }
            s.netChange = net;
            // House players have no account; crediting their synthetic id would grow a
            // phantom balance every round.
            if (s.playerId != null && !s.isBot && payout > 0) {
                pendingPayouts.merge(s.playerId, payout, Long::sum);
            }
        }

        String say = switch (outcome) {
            case PLAYER_WIN -> "Player wins " + playerTotal + " to " + bankerTotal;
            case BANKER_WIN -> "Banker wins " + bankerTotal + " to " + playerTotal;
            case TIE -> "Tie on " + playerTotal;
        };
        dealerSay = say;
        pushEvent("SETTLE", -1, say);
    }

    /**
     * Payout for one line, mirroring {@link BaccaratEngine} exactly so the multiplayer
     * table and the single-player engine cannot quote different odds.
     */
    public static long payoutFor(BaccaratEngine.BetTarget target, long bet, BaccaratEngine.Outcome outcome) {
        if (outcome == BaccaratEngine.Outcome.TIE) {
            if (target == BaccaratEngine.BetTarget.TIE) {
                return bet + (long) (bet * BaccaratEngine.BetTarget.TIE.payoutMultiplier);
            }
            return bet; // main bets push
        }
        if (outcome == BaccaratEngine.Outcome.PLAYER_WIN && target == BaccaratEngine.BetTarget.PLAYER) {
            return bet + (long) (bet * BaccaratEngine.BetTarget.PLAYER.payoutMultiplier);
        }
        if (outcome == BaccaratEngine.Outcome.BANKER_WIN && target == BaccaratEngine.BetTarget.BANKER) {
            return bet + (long) (bet * BaccaratEngine.BetTarget.BANKER.payoutMultiplier);
        }
        return 0;
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
                if (phaseTicks >= BETTING_TICKS) {
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
                    dealOpeningCard();
                }
            }
            case THIRD_CARD -> {
                if (animClock >= nextDealTick) {
                    nextDealTick = animClock + DEAL_INTERVAL;
                    runTableau();
                }
            }
            case SETTLE -> {
                if (phaseTicks >= SETTLE_HOLD) {
                    finishRound();
                }
            }
        }
        while (events.size() > 8) {
            events.remove(0);
        }
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
        playerHand.clear();
        bankerHand.clear();
        playerTotal = 0;
        bankerTotal = 0;
        outcome = null;
        for (Seat s : seats) {
            s.payout = 0;
            s.netChange = 0;
            s.settled = false;
            s.outcome = null;
            if (!s.isOccupied()) {
                s.bet = 0;
            }
        }
    }

    private void resetToWaiting() {
        clearBots();
        phase = Phase.WAITING_FOR_PLAYERS;
        phaseTicks = 0;
        dealerSay = "";
        playerHand.clear();
        bankerHand.clear();
        for (Seat s : seats) {
            s.bet = 0;
            s.netChange = 0;
            s.payout = 0;
        }
    }

    private void finishRound() {
        for (Seat s : seats) {
            s.bet = 0;
        }
        enterBetting();
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
        BaccaratEngine.BetTarget[] lines = {
                BaccaratEngine.BetTarget.PLAYER,
                BaccaratEngine.BetTarget.BANKER,
                BaccaratEngine.BetTarget.BANKER,
                BaccaratEngine.BetTarget.TIE
        };
        for (Seat s : seats) {
            if (s.isOccupied() && s.isBot && !s.hasBet() && phaseTicks > 20) {
                s.bet = stakes[botRandom.nextInt(stakes.length)];
                s.target = lines[botRandom.nextInt(lines.length)];
                pushEvent("BET", s.index, s.name + " backs " + s.target.name() + " for " + s.bet);
            }
        }
    }

    // ---------------------------------------------------------------- events

    private void pushEvent(String kind, int seat, String text) {
        events.add(new TableEvent(animClock, kind, seat, text));
        if (events.size() > 8) {
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

    public Seat[] getSeats() {
        return seats;
    }

    public List<Dealt> getPlayerHand() {
        return playerHand;
    }

    public List<Dealt> getBankerHand() {
        return bankerHand;
    }

    public int getPlayerTotal() {
        return playerTotal;
    }

    public int getBankerTotal() {
        return bankerTotal;
    }

    public BaccaratEngine.Outcome getOutcome() {
        return outcome;
    }

    public String getDealerSay() {
        return dealerSay;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public int getShoeRemaining() {
        return deck.remaining();
    }

    public long getMinBet() {
        return MIN_BET;
    }

    public long getMaxBet() {
        return MAX_BET;
    }

    // ---------------------------------------------------------------- serialisation

    public JsonObject toJson(UUID viewer) {
        JsonObject obj = new JsonObject();
        obj.addProperty("phase", phase.name());
        obj.addProperty("animClock", animClock);
        obj.addProperty("phaseTicks", phaseTicks);
        obj.addProperty("dealerSay", dealerSay);
        obj.addProperty("round", roundNumber);
        obj.addProperty("seated", viewer != null && seatOf(viewer) >= 0);
        obj.addProperty("mySeat", viewer == null ? -1 : seatOf(viewer));
        obj.addProperty("autoFillBots", autoFillBots);
        obj.addProperty("isOwner", viewer != null && viewer.equals(tableOwner));
        obj.addProperty("minBet", MIN_BET);
        obj.addProperty("maxBet", MAX_BET);
        obj.addProperty("shoe", deck.remaining());
        obj.addProperty("maxSeats", MAX_SEATS);
        obj.addProperty("playerTotal", playerTotal);
        obj.addProperty("bankerTotal", bankerTotal);
        obj.addProperty("outcome", outcome == null ? "NONE" : outcome.name());

        obj.add("playerHand", handToJson(playerHand));
        obj.add("bankerHand", handToJson(bankerHand));

        JsonArray seatArr = new JsonArray();
        for (Seat s : seats) {
            JsonObject o = new JsonObject();
            o.addProperty("index", s.index);
            o.addProperty("occupied", s.isOccupied());
            o.addProperty("name", s.name);
            o.addProperty("bot", s.isBot);
            o.addProperty("bet", s.bet);
            o.addProperty("target", s.target == null ? "PLAYER" : s.target.name());
            o.addProperty("payout", s.payout);
            o.addProperty("net", s.netChange);
            o.addProperty("outcome", s.outcome == null ? "NONE" : s.outcome.name());
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

    private static JsonArray handToJson(List<Dealt> hand) {
        JsonArray arr = new JsonArray();
        for (Dealt d : hand) {
            JsonObject c = new JsonObject();
            c.addProperty("c", d.card.toShortString());
            c.addProperty("t", d.tick);
            arr.add(c);
        }
        return arr;
    }

    public String toJsonString(UUID viewer) {
        return GSON.toJson(toJson(viewer));
    }

    /**
     * Test hook: fixes the shoe so the next draws come out in exactly this order
     * (first element first). Production code never calls it.
     */
    public void forceShoe(Card... cards) {
        shoePinned = true;
        deck.setDrawOrder(java.util.Arrays.asList(cards));
    }
}
