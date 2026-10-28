package dev.macasino;

import dev.macasino.client.gui.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public class CasinoClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Casino.LOGGER.info("Initializing Chips Casino 1.20.1 Client...");

        // Open Screen packet receiver
        ClientPlayNetworking.registerGlobalReceiver(Casino.OPEN_SCREEN_ID, (client, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String game = buf.readUtf();
            String state = buf.readUtf();
            long bal = buf.readLong();

            client.execute(() -> {
                BaseCasinoScreen screen = switch (game.toUpperCase()) {
                    case "ROULETTE" -> new RouletteScreen(pos, state, bal);
                    case "BLACKJACK" -> new BlackjackScreen(pos, state, bal);
                    case "BACCARAT" -> new BaccaratScreen(pos, state, bal);
                    case "VIDEO_POKER" -> new VideoPokerScreen(pos, state, bal);
                    case "PACHINKO" -> new PachinkoScreen(pos, state, bal);
                    case "PINBALL" -> new PinballScreen(pos, state, bal);
                    case "TEXAS_HOLDEM" -> new TexasHoldemScreen(pos, state, bal);
                    case "EXCHANGE" -> new ExchangeScreen(pos, state, bal);
                    case "SLOT" -> new SlotMachineScreen(pos, state, bal);
                    default -> null;
                };

                if (screen != null) {
                    Minecraft.getInstance().setScreen(screen);
                }
            });
        });

        // State Sync packet receiver
        ClientPlayNetworking.registerGlobalReceiver(Casino.SYNC_ID, (client, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String game = buf.readUtf();
            String state = buf.readUtf();
            long bal = buf.readLong();

            client.execute(() -> {
                if (Minecraft.getInstance().screen instanceof BaseCasinoScreen screen) {
                    screen.updateState(state, bal);
                }
            });
        });
    }
}
