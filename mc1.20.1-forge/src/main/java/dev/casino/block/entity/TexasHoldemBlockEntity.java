package dev.casino.block.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.casino.Casino;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.game.Card;
import dev.casino.game.TexasHoldemTable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public class TexasHoldemBlockEntity extends BaseCasinoBlockEntity {
    private final TexasHoldemTable table = new TexasHoldemTable();

    public TexasHoldemBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.TEXAS_HOLDEM_BE_TYPE.get(), pos, state);
    }

    @Override
    public String getGameType() {
        return "TEXAS_HOLDEM";
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, TexasHoldemBlockEntity be) {
        if (!level.isClientSide()) {
            TexasHoldemTable.TablePhase before = be.table.getPhase();
            int turnBefore = be.table.getCurrentTurnSeat();
            be.table.tickServer();

            // Broadcast sync when phase or active turn changed or pot changed
            if (be.table.getPhase() != before || be.table.getCurrentTurnSeat() != turnBefore) {
                be.broadcastSync();
            }
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
        obj.addProperty("isOwner", player.getUUID().equals(table.getTableOwner()));

        // Community cards
        JsonArray commCards = new JsonArray();
        for (Card c : table.getCommunityCards()) {
            commCards.add(c.toShortString());
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
            }
            seatsArr.add(seatObj);
        }
        obj.add("seats", seatsArr);

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
