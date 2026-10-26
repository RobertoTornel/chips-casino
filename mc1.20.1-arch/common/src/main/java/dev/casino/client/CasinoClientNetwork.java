package dev.casino.client;

import dev.architectury.networking.NetworkManager;
import dev.casino.client.gui.BaccaratScreen;
import dev.casino.client.gui.BaseCasinoScreen;
import dev.casino.client.gui.ExchangeScreen;
import dev.casino.client.gui.BlackjackScreen;
import dev.casino.client.gui.PachinkoScreen;
import dev.casino.client.gui.RouletteScreen;
import dev.casino.client.gui.TexasHoldemScreen;
import dev.casino.client.gui.VideoPokerScreen;
import dev.casino.network.CasinoNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Client-only packet handling for the Architectury build.
 *
 * <p>Kept out of {@link CasinoNetwork} on purpose: that class is loaded on a dedicated
 * server (it registers the serverbound handler and sends S2C packets), so it must not
 * carry {@code net.minecraft.client.*} references in its constant pool. Everything that
 * touches {@code Minecraft} or the screen classes lives here and is only ever loaded
 * from the client entrypoint.</p>
 */
public final class CasinoClientNetwork {

    private CasinoClientNetwork() {
    }

    /** Registers the clientbound (S2C) handlers. Call only from a client entrypoint. */
    public static void initClient() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, CasinoNetwork.SYNC_ID, (buf, context) -> {
            buf.readBlockPos();          // pos — the matching screen is already open
            buf.readUtf();               // gameType — unused here
            String stateJson = buf.readUtf();
            long balance = buf.readLong();
            context.queue(() -> {
                if (Minecraft.getInstance().screen instanceof BaseCasinoScreen screen) {
                    screen.updateState(stateJson, balance);
                }
            });
        });

        NetworkManager.registerReceiver(NetworkManager.Side.S2C, CasinoNetwork.OPEN_SCREEN_ID, (buf, context) -> {
            BlockPos pos = buf.readBlockPos();
            String gameType = buf.readUtf();
            String stateJson = buf.readUtf();
            long balance = buf.readLong();
            context.queue(() -> openScreen(gameType, pos, stateJson, balance));
        });
    }

    private static void openScreen(String gameType, BlockPos pos, String stateJson, long balance) {
        BaseCasinoScreen screen = switch (gameType.toUpperCase()) {
            case "ROULETTE" -> new RouletteScreen(pos, stateJson, balance);
            case "BLACKJACK" -> new BlackjackScreen(pos, stateJson, balance);
            case "BACCARAT" -> new BaccaratScreen(pos, stateJson, balance);
            case "VIDEO_POKER" -> new VideoPokerScreen(pos, stateJson, balance);
            case "PACHINKO" -> new PachinkoScreen(pos, stateJson, balance);
            case "TEXAS_HOLDEM" -> new TexasHoldemScreen(pos, stateJson, balance);
            case "EXCHANGE" -> new ExchangeScreen(pos, stateJson, balance);
            default -> null;
        };
        if (screen != null) {
            Minecraft.getInstance().setScreen(screen);
        }
    }
}
