package dev.emccasino.block.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.emccasino.EmcCasino;
import dev.emccasino.economy.CasinoAccountManager;
import dev.emccasino.game.BlackjackEngine;
import dev.emccasino.game.Card;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BlackjackBlockEntity extends BaseCasinoBlockEntity {
    private final Map<UUID, BlackjackEngine> sessions = new ConcurrentHashMap<>();

    public BlackjackBlockEntity(BlockPos pos, BlockState state) {
        super(EmcCasino.BLACKJACK_BE_TYPE, pos, state);
    }

    @Override
    public String getGameType() {
        return "BLACKJACK";
    }

    private BlackjackEngine getOrCreate(UUID uuid) {
        return sessions.computeIfAbsent(uuid, k -> new BlackjackEngine());
    }

    @Override
    public String serializeState(ServerPlayer player) {
        BlackjackEngine engine = getOrCreate(player.getUUID());
        JsonObject obj = new JsonObject();
        obj.addProperty("status", engine.getStatus().name());
        obj.addProperty("result", engine.getResult().name());
        obj.addProperty("bet", engine.getCurrentBet());
        obj.addProperty("payout", engine.getPayout());

        // Player cards
        JsonArray pCards = new JsonArray();
        for (Card c : engine.getPlayerHand().cards) {
            pCards.add(c.toShortString());
        }
        obj.add("playerCards", pCards);
        obj.addProperty("playerTotal", engine.getPlayerHand().calculateValue());

        // Dealer cards
        JsonArray dCards = new JsonArray();
        for (int i = 0; i < engine.getDealerHand().cards.size(); i++) {
            Card c = engine.getDealerHand().cards.get(i);
            // In player turn, hide dealer's second card
            if (i == 1 && engine.getStatus() == BlackjackEngine.GameStatus.PLAYER_TURN) {
                dCards.add("??");
            } else {
                dCards.add(c.toShortString());
            }
        }
        obj.add("dealerCards", dCards);
        if (engine.getStatus() == BlackjackEngine.GameStatus.PLAYER_TURN && !engine.getDealerHand().cards.isEmpty()) {
            obj.addProperty("dealerTotal", engine.getDealerHand().cards.get(0).rank.value >= 10 ? 10 : engine.getDealerHand().cards.get(0).rank.value);
        } else {
            obj.addProperty("dealerTotal", engine.getDealerHand().calculateValue());
        }

        return GSON.toJson(obj);
    }

    @Override
    protected void handleGameAction(ServerPlayer player, String action, String data) {
        BlackjackEngine engine = getOrCreate(player.getUUID());

        switch (action.toUpperCase()) {
            case "DEAL" -> {
                try {
                    JsonObject json = GSON.fromJson(data, JsonObject.class);
                    long bet = json.get("bet").getAsLong();
                    if (bet > 0 && CasinoAccountManager.deductBalance(player.getUUID(), bet)) {
                        engine.startRound(bet);
                        if (engine.getStatus() == BlackjackEngine.GameStatus.FINISHED && engine.getPayout() > 0) {
                            CasinoAccountManager.addBalance(player.getUUID(), engine.getPayout());
                        }
                        syncTo(player);
                    }
                } catch (Exception ignored) {}
            }
            case "HIT" -> {
                engine.hit();
                syncTo(player);
            }
            case "STAND" -> {
                engine.stand();
                if (engine.getPayout() > 0) {
                    CasinoAccountManager.addBalance(player.getUUID(), engine.getPayout());
                }
                syncTo(player);
            }
            case "DOUBLE" -> {
                long bet = engine.getCurrentBet();
                if (CasinoAccountManager.deductBalance(player.getUUID(), bet)) {
                    engine.doubleDown();
                    if (engine.getPayout() > 0) {
                        CasinoAccountManager.addBalance(player.getUUID(), engine.getPayout());
                    }
                    syncTo(player);
                }
            }
        }
    }
}
