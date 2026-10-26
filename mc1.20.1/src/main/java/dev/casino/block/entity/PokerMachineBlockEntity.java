package dev.casino.block.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.casino.Casino;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.game.Card;
import dev.casino.game.VideoPokerEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PokerMachineBlockEntity extends BaseCasinoBlockEntity {
    private final Map<UUID, VideoPokerEngine> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, VideoPokerEngine.PokerRoundResult> lastResults = new ConcurrentHashMap<>();

    public PokerMachineBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.POKER_MACHINE_BE_TYPE, pos, state);
    }

    @Override
    public String getGameType() {
        return "VIDEO_POKER";
    }

    private VideoPokerEngine getOrCreate(UUID uuid) {
        return sessions.computeIfAbsent(uuid, k -> new VideoPokerEngine());
    }

    @Override
    public String serializeState(ServerPlayer player) {
        VideoPokerEngine engine = getOrCreate(player.getUUID());
        JsonObject obj = new JsonObject();
        obj.addProperty("state", engine.getState().name());
        obj.addProperty("bet", engine.getCurrentBet());

        JsonArray cardsArr = new JsonArray();
        for (Card c : engine.getHand()) cardsArr.add(c.toShortString());
        obj.add("cards", cardsArr);

        JsonArray heldArr = new JsonArray();
        for (boolean h : engine.getHeld()) heldArr.add(h);
        obj.add("held", heldArr);

        VideoPokerEngine.PokerRoundResult res = lastResults.get(player.getUUID());
        if (res != null) {
            obj.addProperty("handName", res.handResult.description);
            obj.addProperty("payout", res.payout);
            obj.addProperty("multiplier", res.multiplier);
        } else {
            obj.addProperty("handName", "");
            obj.addProperty("payout", 0);
            obj.addProperty("multiplier", 0);
        }

        return GSON.toJson(obj);
    }

    @Override
    protected void handleGameAction(ServerPlayer player, String action, String data) {
        VideoPokerEngine engine = getOrCreate(player.getUUID());

        switch (action.toUpperCase()) {
            case "DEAL" -> {
                try {
                    JsonObject json = GSON.fromJson(data, JsonObject.class);
                    long bet = json.get("bet").getAsLong();
                    if (bet > 0 && CasinoAccountManager.deductBalance(player.getUUID(), bet)) {
                        engine.deal(bet);
                        lastResults.remove(player.getUUID());
                        syncTo(player);
                    }
                } catch (Exception ignored) {}
            }
            case "TOGGLE_HOLD" -> {
                try {
                    JsonObject json = GSON.fromJson(data, JsonObject.class);
                    int idx = json.get("index").getAsInt();
                    engine.toggleHold(idx);
                    syncTo(player);
                } catch (Exception ignored) {}
            }
            case "DRAW" -> {
                VideoPokerEngine.PokerRoundResult res = engine.draw();
                if (res != null) {
                    lastResults.put(player.getUUID(), res);
                    if (res.payout > 0) {
                        CasinoAccountManager.addBalance(player.getUUID(), res.payout);
                    }
                    syncTo(player);
                }
            }
        }
    }
}
