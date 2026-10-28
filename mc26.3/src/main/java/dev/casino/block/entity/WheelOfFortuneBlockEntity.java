package dev.casino.block.entity;

import com.google.gson.JsonObject;
import com.google.gson.JsonObject;
import dev.casino.Casino;
import dev.casino.economy.CasinoAccountManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wheel of Fortune: spinning wheel with colored segments.
 */
public class WheelOfFortuneBlockEntity extends BaseCasinoBlockEntity {
    private final Map<UUID, Long> bets = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastSegment = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPayout = new ConcurrentHashMap<>();

    public static final int MIN_BET = 10;
    public static final int MAX_BET = 10_000;

    public WheelOfFortuneBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.WHEEL_OF_FORTUNE_BE_TYPE, pos, state);
    }

    @Override
    public String getGameType() {
        return "WHEEL_OF_FORTUNE";
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, WheelOfFortuneBlockEntity be) {
        if (level.isClientSide()) {
            return;
        }
        for (ServerPlayer p : new java.util.ArrayList<>(be.viewers)) {
            if (p.isRemoved()) {
                be.cleanupPlayer(p);
            }
        }
        be.broadcastSync();
    }

    @Override
    protected void handleGameAction(ServerPlayer player, String action, String data) {
        UUID uuid = player.getUUID();
        switch (action.toUpperCase()) {
            case "SET_BET" -> {
                try {
                    long bet = 10;
                    if (bet < MIN_BET || bet > MAX_BET) return;
                    if (!CasinoAccountManager.deductBalance(uuid, bet)) return;
                    bets.put(uuid, bet);
                    // Spin wheel: 8 segments, random result
                    int segment = (int) (Math.random() * 8);
                    lastSegment.put(uuid, segment);
                    // Segment multipliers: 0=2x, 1=5x, 2=10x, 3=20x, 4=50x, 5=100x, 6=5x, 7=2x
                    long[] multipliers = {2, 5, 10, 20, 50, 100, 5, 2};
                    long payout = bet * multipliers[segment];
                    lastPayout.put(uuid, payout);
                    CasinoAccountManager.addBalance(uuid, payout);
                    syncTo(player);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public String serializeState(ServerPlayer player) {
        JsonObject obj = new JsonObject();
        obj.addProperty("bet", bets.getOrDefault(player.getUUID(), 0L));
        obj.addProperty("lastSegment", lastSegment.getOrDefault(player.getUUID(), 0));
        obj.addProperty("lastPayout", lastPayout.getOrDefault(player.getUUID(), 0L));
        return GSON.toJson(obj);
    }

    @Override
    public void removeViewer(ServerPlayer player) {
        super.removeViewer(player);
        bets.remove(player.getUUID());
        lastSegment.remove(player.getUUID());
        lastPayout.remove(player.getUUID());
    }
}
