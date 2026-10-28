package dev.casino.block.entity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.casino.economy.Exchange;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.network.CasinoSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public abstract class BaseCasinoBlockEntity extends BlockEntity {
    protected static final Gson GSON = new Gson();
    protected final Set<ServerPlayer> viewers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Last serialized state per player, keyed by UUID, to avoid regenerating JSON on every
     *  sync beat when nothing changed. Subclasses must call {@link #invalidateState(UUID)}
     *  when the player-visible state changes. */
    protected final Map<UUID, String> stateCache = new ConcurrentHashMap<>();
    /** Generation counter bumped whenever any viewer-visible state changes. */
    protected long stateGeneration;

    public BaseCasinoBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public abstract String getGameType();
    public abstract String serializeState(ServerPlayer player);

    public void addViewer(ServerPlayer player) {
        viewers.add(player);
        syncTo(player);
    }

    public void removeViewer(ServerPlayer player) {
        viewers.remove(player);
    }

    /**
     * Call this when a player disconnects so their seat is freed and any uncommitted
     * bets are refunded. Without this, a mid-hand disconnect leaves a phantom bettor
     * blocking a seat and their chips locked on the felt until server restart.
     */
    public void cleanupPlayer(ServerPlayer player) {
        if (viewers.remove(player)) {
            removeViewer(player);
        }
        // Subclasses should override to refund uncommitted bets and free seats
        onPlayerCleanup(player);
    }

    /**
     * Called when a player disconnects from this table. Subclasses should override
     * to refund any uncommitted bets and free the player's seat.
     */
    protected void onPlayerCleanup(ServerPlayer player) {
        // Default: no-op. Override in subclasses that track bets/seats.
    }

    public void syncTo(ServerPlayer player) {
        long balance = CasinoAccountManager.getBalance(player.getUUID());
        UUID uuid = player.getUUID();
        String state = stateCache.get(uuid);
        if (state == null) {
            state = serializeState(player);
            stateCache.put(uuid, state);
        }
        ServerPlayNetworking.send(player, new CasinoSyncPayload(this.worldPosition, getGameType(), state, balance));
    }

    /** Call this when any viewer-visible state changes so the cached JSON is regenerated
     *  on the next {@link #syncTo(ServerPlayer)}. */
    protected void invalidateState(UUID uuid) {
        stateCache.remove(uuid);
        stateGeneration++;
    }

    /** Clear all cached state (e.g. on disconnect). */
    protected void invalidateAllState() {
        stateCache.clear();
        stateGeneration++;
    }

    public void broadcastSync() {
        // State may have changed since the last sync (a player bet, the wheel turned, a card
        // was dealt). Invalidate all cached JSON so every viewer gets fresh state.
        invalidateAllState();
        viewers.removeIf(p -> p.isRemoved() || p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(this.worldPosition)) > 64.0);
        for (ServerPlayer player : viewers) {
            syncTo(player);
        }
    }

    public void handleAction(ServerPlayer player, String action, String data) {
        // The subclass gets first refusal on every action. This matters for the Exchange,
        // which has its own showcase and pricing logic: an earlier version intercepted the
        // generic item actions here *before* delegating, so the Exchange screen's buttons
        // never reached the Exchange and the block appeared to do nothing.
        if (handlesAction(action)) {
            handleGameAction(player, action, data);
            return;
        }

        // Generic fallbacks, for tables that only need plain item <-> Chips conversion.
        // These route through Exchange rather than re-implementing the price rules; two
        // copies is how the sell rate drifted between a table footer and the Exchange.
        if ("DEPOSIT_MAIN_HAND".equalsIgnoreCase(action)
                || "SELL_MAIN_HAND".equalsIgnoreCase(action)) {
            ItemStack stack = player.getMainHandItem();
            Exchange.sellStack(player, stack, stack.getCount());
            syncTo(player);
            return;
        }

        if ("WITHDRAW_ITEM".equalsIgnoreCase(action)
                || "EXCHANGE_BUY".equalsIgnoreCase(action)) {
            try {
                JsonObject json = GSON.fromJson(data, JsonObject.class);
                String itemId = json.get("itemId").getAsString();
                int count = json.has("count") ? json.get("count").getAsInt() : 1;

                Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
                if (item != null) {
                    Exchange.buyFor(player, item, count);
                }
            } catch (Exception ignored) {
                // a malformed payload must not take the block entity down
            }
            syncTo(player);
            return;
        }

        handleGameAction(player, action, data);
    }

    /**
     * Whether this block entity implements {@code action} itself.
     *
     * <p>Defaults to {@code false} so the generic item fallbacks apply. Subclasses that own
     * a richer implementation (the Exchange) override this to claim their actions and stop
     * the fallbacks from short-circuiting them.
     */
    protected boolean handlesAction(String action) {
        return false;
    }

    protected abstract void handleGameAction(ServerPlayer player, String action, String data);
}
