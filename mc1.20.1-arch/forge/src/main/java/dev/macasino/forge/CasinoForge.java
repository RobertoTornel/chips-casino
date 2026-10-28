package dev.macasino.forge;

import dev.macasino.Casino;
import dev.macasino.api.PriceDataLoader;
import dev.macasino.client.CasinoClientInit;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge (and, via the shared Forge 47 API, NeoForge 1.20.1) platform glue.
 *
 * <p>Registration, networking, commands and lifecycle are all handled by
 * {@link Casino#init()} in :common. This class only adds the two Forge-specific
 * hooks: the data-pack reload listener and the client-side setup pass.</p>
 */
@Mod(Casino.MOD_ID)
public class CasinoForge {

    public CasinoForge() {
        // Register everything: blocks, block entities, block items, packets, commands, world hooks.
        Casino.init();

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onClientSetup);

        // /reload support: Forge exposes its own reload-listener event.
        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListener);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> CasinoClientInit::initClient));
    }

    private void onAddReloadListener(final AddReloadListenerEvent event) {
        // ResourceManagerReloadListener has a default PreparableReloadListener#reload,
        // so a tiny adapter is all that is needed.
        event.addListener((ResourceManagerReloadListener) PriceDataLoader::reload);
    }
}
