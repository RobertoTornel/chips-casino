package dev.emccasino.fabric;

import dev.emccasino.EmcCasino;
import dev.emccasino.api.EmcDataLoader;
import dev.emccasino.client.CasinoClientInit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Fabric platform glue. Everything the mod actually does lives in :common — this class
 * only hands over the loader entrypoints plus the two Fabric-specific hooks
 * (data-pack reload listener and the built-in dark-mode resource pack).
 */
public class EmcCasinoFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        EmcCasino.init();

        // /reload support: Fabric has its own reload-listener registry.
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new EmcReloadListener());

        // Optional built-in "EMC Casino Dark Mode" resource pack (assets live in :common).
        FabricLoader.getInstance().getModContainer(EmcCasino.MOD_ID).ifPresent(container ->
                ResourceManagerHelper.registerBuiltinResourcePack(
                        new ResourceLocation(EmcCasino.MOD_ID, "dark_mode"),
                        container,
                        Component.literal("EMC Casino Dark Mode"),
                        ResourcePackActivationType.NORMAL));
    }

    /** Bridges the shared {@link EmcDataLoader} to Fabric's reload-listener registry. */
    private static final class EmcReloadListener implements IdentifiableResourceReloadListener {
        private static final ResourceLocation ID = new ResourceLocation(EmcCasino.MOD_ID, "emc_values");

        @Override
        public ResourceLocation getFabricId() {
            return ID;
        }

        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            EmcDataLoader.reload(manager);
        }
    }

    /** Fabric client entrypoint (registered as {@code client} in fabric.mod.json). */
    public static class Client implements ClientModInitializer {
        @Override
        public void onInitializeClient() {
            CasinoClientInit.initClient();
        }
    }
}
