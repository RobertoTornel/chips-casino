package dev.emccasino.network;

import dev.architectury.networking.NetworkManager;
import dev.emccasino.EmcCasino;
import dev.emccasino.block.entity.BaseCasinoBlockEntity;
import dev.emccasino.client.gui.BaccaratScreen;
import dev.emccasino.client.gui.BaseCasinoScreen;
import dev.emccasino.client.gui.BlackjackScreen;
import dev.emccasino.client.gui.PachinkoScreen;
import dev.emccasino.client.gui.RouletteScreen;
import dev.emccasino.client.gui.TexasHoldemScreen;
import dev.emccasino.client.gui.VideoPokerScreen;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
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
    public static final ResourceLocation ACTION_ID = EmcCasino.id("action");
    public static final ResourceLocation SYNC_ID = EmcCasino.id("sync");
    public static final ResourceLocation OPEN_SCREEN_ID = EmcCasino.id("open_screen");

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

    /**
     * Registers the clientbound (S2C) handlers. Must only be called from a client-side
     * entrypoint — it touches {@code net.minecraft.client.*} classes.
     */
    public static void initClient() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SYNC_ID, (buf, context) -> {
            buf.readBlockPos();
            buf.readUtf(); // gameType (unused: the screen for this table is already open)
            String stateJson = buf.readUtf();
            long balance = buf.readLong();
            context.queue(() -> {
                if (Minecraft.getInstance().screen instanceof BaseCasinoScreen screen) {
                    screen.updateState(stateJson, balance);
                }
            });
        });

        NetworkManager.registerReceiver(NetworkManager.Side.S2C, OPEN_SCREEN_ID, (buf, context) -> {
            BlockPos pos = buf.readBlockPos();
            String gameType = buf.readUtf();
            String stateJson = buf.readUtf();
            long balance = buf.readLong();
            context.queue(() -> {
                BaseCasinoScreen screen = createScreen(gameType, pos, stateJson, balance);
                if (screen != null) {
                    Minecraft.getInstance().setScreen(screen);
                }
            });
        });
    }

    private static BaseCasinoScreen createScreen(String gameType, BlockPos pos, String stateJson, long balance) {
        return switch (gameType.toUpperCase()) {
            case "ROULETTE" -> new RouletteScreen(pos, stateJson, balance);
            case "BLACKJACK" -> new BlackjackScreen(pos, stateJson, balance);
            case "BACCARAT" -> new BaccaratScreen(pos, stateJson, balance);
            case "VIDEO_POKER" -> new VideoPokerScreen(pos, stateJson, balance);
            case "PACHINKO" -> new PachinkoScreen(pos, stateJson, balance);
            case "TEXAS_HOLDEM" -> new TexasHoldemScreen(pos, stateJson, balance);
            default -> null;
        };
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
