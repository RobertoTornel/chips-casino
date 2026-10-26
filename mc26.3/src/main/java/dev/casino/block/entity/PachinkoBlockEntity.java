package dev.casino.block.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.casino.Casino;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.game.PachinkoEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PachinkoBlockEntity extends BaseCasinoBlockEntity {
    private final PachinkoEngine engine = new PachinkoEngine();
    private final Map<UUID, PachinkoEngine.BallDropResult> lastDrops = new ConcurrentHashMap<>();

    public PachinkoBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.PACHINKO_BE_TYPE, pos, state);
    }

    @Override
    public String getGameType() {
        return "PACHINKO";
    }

    @Override
    public String serializeState(ServerPlayer player) {
        JsonObject obj = new JsonObject();
        PachinkoEngine.BallDropResult res = lastDrops.get(player.getUUID());
        if (res != null) {
            obj.addProperty("outcome", res.outcome.name());
            obj.addProperty("label", res.outcome.label);
            obj.addProperty("payout", res.payout);

            JsonArray path = new JsonArray();
            for (int[] pt : res.trajectory) {
                JsonArray pair = new JsonArray();
                pair.add(pt[0]);
                pair.add(pt[1]);
                path.add(pair);
            }
            obj.add("trajectory", path);
        } else {
            obj.addProperty("outcome", "NONE");
            obj.addProperty("label", "");
            obj.addProperty("payout", 0);
        }
        return GSON.toJson(obj);
    }

    @Override
    protected void handleGameAction(ServerPlayer player, String action, String data) {
        if ("DROP".equalsIgnoreCase(action)) {
            try {
                JsonObject json = GSON.fromJson(data, JsonObject.class);
                long bet = json.get("bet").getAsLong();
                if (bet > 0 && CasinoAccountManager.deductBalance(player.getUUID(), bet)) {
                    PachinkoEngine.BallDropResult res = engine.dropBall(bet);
                    lastDrops.put(player.getUUID(), res);
                    if (res.payout > 0) {
                        CasinoAccountManager.addBalance(player.getUUID(), res.payout);
                    }
                    syncTo(player);
                }
            } catch (Exception ignored) {}
        }
    }
}
