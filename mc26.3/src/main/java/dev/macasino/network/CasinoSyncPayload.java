package dev.macasino.network;

import dev.macasino.Casino;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CasinoSyncPayload(BlockPos pos, String gameType, String stateJson, long playerBalance) implements CustomPacketPayload {
    public static final Type<CasinoSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Casino.MOD_ID, "sync"));

    public static final StreamCodec<FriendlyByteBuf, CasinoSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos);
                buf.writeUtf(payload.gameType);
                buf.writeUtf(payload.stateJson);
                buf.writeLong(payload.playerBalance);
            },
            buf -> new CasinoSyncPayload(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readLong())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
