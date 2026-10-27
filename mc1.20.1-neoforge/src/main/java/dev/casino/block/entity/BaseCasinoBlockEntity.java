package dev.casino.block.entity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.network.CasinoNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

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

    /**
     * Call this when a player disconnects so their seat is freed and any uncommitted
     * bets are refunded. Without this, a mid-hand disconnect leaves a phantom bettor
     * blocking a seat and their chips locked on the felt until server restart.
     */
    public void cleanupPlayer(ServerPlayer player) {
        if (viewers.remove(player)) {
            removeViewer(player);
        }
    }


    public void syncTo(ServerPlayer player) {
        long balance = CasinoAccountManager.getBalance(player.getUUID());
        String state = serializeState(player);
        CasinoNetwork.sendToPlayer(player, new CasinoNetwork.SyncPacket(this.worldPosition, getGameType(), state, balance));
    }

    public void broadcastSync() {
        viewers.removeIf(p -> p.isRemoved() || p.distanceToSqr(Vec3.atCenterOf(this.worldPosition)) > 64.0);
        for (ServerPlayer player : viewers) {
            syncTo(player);
        }
    }

    public void handleAction(ServerPlayer player, String action, String data) {
        // The subclass gets first refusal on every action. This matters for the Exchange,
        // which has its own showcase logic: intercepting the generic item actions here
        // before delegating made the Exchange screen's buttons do nothing.
        if (handlesAction(action)) {
            handleGameAction(player, action, data);
            return;
        }

        // Item <-> Chips conversion belongs to Exchange, so these legacy aliases delegate
        // there instead of re-implementing the price rules. Two copies is how the sell rate
        // drifted between a table's footer and the Exchange screen.
        if ("DEPOSIT_MAIN_HAND".equalsIgnoreCase(action)
                || "SELL_MAIN_HAND".equalsIgnoreCase(action)) {
            ItemStack stack = player.getMainHandItem();
            dev.casino.economy.Exchange.sellStack(player, stack, stack.getCount());
            syncTo(player);
            return;
        }

        if ("WITHDRAW_ITEM".equalsIgnoreCase(action)
                || "EXCHANGE_BUY".equalsIgnoreCase(action)) {
            try {
                JsonObject json = GSON.fromJson(data, JsonObject.class);
                String itemId = json.get("itemId").getAsString();
                int count = json.has("count") ? json.get("count").getAsInt() : 1;

                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
                if (item != null) {
                    dev.casino.economy.Exchange.buyFor(player, item, count);
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
     * <p>Defaults to false so the generic item fallbacks apply. Subclasses owning a richer
     * implementation (the Exchange) override this to stop the fallbacks short-circuiting.
     */
    protected boolean handlesAction(String action) {
        return false;
    }

    protected abstract void handleGameAction(ServerPlayer player, String action, String data);
}
