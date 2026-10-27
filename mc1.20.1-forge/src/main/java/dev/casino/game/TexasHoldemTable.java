package dev.casino.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.*;

public class TexasHoldemTable {
    private static final Gson GSON = new GsonBuilder().create();

    public enum TablePhase {
        WAITING_FOR_PLAYERS,
        DEALING,
        PRE_FLOP,
        FLOP,
        TURN,
        RIVER,
        SHOWDOWN
    }

    /** A card plus the tick it landed on, so the client can animate its arrival. */
    public static class Dealt {
        public final Card card;
        public final int tick;

        public Dealt(Card card, int tick) {
            this.card = card;
            this.tick = tick;
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

    public static class Seat {
        public int seatIndex;
        public UUID playerId;
        public String playerName;
        public long stack;
        public long currentBet;
        public boolean isBot;
        public String botType;
        public boolean folded;
        public boolean allIn;
        public Card[] holeCards = new Card[2];
        /** Tick each hole card landed on, -1 until dealt. */
        public final int[] holeTicks = new int[]{-1, -1};
        public String lastAction = "";

        public Seat(int index) {
            this.seatIndex = index;
        }

        public boolean isOccupied() {
            return playerId != null;
        }

        public void resetForNewHand() {
            currentBet = 0;
            folded = false;
            allIn = false;
            holeCards[0] = null;
            holeCards[1] = null;
            holeTicks[0] = -1;
            holeTicks[1] = -1;
            lastAction = "";
        }
    }

    public static final int MAX_SEATS = 6;
    private final Seat[] seats = new Seat[MAX_SEATS];
    private final Deck deck = new Deck();
    private final List<Dealt> communityCards = new ArrayList<>();

    private TablePhase phase = TablePhase.WAITING_FOR_PLAYERS;
    private long pot = 0;
    private long currentHighestBet = 0;
    private int dealerButton = 0;
    private int currentTurnSeat = -1;
    private long smallBlind = 10;
    private long bigBlind = 20;

    /**
     * Monotonic tick counter shared by every client at the table: one per
     * {@link #tickServer()} call. Every dealt card and every event is stamped with it,
     * so two screens watching the same table animate the same card on the same tick.
     */
    private int animClock = 0;
    private int handNumber = 0;
    private final List<TableEvent> events = new ArrayList<>();

    private boolean autoFillBots = true;
    private UUID tableOwner = null;

    // Timer/delay for bot actions and phase transitions
    private int botActionDelayTicks = 0;
    private int showdownTimerTicks = 0;
    private String handSummary = "Waiting for players...";

    public TexasHoldemTable() {
        for (int i = 0; i < MAX_SEATS; i++) {
            seats[i] = new Seat(i);
        }
    }

    public Seat[] getSeats() { return seats; }
    public List<Dealt> getCommunityCards() { return communityCards; }
    public TablePhase getPhase() { return phase; }
    public long getPot() { return pot; }
    public long getCurrentHighestBet() { return currentHighestBet; }
    public int getCurrentTurnSeat() { return currentTurnSeat; }
    public int getAnimClock() { return animClock; }
    public int getHandNumber() { return handNumber; }
    public List<TableEvent> getRecentEvents() { return events; }

    /**
     * The line the dealer is currently saying: the newest event, or a sensible prompt
     * when nothing has happened yet.
     */
    public String getDealerSay() {
        if (!events.isEmpty()) {
            return events.get(events.size() - 1).text;
        }
        if (currentTurnSeat >= 0 && currentTurnSeat < MAX_SEATS && seats[currentTurnSeat].isOccupied()) {
            return seats[currentTurnSeat].playerName + " to act";
        }
        return "Place your bets";
    }

    public boolean isAutoFillBots() { return autoFillBots; }
    public void setAutoFillBots(boolean autoFill) { this.autoFillBots = autoFill; }
    public UUID getTableOwner() { return tableOwner; }
    public void setTableOwner(UUID owner) { if (this.tableOwner == null) this.tableOwner = owner; }
    public String getHandSummary() { return handSummary; }

    public int getOccupiedCount() {
        int count = 0;
        for (Seat s : seats) {
            if (s.isOccupied()) count++;
        }
        return count;
    }

    public int getActivePlayerCount() {
        int count = 0;
        for (Seat s : seats) {
            if (s.isOccupied() && !s.folded && s.stack + s.currentBet > 0) count++;
        }
        return count;
    }

    public boolean joinTable(int seatIdx, UUID playerId, String name, long buyIn) {
        if (seatIdx < 0 || seatIdx >= MAX_SEATS) return false;
        Seat s = seats[seatIdx];
        if (s.isOccupied()) return false;

        s.playerId = playerId;
        s.playerName = name;
        s.stack = buyIn;
        s.isBot = false;
        s.botType = "";
        s.resetForNewHand();

        if (tableOwner == null) {
            tableOwner = playerId;
        }

        pushEvent("JOIN", seatIdx, name + " sits down");
        checkStartGame();
        return true;
    }

    public void leaveTable(UUID playerId) {
        for (int i = 0; i < MAX_SEATS; i++) {
            Seat s = seats[i];
            if (s.isOccupied() && playerId.equals(s.playerId)) {
                String who = s.playerName;
                s.playerId = null;
                s.playerName = null;
                s.stack = 0;
                s.isBot = false;
                s.resetForNewHand();
                if (playerId.equals(tableOwner)) {
                    assignNewOwner();
                }
                pushEvent("LEAVE", i, who + " leaves");
                break;
            }
        }
    }

    public void kickBot(int seatIdx) {
        if (seatIdx >= 0 && seatIdx < MAX_SEATS) {
            Seat s = seats[seatIdx];
            if (s.isOccupied() && s.isBot) {
                String who = s.playerName;
                s.playerId = null;
                s.playerName = null;
                s.stack = 0;
                s.isBot = false;
                s.resetForNewHand();
                pushEvent("LEAVE", seatIdx, who + " leaves");
            }
        }
    }

    private void assignNewOwner() {
        tableOwner = null;
        for (Seat s : seats) {
            if (s.isOccupied() && !s.isBot) {
                tableOwner = s.playerId;
                break;
            }
        }
    }

    public void fillBotsIfEnabled() {
        if (!autoFillBots) return;
        String[] botNames = {"Tight Tony", "Bluffing Bob", "Lucky Lucy", "Wild Wendy", "Poker Pete"};
        String[] botPersonalities = {"TIGHT", "BLUFF", "CALLING_STATION", "MANIAC", "BALANCED"};

        int botIdx = 0;
        for (int i = 0; i < MAX_SEATS; i++) {
            if (!seats[i].isOccupied()) {
                seats[i].playerId = UUID.randomUUID();
                seats[i].playerName = botNames[botIdx % botNames.length];
                seats[i].botType = botPersonalities[botIdx % botPersonalities.length];
                seats[i].isBot = true;
                seats[i].stack = 1000;
                seats[i].resetForNewHand();
                pushEvent("JOIN", i, seats[i].playerName + " (house) sits down");
                botIdx++;
            }
        }
    }

    public void checkStartGame() {
        if (phase == TablePhase.WAITING_FOR_PLAYERS || phase == TablePhase.SHOWDOWN) {
            if (autoFillBots) {
                fillBotsIfEnabled();
            }
            if (getActivePlayerCount() >= 2) {
                startNewHand();
            }
        }
    }

    public void startNewHand() {
        deck.reset();
        communityCards.clear();
        pot = 0;
        currentHighestBet = 0;
        handNumber++;
        handSummary = "Dealing new hand...";

        // Advance dealer button to next occupied seat
        dealerButton = nextOccupiedSeat(dealerButton);

        // Reset seats with chips
        for (Seat s : seats) {
            if (s.isOccupied()) {
                if (s.stack <= 0) {
                    if (s.isBot) {
                        s.stack = 1000; // rebuy for bot
                        s.resetForNewHand();
                    } else {
                        // Human broke, leave seat
                        leaveTable(s.playerId);
                    }
                } else {
                    s.resetForNewHand();
                }
            }
        }

        if (getActivePlayerCount() < 2) {
            phase = TablePhase.WAITING_FOR_PLAYERS;
            handSummary = "Waiting for more players...";
            return;
        }

        // Deal hole cards
        for (int c = 0; c < 2; c++) {
            for (Seat s : seats) {
                if (s.isOccupied() && !s.folded) {
                    s.holeCards[c] = deck.draw();
                    s.holeTicks[c] = animClock;
                }
            }
        }
        pushEvent("DEAL", -1, "Hand " + handNumber + ": hole cards dealt");

        // Post blinds
        int sbSeat = nextOccupiedSeat(dealerButton);
        int bbSeat = nextOccupiedSeat(sbSeat);

        postBlind(seats[sbSeat], smallBlind, "small");
        postBlind(seats[bbSeat], bigBlind, "big");
        currentHighestBet = bigBlind;

        phase = TablePhase.PRE_FLOP;
        currentTurnSeat = nextOccupiedSeat(bbSeat);
        botActionDelayTicks = 20; // 1 second delay
    }

    private void postBlind(Seat s, long amount, String label) {
        long actual = Math.min(s.stack, amount);
        s.stack -= actual;
        s.currentBet = actual;
        pot += actual;
        s.lastAction = "Blind (" + actual + ")";
        if (s.stack == 0) s.allIn = true;
        pushEvent("BLIND", s.seatIndex, s.playerName + " posts " + label + " blind " + actual);
    }

    private int nextOccupiedSeat(int current) {
        for (int i = 1; i <= MAX_SEATS; i++) {
            int next = (current + i) % MAX_SEATS;
            if (seats[next].isOccupied() && !seats[next].folded && (seats[next].stack > 0 || seats[next].allIn)) {
                return next;
            }
        }
        return current;
    }

    public boolean handlePlayerAction(UUID playerId, String action, long raiseAmount) {
        if (currentTurnSeat < 0 || currentTurnSeat >= MAX_SEATS) return false;
        Seat current = seats[currentTurnSeat];
        if (!current.isOccupied() || current.isBot || !playerId.equals(current.playerId)) {
            return false;
        }

        executeAction(current, action, raiseAmount);
        return true;
    }

    private void executeAction(Seat s, String action, long raiseAmount) {
        long callCost = currentHighestBet - s.currentBet;
        long betBefore = s.currentBet;

        switch (action.toUpperCase()) {
            case "FOLD" -> {
                s.folded = true;
                s.lastAction = "Folded";
            }
            case "CHECK" -> {
                if (callCost <= 0) {
                    s.lastAction = "Checked";
                } else {
                    // Must fold if can't check and chose check
                    s.folded = true;
                    s.lastAction = "Folded";
                }
            }
            case "CALL" -> {
                long pay = Math.min(s.stack, callCost);
                s.stack -= pay;
                s.currentBet += pay;
                pot += pay;
                s.lastAction = "Called " + s.currentBet;
                if (s.stack == 0) s.allIn = true;
            }
            case "RAISE" -> {
                long totalBet = currentHighestBet + Math.max(bigBlind, raiseAmount);
                long needed = totalBet - s.currentBet;
                if (needed > s.stack) {
                    // All in
                    needed = s.stack;
                    totalBet = s.currentBet + needed;
                    s.allIn = true;
                }
                s.stack -= needed;
                s.currentBet = totalBet;
                pot += needed;
                currentHighestBet = Math.max(currentHighestBet, totalBet);
                s.lastAction = "Raised to " + totalBet;
            }
        }

        long paid = s.currentBet - betBefore;
        String announce;
        if (s.folded) {
            announce = s.playerName + " folds";
        } else if ("RAISE".equalsIgnoreCase(action)) {
            announce = s.playerName + " raises to " + s.currentBet;
        } else if (paid > 0) {
            announce = s.playerName + " calls " + paid;
        } else {
            announce = s.playerName + " checks";
        }
        pushEvent("ACTION", s.seatIndex, announce);

        advanceTurn();
    }

    public void advanceTurn() {
        // Check if only 1 player remaining
        int nonFolded = 0;
        Seat lastStanding = null;
        for (Seat s : seats) {
            if (s.isOccupied() && !s.folded) {
                nonFolded++;
                lastStanding = s;
            }
        }

        if (nonFolded <= 1 && lastStanding != null) {
            // Everyone else folded, award pot immediately
            lastStanding.stack += pot;
            handSummary = lastStanding.playerName + " won " + pot + " Chips (all folded)";
            pushEvent("SHOWDOWN", lastStanding.seatIndex, lastStanding.playerName + " takes it uncontested");
            pushEvent("SETTLE", lastStanding.seatIndex, lastStanding.playerName + " wins " + pot + " (everyone folded)");
            phase = TablePhase.SHOWDOWN;
            showdownTimerTicks = 80; // 4 seconds delay before next hand
            return;
        }

        // Check if betting round complete:
        // Round is complete when all active non-allIn players have matched currentHighestBet
        boolean roundComplete = true;
        for (Seat s : seats) {
            if (s.isOccupied() && !s.folded && !s.allIn) {
                if (s.currentBet < currentHighestBet || "".equals(s.lastAction)) {
                    roundComplete = false;
                    break;
                }
            }
        }

        if (roundComplete) {
            advancePhase();
        } else {
            currentTurnSeat = nextTurnSeat(currentTurnSeat);
            botActionDelayTicks = 25; // delay for realism
        }
    }

    private int nextTurnSeat(int current) {
        for (int i = 1; i <= MAX_SEATS; i++) {
            int next = (current + i) % MAX_SEATS;
            Seat s = seats[next];
            if (s.isOccupied() && !s.folded && !s.allIn) {
                return next;
            }
        }
        return current;
    }

    private void advancePhase() {
        // Reset bets for next street
        for (Seat s : seats) {
            s.currentBet = 0;
            if (!s.folded) s.lastAction = "";
        }
        currentHighestBet = 0;

        switch (phase) {
            case PRE_FLOP -> {
                phase = TablePhase.FLOP;
                dealCommunity(3);
                handSummary = "Flop dealt";
                pushEvent("FLOP", -1, "Flop: " + boardText(3));
            }
            case FLOP -> {
                phase = TablePhase.TURN;
                dealCommunity(1);
                handSummary = "Turn card dealt";
                pushEvent("TURN", -1, "Turn: " + boardText(1));
            }
            case TURN -> {
                phase = TablePhase.RIVER;
                dealCommunity(1);
                handSummary = "River card dealt";
                pushEvent("RIVER", -1, "River: " + boardText(1));
            }
            case RIVER -> {
                phase = TablePhase.SHOWDOWN;
                evaluateShowdown();
                showdownTimerTicks = 120; // 6s delay
                return;
            }
            default -> {}
        }

        currentTurnSeat = nextOccupiedSeat(dealerButton);
        botActionDelayTicks = 30;
    }

    /** Draws {@code count} cards onto the board, stamped with the tick they landed on. */
    private void dealCommunity(int count) {
        for (int i = 0; i < count; i++) {
            communityCards.add(new Dealt(deck.draw(), animClock));
        }
    }

    /** The last {@code count} board cards as text, for the dealer's line. */
    private String boardText(int count) {
        int from = Math.max(0, communityCards.size() - count);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < communityCards.size(); i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(communityCards.get(i).card.toShortString());
        }
        return sb.toString();
    }

    /** The board as plain cards, for the evaluator. */
    private List<Card> communityCardsPlain() {
        List<Card> plain = new ArrayList<>(communityCards.size());
        for (Dealt d : communityCards) {
            plain.add(d.card);
        }
        return plain;
    }

    private void evaluateShowdown() {
        List<Seat> contenders = new ArrayList<>();
        for (Seat s : seats) {
            if (s.isOccupied() && !s.folded) {
                contenders.add(s);
            }
        }

        if (contenders.isEmpty()) return;

        Seat winner = null;
        PokerHandEvaluator.HandResult bestHand = null;

        for (Seat s : contenders) {
            List<Card> all7 = communityCardsPlain();
            all7.add(s.holeCards[0]);
            all7.add(s.holeCards[1]);
            PokerHandEvaluator.HandResult res = PokerHandEvaluator.evaluate7(all7);

            if (bestHand == null || res.compareTo(bestHand) > 0) {
                bestHand = res;
                winner = s;
            }
        }

        if (winner != null && bestHand != null) {
            long won = pot;
            winner.stack += won;
            handSummary = winner.playerName + " won " + won + " Chips with " + bestHand.description + "!";
            pushEvent("SHOWDOWN", winner.seatIndex,
                    "Showdown: " + winner.playerName + " shows " + bestHand.description);
            pushEvent("SETTLE", winner.seatIndex,
                    winner.playerName + " wins " + won + " with " + describeForDealer(bestHand));
        }
    }

    public void tickServer() {
        animClock++;

        if (phase == TablePhase.SHOWDOWN) {
            if (showdownTimerTicks > 0) {
                showdownTimerTicks--;
                if (showdownTimerTicks == 0) {
                    checkStartGame();
                }
            }
            return;
        }

        if (phase == TablePhase.WAITING_FOR_PLAYERS) {
            checkStartGame();
            return;
        }

        // Bot turn handling
        if (currentTurnSeat >= 0 && currentTurnSeat < MAX_SEATS) {
            Seat current = seats[currentTurnSeat];
            if (current.isOccupied() && current.isBot && !current.folded && !current.allIn) {
                if (botActionDelayTicks > 0) {
                    botActionDelayTicks--;
                } else {
                    executeBotDecision(current);
                }
            }
        }
    }

    private void executeBotDecision(Seat bot) {
        long callCost = currentHighestBet - bot.currentBet;
        Random rng = new Random();

        // Evaluate bot hand strength
        List<Card> cards = communityCardsPlain();
        cards.add(bot.holeCards[0]);
        cards.add(bot.holeCards[1]);

        int strengthRank = 1;
        if (cards.size() >= 5) {
            strengthRank = PokerHandEvaluator.evaluate7(cards).type.rank;
        } else {
            // Pre-flop basic strength: pairs or high card
            if (bot.holeCards[0].rank == bot.holeCards[1].rank) strengthRank = 4;
            else if (bot.holeCards[0].rank.value >= 12 || bot.holeCards[1].rank.value >= 12) strengthRank = 2;
        }

        String personality = (bot.botType != null) ? bot.botType : "BALANCED";

        switch (personality) {
            case "TIGHT" -> {
                // Folds easily unless strong hand
                if (callCost == 0) {
                    executeAction(bot, "CHECK", 0);
                } else if (strengthRank >= 3 || (callCost <= bigBlind && strengthRank >= 2)) {
                    if (strengthRank >= 5 && rng.nextBoolean()) {
                        executeAction(bot, "RAISE", bigBlind * 2);
                    } else {
                        executeAction(bot, "CALL", 0);
                    }
                } else {
                    executeAction(bot, "FOLD", 0);
                }
            }
            case "BLUFF" -> {
                // Aggressive, bets and bluffs
                if (rng.nextFloat() < 0.35f && bot.stack >= bigBlind * 2) {
                    executeAction(bot, "RAISE", bigBlind * 2);
                } else if (callCost == 0) {
                    if (rng.nextBoolean()) executeAction(bot, "RAISE", bigBlind);
                    else executeAction(bot, "CHECK", 0);
                } else if (callCost <= bot.stack / 3 || strengthRank >= 2) {
                    executeAction(bot, "CALL", 0);
                } else {
                    executeAction(bot, "FOLD", 0);
                }
            }
            case "CALLING_STATION" -> {
                // Never folds if cheap, rarely raises
                if (callCost == 0) executeAction(bot, "CHECK", 0);
                else if (callCost <= bot.stack / 2) executeAction(bot, "CALL", 0);
                else if (strengthRank >= 4) executeAction(bot, "CALL", 0);
                else executeAction(bot, "FOLD", 0);
            }
            case "MANIAC" -> {
                // Wild bets
                if (rng.nextFloat() < 0.5f && bot.stack >= bigBlind * 2) {
                    executeAction(bot, "RAISE", bigBlind * 3);
                } else if (callCost == 0) {
                    executeAction(bot, "CHECK", 0);
                } else {
                    executeAction(bot, "CALL", 0);
                }
            }
            default -> { // BALANCED
                if (callCost == 0) {
                    if (strengthRank >= 4 && rng.nextBoolean()) executeAction(bot, "RAISE", bigBlind * 2);
                    else executeAction(bot, "CHECK", 0);
                } else if (strengthRank >= 3 || callCost <= bigBlind) {
                    executeAction(bot, "CALL", 0);
                } else {
                    executeAction(bot, "FOLD", 0);
                }
            }
        }
    }

    // ---------------------------------------------------------------- narration

    /** A hand described the way a dealer would say it: "a flush", "two pair". */
    private static String describeForDealer(PokerHandEvaluator.HandResult hand) {
        String article = switch (hand.type) {
            case FLUSH, STRAIGHT, FULL_HOUSE, STRAIGHT_FLUSH, ROYAL_FLUSH -> "a ";
            default -> "";
        };
        return article + hand.type.name.toLowerCase(Locale.ROOT);
    }

    /** Records a beat on the dealer's ticker, keeping only the newest eight. */
    private void pushEvent(String kind, int seat, String text) {
        events.add(new TableEvent(animClock, kind, seat, text));
        while (events.size() > 8) {
            events.remove(0);
        }
    }
}
