package dev.macasino;

import dev.macasino.client.gui.*;
import dev.macasino.network.CasinoSyncPayload;
import dev.macasino.network.OpenCasinoScreenPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public class CasinoClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Casino.LOGGER.info("Initializing Chips Casino Client...");

        // Receive Open Casino Screen from Server
        ClientPlayNetworking.registerGlobalReceiver(OpenCasinoScreenPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                BlockPos pos = payload.pos();
                String game = payload.gameType();
                String state = payload.initialStateJson();
                long bal = payload.playerBalance();

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
                    Minecraft.getInstance().setScreenAndShow(screen);
                }
            });
        });

        // Receive Casino State Sync from Server
        ClientPlayNetworking.registerGlobalReceiver(CasinoSyncPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                if (Minecraft.getInstance().gui.screen() instanceof BaseCasinoScreen screen) {
                    screen.updateState(payload.stateJson(), payload.playerBalance());
                }
            });
        });
    }
}
