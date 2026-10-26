package dev.casino.network;

import dev.casino.Casino;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record OpenCasinoScreenPayload(BlockPos pos, String gameType, String initialStateJson, long playerBalance) implements CustomPacketPayload {
    public static final Type<OpenCasinoScreenPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Casino.MOD_ID, "open_screen"));

    public static final StreamCodec<FriendlyByteBuf, OpenCasinoScreenPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos);
                buf.writeUtf(payload.gameType);
                buf.writeUtf(payload.initialStateJson);
                buf.writeLong(payload.playerBalance);
            },
            buf -> new OpenCasinoScreenPayload(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readLong())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
