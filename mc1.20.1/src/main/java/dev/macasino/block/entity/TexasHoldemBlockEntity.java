package dev.macasino.block.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.macasino.Casino;
import dev.macasino.economy.CasinoAccountManager;
import dev.macasino.game.TexasHoldemTable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

public class TexasHoldemBlockEntity extends BaseCasinoBlockEntity {
    /** Heartbeat period in ticks: 20 tps / 4 = 5 state packets per second per viewer. */
    private static final int HEARTBEAT_TICKS = 4;

    private final TexasHoldemTable table = new TexasHoldemTable();

    private int heartbeat;
    private TexasHoldemTable.TablePhase lastPhase = TexasHoldemTable.TablePhase.WAITING_FOR_PLAYERS;
    private int lastTurnSeat = -1;
    private int lastEventCount = -1;
    private int lastEventTick = -1;
    private String lastEventKind = "";

    public TexasHoldemBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.TEXAS_HOLDEM_BE_TYPE, pos, state);
    }

    @Override
    public String getGameType() {
        return "TEXAS_HOLDEM";
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, TexasHoldemBlockEntity be) {
        if (level.isClientSide()) {
            return;
        }
        // Clean up disconnected players from viewers
        for (ServerPlayer p : new ArrayList<>(be.viewers)) {
            if (p.isRemoved()) {
                be.cleanupPlayer(p);
            }
        }

        be.table.tickServer();
        be.syncIfChanged();
    }

    /**
     * Broadcasts on real beats only: phase change, turn moving, a new dealer line, or at
     * least once every {@value #HEARTBEAT_TICKS} ticks. Sending the whole table state every
     * tick would be 20 packets per second per viewer.
     */
    private void syncIfChanged() {
        TexasHoldemTable.TablePhase phase = table.getPhase();
        int turnSeat = table.getCurrentTurnSeat();
        List<TexasHoldemTable.TableEvent> recent = table.getRecentEvents();
        int eventCount = recent.size();
        int eventTick = eventCount == 0 ? -1 : recent.get(eventCount - 1).tick;
        String eventKind = eventCount == 0 ? "" : recent.get(eventCount - 1).kind;

        // Size alone would miss a full ring buffer, where one event in evicts one event out.
        boolean newEvent = eventCount > 0
                && (eventCount != lastEventCount || eventTick != lastEventTick || !eventKind.equals(lastEventKind));
        lastEventCount = eventCount;
        lastEventTick = eventTick;
        lastEventKind = eventKind;

        boolean changed = phase != lastPhase || turnSeat != lastTurnSeat || newEvent;
        lastPhase = phase;
        lastTurnSeat = turnSeat;

        if (++heartbeat >= HEARTBEAT_TICKS) {
            heartbeat = 0;
            changed = true;
        }

        if (changed) {
            broadcastSync();
        }
    }

    @Override
    public String serializeState(ServerPlayer player) {
        JsonObject obj = new JsonObject();
        obj.addProperty("phase", table.getPhase().name());
        obj.addProperty("pot", table.getPot());
        obj.addProperty("currentHighestBet", table.getCurrentHighestBet());
        obj.addProperty("turnSeat", table.getCurrentTurnSeat());
        obj.addProperty("summary", table.getHandSummary());
        obj.addProperty("autoFillBots", table.isAutoFillBots());
        obj.addProperty("animClock", table.getAnimClock());
        obj.addProperty("handNumber", table.getHandNumber());
        obj.addProperty("dealerSay", table.getDealerSay());
        obj.addProperty("isOwner", player.getUUID().equals(table.getTableOwner()));

        // Community cards, each stamped with the tick it landed on
        JsonArray commCards = new JsonArray();
        for (TexasHoldemTable.Dealt d : table.getCommunityCards()) {
            JsonObject cardObj = new JsonObject();
            cardObj.addProperty("c", d.card.toShortString());
            cardObj.addProperty("t", d.tick);
            commCards.add(cardObj);
        }
        obj.add("communityCards", commCards);

        // Seats
        JsonArray seatsArr = new JsonArray();
        for (TexasHoldemTable.Seat s : table.getSeats()) {
            JsonObject seatObj = new JsonObject();
            seatObj.addProperty("index", s.seatIndex);
            seatObj.addProperty("occupied", s.isOccupied());
            if (s.isOccupied()) {
                seatObj.addProperty("name", s.playerName);
                seatObj.addProperty("stack", s.stack);
                seatObj.addProperty("currentBet", s.currentBet);
                seatObj.addProperty("isBot", s.isBot);
                seatObj.addProperty("folded", s.folded);
                seatObj.addProperty("allIn", s.allIn);
                seatObj.addProperty("lastAction", s.lastAction);

                JsonArray cards = new JsonArray();
                if (s.holeCards[0] != null && s.holeCards[1] != null) {
                    boolean showCards = s.playerId.equals(player.getUUID()) || table.getPhase() == TexasHoldemTable.TablePhase.SHOWDOWN;
                    if (showCards) {
                        cards.add(s.holeCards[0].toShortString());
                        cards.add(s.holeCards[1].toShortString());
                    } else {
                        cards.add("??");
                        cards.add("??");
                    }
                }
                seatObj.add("cards", cards);

                JsonArray ticks = new JsonArray();
                ticks.add(s.holeTicks[0]);
                ticks.add(s.holeTicks[1]);
                seatObj.add("holeTicks", ticks);
            }
            seatsArr.add(seatObj);
        }
        obj.add("seats", seatsArr);

        // Dealer ticker: the newest beats, oldest first
        JsonArray eventsArr = new JsonArray();
        for (TexasHoldemTable.TableEvent e : table.getRecentEvents()) {
            JsonObject eventObj = new JsonObject();
            eventObj.addProperty("t", e.tick);
            eventObj.addProperty("k", e.kind);
            eventObj.addProperty("s", e.seat);
            eventObj.addProperty("x", e.text);
            eventsArr.add(eventObj);
        }
        obj.add("events", eventsArr);

        return GSON.toJson(obj);
    }

    @Override
    protected void handleGameAction(ServerPlayer player, String action, String data) {
        switch (action.toUpperCase()) {
            case "JOIN" -> {
                try {
                    JsonObject json = GSON.fromJson(data, JsonObject.class);
                    int seatIdx = json.get("seat").getAsInt();
                    long buyIn = json.get("buyIn").getAsLong();
                    if (buyIn >= 100 && CasinoAccountManager.deductBalance(player.getUUID(), buyIn)) {
                        if (table.joinTable(seatIdx, player.getUUID(), player.getName().getString(), buyIn)) {
                            broadcastSync();
                        } else {
                            // Refund buyIn
                            CasinoAccountManager.addBalance(player.getUUID(), buyIn);
                        }
                    }
                } catch (Exception ignored) {}
            }
            case "LEAVE" -> {
                for (TexasHoldemTable.Seat s : table.getSeats()) {
                    if (s.isOccupied() && player.getUUID().equals(s.playerId)) {
                        if (s.stack > 0) {
                            CasinoAccountManager.addBalance(player.getUUID(), s.stack);
                        }
                        table.leaveTable(player.getUUID());
                        broadcastSync();
                        break;
                    }
                }
            }
            case "FOLD", "CHECK", "CALL", "RAISE" -> {
                long raiseAmt = 0;
                try {
                    JsonObject json = GSON.fromJson(data, JsonObject.class);
                    if (json.has("raise")) raiseAmt = json.get("raise").getAsLong();
                } catch (Exception ignored) {}

                if (table.handlePlayerAction(player.getUUID(), action, raiseAmt)) {
                    broadcastSync();
                }
            }
            case "TOGGLE_BOTS" -> {
                if (player.getUUID().equals(table.getTableOwner())) {
                    table.setAutoFillBots(!table.isAutoFillBots());
                    broadcastSync();
                }
            }
            case "KICK_BOT" -> {
                if (player.getUUID().equals(table.getTableOwner())) {
                    try {
                        JsonObject json = GSON.fromJson(data, JsonObject.class);
                        int seatIdx = json.get("seat").getAsInt();
                        table.kickBot(seatIdx);
                        broadcastSync();
                    } catch (Exception ignored) {}
                }
            }
        }
    }
}
