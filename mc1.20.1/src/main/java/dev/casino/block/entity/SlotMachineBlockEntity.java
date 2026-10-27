package dev.casino.block.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.casino.Casino;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.game.SlotMachineEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slot machine block entity: server-authoritative reel logic.
 *
 * <p>A spin deducts the bet, rolls the reels, queues the payout, and syncs the final symbol
 * grid to every viewer. The client only ever sends {@code bet} and a spin request;
 * the server owns the seed and the outcome.
 *
 * <p>Security: the seed is server-generated from {@link Casino#SLOT_RANDOM}
 * combined with the player UUID, so a client cannot predict or influence the
 * outcome. The bet is validated against the server-stored pending bet, never
 * trusted from the client. Player session state is cleaned up on disconnect via
 * {@link #removeViewer(ServerPlayer)} to prevent memory leaks and stuck games.
 *
 * <p>Payouts flow through {@link #drainPayouts()} exactly once per tick, so a duplicate
 * SPIN packet cannot pay twice — the same exactly-once guarantee the table games use.
 */
public class SlotMachineBlockEntity extends BaseCasinoBlockEntity {
    private final SlotMachineEngine engine = new SlotMachineEngine();
    private final Map<UUID, SlotMachineEngine.Result> lastSpins = new ConcurrentHashMap<>();

    // Client-selected bet, persisted across spins so the player doesn't re-pick each time.
    // Validated on spin: the server-stored value is used, never the client's.
    private final Map<UUID, Long> bets = new ConcurrentHashMap<>();

    // Payouts the house owes, drained exactly once per tick in serverTick.
    private final Map<UUID, Long> pendingPayouts = new LinkedHashMap<>();

    public static final long MIN_BET = 10;
    public static final long MAX_BET = 10_000;

    public SlotMachineBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.SLOT_MACHINE_BE_TYPE, pos, state);
    }

    @Override
    public String getGameType() {
        return "SLOT";
    }

    @Override
    public void removeViewer(ServerPlayer player) {
        super.removeViewer(player);
        // Clean up player session state to prevent memory leaks and stuck games.
        lastSpins.remove(player.getUUID());
        bets.remove(player.getUUID());
        pendingPayouts.remove(player.getUUID());
    }

    @Override
    public String serializeState(ServerPlayer player) {
        JsonObject obj = new JsonObject();
        obj.addProperty("bet", bets.getOrDefault(player.getUUID(), MIN_BET));

        SlotMachineEngine.Result res = lastSpins.get(player.getUUID());
        if (res != null) {
            obj.addProperty("payout", res.payout);
            obj.addProperty("won", res.won);
            obj.addProperty("bet", res.bet);

            JsonArray visible = new JsonArray();
            for (int[] row : res.visible) {
                JsonArray r = new JsonArray();
                for (int sym : row) r.add(sym);
                visible.add(r);
            }
            obj.add("visible", visible);

            JsonArray stops = new JsonArray();
            for (int s : res.stop) stops.add(s);
            obj.add("stops", stops);
        } else {
            obj.addProperty("payout", 0);
            obj.addProperty("won", false);
            obj.addProperty("bet", bets.getOrDefault(player.getUUID(), MIN_BET));
            JsonArray empty = new JsonArray();
            for (int r = 0; r < 3; r++) {
                JsonArray row = new JsonArray();
                row.add(-1); row.add(-1); row.add(-1);
                empty.add(row);
            }
            obj.add("visible", empty);
            obj.add("stops", new JsonArray());
        }
        return GSON.toJson(obj);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SlotMachineBlockEntity be) {
        if (level.isClientSide()) {
            return;
        }
        // Clean up disconnected players from viewers
        for (ServerPlayer p : new ArrayList<>(be.viewers)) {
            if (p.isRemoved()) {
                be.cleanupPlayer(p);
            }
        }

        // Drain the payout queue exactly once per tick and credit real accounts.
        Map<UUID, Long> owed = be.pendingPayouts;
        if (!owed.isEmpty()) {
            Map<UUID, Long> copy = new LinkedHashMap<>(owed);
            owed.clear();
            for (Map.Entry<UUID, Long> e : copy.entrySet()) {
                CasinoAccountManager.addBalance(e.getKey(), e.getValue());
            }
        }
    }

    @Override
    protected void handleGameAction(ServerPlayer player, String action, String data) {
        switch (action.toUpperCase()) {
            case "SET_BET" -> {
                try {
                    JsonObject json = GSON.fromJson(data, JsonObject.class);
                    long bet = json.get("bet").getAsLong();
                    // Validate: bet must be within MIN_BET..MAX_BET.
                    if (bet < MIN_BET || bet > MAX_BET) {
                        return;
                    }
                    bets.put(player.getUUID(), bet);
                    syncTo(player);
                } catch (Exception e) {
                    Casino.LOGGER.warn("Invalid SET_BET payload from {}: {}",
                            player.getName().getString(), e.getMessage());
                }
            }
            case "SPIN" -> {
                try {
                    // Use the server-stored bet, never the client-supplied one.
                    long bet = bets.getOrDefault(player.getUUID(), MIN_BET);
                    if (bet <= 0) {
                        break;
                    }
                    if (!CasinoAccountManager.deductBalance(player.getUUID(), bet)) {
                        break; // insufficient funds
                    }
                    // Server owns the seed: deterministic, client can't influence it.
                    long seed = Casino.SLOT_RANDOM.nextLong()
                            ^ player.getUUID().getMostSignificantBits()
                            ^ player.getUUID().getLeastSignificantBits();
                    SlotMachineEngine.Result res = engine.spin(bet, seed);
                    lastSpins.put(player.getUUID(), res);
                    bets.put(player.getUUID(), bet);
                    // Queue the payout for exactly-once draining via serverTick, never
                    // credit directly from the action handler — a duplicate SPIN packet
                    // must not pay twice.
                    if (res.payout > 0) {
                        pendingPayouts.merge(player.getUUID(), res.payout, Long::sum);
                    }
                    syncTo(player);
                } catch (Exception e) {
                    Casino.LOGGER.warn("Spin failed for {}: {}",
                            player.getName().getString(), e.getMessage());
                }
            }
        }
    }

    /** Pulls every payout the house still owes and clears the queue (test hook). */
    Map<UUID, Long> drainPayouts() {
        if (pendingPayouts.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> copy = new LinkedHashMap<>(pendingPayouts);
        pendingPayouts.clear();
        return copy;
    }

    public long getMinBet() {
        return MIN_BET;
    }

    public long getMaxBet() {
        return MAX_BET;
    }
}
