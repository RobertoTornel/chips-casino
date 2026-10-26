package dev.casino.network;

import dev.casino.Casino;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CasinoActionPayload(BlockPos pos, String action, String data) implements CustomPacketPayload {
    public static final Type<CasinoActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Casino.MOD_ID, "action"));

    public static final StreamCodec<FriendlyByteBuf, CasinoActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos);
                buf.writeUtf(payload.action);
                buf.writeUtf(payload.data);
            },
            buf -> new CasinoActionPayload(buf.readBlockPos(), buf.readUtf(), buf.readUtf())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
