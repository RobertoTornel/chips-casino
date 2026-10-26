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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class BaseCasinoBlockEntity extends BlockEntity {
    protected static final Gson GSON = new Gson();
    protected final Set<ServerPlayer> viewers = Collections.newSetFromMap(new ConcurrentHashMap<>());

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

    public void syncTo(ServerPlayer player) {
        long balance = CasinoAccountManager.getBalance(player.getUUID());
        String state = serializeState(player);
        ServerPlayNetworking.send(player, new CasinoSyncPayload(this.worldPosition, getGameType(), state, balance));
    }

    public void broadcastSync() {
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
