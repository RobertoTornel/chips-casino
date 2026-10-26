package dev.casino.network;

import dev.architectury.networking.NetworkManager;
import dev.casino.Casino;
import dev.casino.block.entity.BaseCasinoBlockEntity;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Cross-loader packet layer built on Architectury's {@link NetworkManager}.
 *
 * <p>The wire format is loader-agnostic ({@link FriendlyByteBuf} over an
 * {@link ResourceLocation} channel), so this single class serves Fabric, Forge and
 * NeoForge without any per-platform networking code:</p>
 *
 * <ul>
 *   <li>{@code action} (C2S) — {@code pos, action, dataJson}</li>
 *   <li>{@code sync} (S2C) — {@code pos, gameType, stateJson, balance}</li>
 *   <li>{@code open_screen} (S2C) — {@code pos, gameType, stateJson, balance}</li>
 * </ul>
 */
public final class CasinoNetwork {
    public static final ResourceLocation ACTION_ID = Casino.id("action");
    public static final ResourceLocation SYNC_ID = Casino.id("sync");
    public static final ResourceLocation OPEN_SCREEN_ID = Casino.id("open_screen");

    private CasinoNetwork() {
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    /** Registers the serverbound (C2S) handler. Called from the common initializer. */
    public static void initCommon() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ACTION_ID, (buf, context) -> {
            BlockPos pos = buf.readBlockPos();
            String action = buf.readUtf();
            String data = buf.readUtf();
            context.queue(() -> {
                Player player = context.getPlayer();
                if (!(player instanceof ServerPlayer serverPlayer)) {
                    return;
                }
                BlockEntity be = serverPlayer.level().getBlockEntity(pos);
                if (be instanceof BaseCasinoBlockEntity casinoBe) {
                    casinoBe.handleAction(serverPlayer, action, data);
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /** Client → server: the player clicked something inside a casino screen. */
    public static void sendAction(BlockPos pos, String action, String data) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBlockPos(pos);
        buf.writeUtf(action);
        buf.writeUtf(data);
        NetworkManager.sendToServer(ACTION_ID, buf);
    }

    /** Server → client: push the authoritative table state + balance to one viewer. */
    public static void sendSync(ServerPlayer player, BlockPos pos, String gameType, String stateJson, long balance) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBlockPos(pos);
        buf.writeUtf(gameType);
        buf.writeUtf(stateJson);
        buf.writeLong(balance);
        NetworkManager.sendToPlayer(player, SYNC_ID, buf);
    }

    /** Server → client: open the matching casino screen for one player. */
    public static void sendOpenScreen(ServerPlayer player, BlockPos pos, String gameType, String stateJson, long balance) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBlockPos(pos);
        buf.writeUtf(gameType);
        buf.writeUtf(stateJson);
        buf.writeLong(balance);
        NetworkManager.sendToPlayer(player, OPEN_SCREEN_ID, buf);
    }
}
