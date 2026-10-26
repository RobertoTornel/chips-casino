package dev.casino.fabric;

import dev.casino.Casino;
import dev.casino.api.PriceDataLoader;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
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
public class CasinoFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Casino.init();

        // /reload support: Fabric has its own reload-listener registry.
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new PriceReloadListener());

        // Optional built-in "Casino Dark Mode" resource pack (assets live in :common).
        FabricLoader.getInstance().getModContainer(Casino.MOD_ID).ifPresent(container ->
                ResourceManagerHelper.registerBuiltinResourcePack(
                        new ResourceLocation(Casino.MOD_ID, "dark_mode"),
                        container,
                        Component.literal("Casino Dark Mode"),
                        ResourcePackActivationType.NORMAL));
    }

    /** Bridges the shared {@link PriceDataLoader} to Fabric's reload-listener registry. */
    private static final class PriceReloadListener implements SimpleSynchronousResourceReloadListener {
        private static final ResourceLocation ID = new ResourceLocation(Casino.MOD_ID, "price_values");

        @Override
        public ResourceLocation getFabricId() {
            return ID;
        }

        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            PriceDataLoader.reload(manager);
        }
    }
}
