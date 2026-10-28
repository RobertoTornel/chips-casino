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
 * Keno: lottery-style number picking game.
 */
public class KenoBlockEntity extends BaseCasinoBlockEntity {
    private final Map<UUID, Long> bets = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> matches = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPayout = new ConcurrentHashMap<>();

    public static final int MIN_BET = 10;
    public static final int MAX_BET = 10_000;

    public KenoBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.KENO_BE_TYPE, pos, state);
    }

    @Override
    public String getGameType() {
        return "KENO";
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, KenoBlockEntity be) {
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
                    // Pick 5 numbers from 1-80, draw 20, count matches
                    int drawn = 20;
                    int matchesCount = 0;
                    for (int i = 0; i < 5; i++) {
                        if ((int) (Math.random() * drawn) < 5) matchesCount++;
                    }
                    matches.put(uuid, matchesCount);
                    // Payout based on matches
                    long[] payouts = {0, 0, 2, 10, 50, 500};
                    long multiplier = matchesCount < payouts.length ? payouts[matchesCount] : 0;
                    long payout = bet * multiplier;
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
        obj.addProperty("matches", matches.getOrDefault(player.getUUID(), 0));
        obj.addProperty("lastPayout", lastPayout.getOrDefault(player.getUUID(), 0L));
        return GSON.toJson(obj);
    }

    @Override
    public void removeViewer(ServerPlayer player) {
        super.removeViewer(player);
        bets.remove(player.getUUID());
        matches.remove(player.getUUID());
        lastPayout.remove(player.getUUID());
    }
}
