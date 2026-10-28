package dev.macasino.client;

import dev.macasino.client.gui.BaseCasinoScreen;
import dev.macasino.client.gui.ExchangeScreen;
import dev.macasino.client.gui.SlotMachineScreen;
import dev.macasino.client.gui.BaccaratScreen;
import dev.macasino.client.gui.BlackjackScreen;
import dev.macasino.client.gui.PachinkoScreen;
import dev.macasino.client.gui.PinballScreen;
import dev.macasino.client.gui.RouletteScreen;
import dev.macasino.client.gui.TexasHoldemScreen;
import dev.macasino.client.gui.VideoPokerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Client-only packet handling.
 *
 * This class must ONLY ever be loaded on a physical client. The network packet
 * classes reference it exclusively through {@code DistExecutor}, which keeps
 * {@code net.minecraft.client.*} out of their constant pools so a dedicated
 * server never has to resolve client classes.
 */
public final class ClientPacketHandler {

    private ClientPacketHandler() {
    }

    public static void openScreen(BlockPos pos, String gameType, String state, long balance) {
        BaseCasinoScreen screen = switch (gameType.toUpperCase()) {
            case "ROULETTE" -> new RouletteScreen(pos, state, balance);
            case "BLACKJACK" -> new BlackjackScreen(pos, state, balance);
            case "BACCARAT" -> new BaccaratScreen(pos, state, balance);
            case "VIDEO_POKER" -> new VideoPokerScreen(pos, state, balance);
            case "PACHINKO" -> new PachinkoScreen(pos, state, balance);
            case "PINBALL" -> new PinballScreen(pos, state, balance);
            case "TEXAS_HOLDEM" -> new TexasHoldemScreen(pos, state, balance);
            case "EXCHANGE" -> new ExchangeScreen(pos, state, balance);
            case "SLOT" -> new SlotMachineScreen(pos, state, balance);
            default -> null;
        };
        if (screen != null) {
            Minecraft.getInstance().setScreen(screen);
        }
    }

    public static void updateState(String state, long balance) {
        if (Minecraft.getInstance().screen instanceof BaseCasinoScreen screen) {
            screen.updateState(state, balance);
        }
    }
}
