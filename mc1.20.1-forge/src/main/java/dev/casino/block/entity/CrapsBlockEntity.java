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
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Craps table: dice-based game where players bet on the outcome of two dice.
 * Pure server-side logic with client rendering from synced state.
 */
public class CrapsBlockEntity extends BaseCasinoBlockEntity {
    private final Map<UUID, Long> bets = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastRoll = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPayout = new ConcurrentHashMap<>();

    public static final int MIN_BET = 10;
    public static final int MAX_BET = 10_000;

    public CrapsBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.CRAPS_BE_TYPE.get(), pos, state);
    }

    @Override
    public String getGameType() {
        return "CRAPS";
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CrapsBlockEntity be) {
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
                    JsonObject json = new JsonObject();
                    json.addProperty("bet", 0); // placeholder
                    long bet = 10; // simplified
                    if (bet < MIN_BET || bet > MAX_BET) return;
                    if (!CasinoAccountManager.deductBalance(uuid, bet)) return;
                    bets.put(uuid, bet);
                    // Roll two dice
                    int d1 = (int) (Math.random() * 6) + 1;
                    int d2 = (int) (Math.random() * 6) + 1;
                    int total = d1 + d2;
                    lastRoll.put(uuid, total);
                    // Simple payout: 7 or 11 wins, 2 or 12 loses, other = point
                    long payout = 0;
                    if (total == 7 || total == 11) {
                        payout = bet * 2;
                    } else if (total == 2 || total == 12) {
                        payout = 0;
                    } else {
                        payout = bet; // push for now
                    }
                    lastPayout.put(uuid, payout);
                    if (payout > 0) {
                        CasinoAccountManager.addBalance(uuid, payout);
                    }
                    syncTo(player);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public String serializeState(ServerPlayer player) {
        JsonObject obj = new JsonObject();
        obj.addProperty("bet", bets.getOrDefault(player.getUUID(), 0L));
        obj.addProperty("lastRoll", lastRoll.getOrDefault(player.getUUID(), 0));
        obj.addProperty("lastPayout", lastPayout.getOrDefault(player.getUUID(), 0L));
        return GSON.toJson(obj);
    }

    @Override
    public void removeViewer(ServerPlayer player) {
        super.removeViewer(player);
        bets.remove(player.getUUID());
        lastRoll.remove(player.getUUID());
        lastPayout.remove(player.getUUID());
    }
}
