package dev.emccasino.block.entity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.emccasino.api.EmcRegistry;
import dev.emccasino.economy.CasinoAccountManager;
import dev.emccasino.network.CasinoSyncPayload;
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
        if ("DEPOSIT_MAIN_HAND".equalsIgnoreCase(action)) {
            ItemStack stack = player.getMainHandItem();
            if (!stack.isEmpty()) {
                long depositValue = EmcRegistry.getDepositValue(stack);
                if (depositValue > 0) {
                    CasinoAccountManager.addBalance(player.getUUID(), depositValue);
                    stack.setCount(0);
                    syncTo(player);
                }
            }
            return;
        }

        if ("WITHDRAW_ITEM".equalsIgnoreCase(action)) {
            try {
                JsonObject json = GSON.fromJson(data, JsonObject.class);
                String itemId = json.get("itemId").getAsString();
                int count = json.has("count") ? json.get("count").getAsInt() : 1;

                Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
                if (item != null) {
                    ItemStack out = new ItemStack(item, count);
                    long cost = EmcRegistry.getWithdrawCost(out);
                    if (cost > 0 && CasinoAccountManager.deductBalance(player.getUUID(), cost)) {
                        if (!player.getInventory().add(out)) {
                            player.spawnAtLocation((net.minecraft.server.level.ServerLevel) player.level(), out);
                        }
                        syncTo(player);
                    }
                }
            } catch (Exception ignored) {}
            return;
        }

        handleGameAction(player, action, data);
    }

    protected abstract void handleGameAction(ServerPlayer player, String action, String data);
}
