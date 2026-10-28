package dev.casino.network;

import dev.casino.Casino;
import dev.casino.block.entity.BaseCasinoBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

public class CasinoNetwork {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Casino.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void init() {
        CHANNEL.registerMessage(packetId++, ActionPacket.class, ActionPacket::encode, ActionPacket::decode, ActionPacket::handle);
        CHANNEL.registerMessage(packetId++, OpenScreenPacket.class, OpenScreenPacket::encode, OpenScreenPacket::decode, OpenScreenPacket::handle);
        CHANNEL.registerMessage(packetId++, SyncPacket.class, SyncPacket::encode, SyncPacket::decode, SyncPacket::handle);
    }

    public static void sendToServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }

    public static void sendToPlayer(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    public static void sendSync(ServerPlayer player, BlockPos pos, String gameType, String stateJson, long balance) {
        sendToPlayer(player, new SyncPacket(pos, gameType, stateJson, balance));
    }

    public static class ActionPacket {
        public final BlockPos pos;
        public final String action;
        public final String data;

        public ActionPacket(BlockPos pos, String action, String data) {
            this.pos = pos;
            this.action = action;
            this.data = data;
        }

        public static void encode(ActionPacket pkt, FriendlyByteBuf buf) {
            buf.writeBlockPos(pkt.pos);
            buf.writeUtf(pkt.action);
            buf.writeUtf(pkt.data);
        }

        public static ActionPacket decode(FriendlyByteBuf buf) {
            return new ActionPacket(buf.readBlockPos(), buf.readUtf(), buf.readUtf());
        }

        public static void handle(ActionPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player != null) {
                    BlockEntity be = player.level().getBlockEntity(pkt.pos);
                    if (be instanceof BaseCasinoBlockEntity casinoBe) {
                        casinoBe.handleAction(player, pkt.action, pkt.data);
                    }
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    public static class OpenScreenPacket {
        public final BlockPos pos;
        public final String gameType;
        public final String initialState;
        public final long playerBalance;

        public OpenScreenPacket(BlockPos pos, String gameType, String initialState, long playerBalance) {
            this.pos = pos;
            this.gameType = gameType;
            this.initialState = initialState;
            this.playerBalance = playerBalance;
        }

        public static void encode(OpenScreenPacket pkt, FriendlyByteBuf buf) {
            buf.writeBlockPos(pkt.pos);
            buf.writeUtf(pkt.gameType);
            buf.writeUtf(pkt.initialState);
            buf.writeLong(pkt.playerBalance);
        }

        public static OpenScreenPacket decode(FriendlyByteBuf buf) {
            return new OpenScreenPacket(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readLong());
        }

        public static void handle(OpenScreenPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> dev.casino.client.ClientPacketHandler.openScreen(
                            pkt.pos, pkt.gameType, pkt.initialState, pkt.playerBalance)));
            ctx.setPacketHandled(true);
        }
    }

    public static class SyncPacket {
        public final BlockPos pos;
        public final String gameType;
        public final String stateJson;
        public final long playerBalance;

        public SyncPacket(BlockPos pos, String gameType, String stateJson, long playerBalance) {
            this.pos = pos;
            this.gameType = gameType;
            this.stateJson = stateJson;
            this.playerBalance = playerBalance;
        }

        public static void encode(SyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeBlockPos(pkt.pos);
            buf.writeUtf(pkt.gameType);
            buf.writeUtf(pkt.stateJson);
            buf.writeLong(pkt.playerBalance);
        }

        public static SyncPacket decode(FriendlyByteBuf buf) {
            return new SyncPacket(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readLong());
        }

        public static void handle(SyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> dev.casino.client.ClientPacketHandler.updateState(
                            pkt.stateJson, pkt.playerBalance)));
            ctx.setPacketHandled(true);
        }
    }
}
