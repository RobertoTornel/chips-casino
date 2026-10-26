package dev.casino.fabric;

import dev.casino.client.CasinoClientInit;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client entrypoint (registered as {@code client} in fabric.mod.json).
 *
 * <p>Kept as its own top-level class so the entrypoint string in {@code fabric.mod.json}
 * contains no {@code $} (which Gradle's resource template expansion would misinterpret).</p>
 */
public class CasinoFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        CasinoClientInit.initClient();
    }
}
