package dev.macasino.client;

import dev.macasino.Casino;

/**
 * Client-side wiring shared by all loaders.
 *
 * <p>Only invoked from a client entrypoint (Fabric {@code client} entrypoint, Forge
 * {@code FMLClientSetupEvent} guarded by {@code Dist.CLIENT}), so the
 * {@code net.minecraft.client.*} references in {@link CasinoClientNetwork} are never
 * touched on a dedicated server.</p>
 */
public final class CasinoClientInit {
    private CasinoClientInit() {
    }

    public static void initClient() {
        Casino.LOGGER.info("Initializing {} client (screens + S2C packets)", Casino.MOD_NAME);
        CasinoClientNetwork.initClient();
    }
}
