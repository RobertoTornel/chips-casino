package dev.casino.network;

import dev.casino.Casino;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public class CasinoNetwork {
    public static final ResourceLocation ACTION_ID = new ResourceLocation(Casino.MOD_ID, "action");
    public static final ResourceLocation SYNC_ID = new ResourceLocation(Casino.MOD_ID, "sync");
    public static final ResourceLocation OPEN_SCREEN_ID = new ResourceLocation(Casino.MOD_ID, "open_screen");

    public static void sendAction(BlockPos pos, String action, String data) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeUtf(action);
        buf.writeUtf(data);
        ClientPlayNetworking.send(ACTION_ID, buf);
    }

    public static void sendSync(ServerPlayer player, BlockPos pos, String gameType, String stateJson, long balance) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeUtf(gameType);
        buf.writeUtf(stateJson);
        buf.writeLong(balance);
        ServerPlayNetworking.send(player, SYNC_ID, buf);
    }

    public static void sendOpenScreen(ServerPlayer player, BlockPos pos, String gameType, String initialStateJson, long balance) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeUtf(gameType);
        buf.writeUtf(initialStateJson);
        buf.writeLong(balance);
        ServerPlayNetworking.send(player, OPEN_SCREEN_ID, buf);
    }
}
