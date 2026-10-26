package dev.emccasino;

import dev.emccasino.client.gui.*;
import dev.emccasino.network.CasinoSyncPayload;
import dev.emccasino.network.OpenCasinoScreenPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public class EmcCasinoClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EmcCasino.LOGGER.info("Initializing EMC Casino Client...");

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
                    case "TEXAS_HOLDEM" -> new TexasHoldemScreen(pos, state, bal);
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
