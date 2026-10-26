package dev.emccasino.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Data-pack driven EMC table loader (1.20.1).
 *
 * Merges every JSON object found at {@code data/<namespace>/emc/*.json}
 * into {@link EmcRegistry}, re-applied on every /reload.
 */
public final class EmcDataLoader implements IdentifiableResourceReloadListener, ResourceManagerReloadListener {
    private static final Gson GSON = new Gson();
    private static final ResourceLocation ID = new ResourceLocation("emc_casino", "emc_values");

    private EmcDataLoader() {
    }

    public static void register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new EmcDataLoader());
    }

    @Override
    public ResourceLocation getFabricId() {
        return ID;
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        Map<ResourceLocation, Resource> found = manager.listResources(
                "emc",
                id -> id.getNamespace().equals("emc_casino") && id.getPath().endsWith(".json")
        );

        int applied = 0;
        for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
            Optional<Resource> res = manager.getResource(entry.getKey());
            if (res.isEmpty()) {
                continue;
            }
            try (InputStreamReader reader = new InputStreamReader(
                    res.get().open(), StandardCharsets.UTF_8)) {
                JsonElement root = GSON.fromJson(reader, JsonElement.class);
                if (root != null && root.isJsonObject()) {
                    JsonObject obj = root.getAsJsonObject();
                    for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                        try {
                            EmcRegistry.setEmc(e.getKey(), e.getValue().getAsLong());
                            applied++;
                        } catch (Exception badValue) {
                            EmcRegistry.LOGGER.warn("Invalid EMC value for {} in {}: {}",
                                    e.getKey(), entry.getKey(), e.getValue());
                        }
                    }
                }
            } catch (Exception ex) {
                EmcRegistry.LOGGER.warn("Failed to read EMC data file {}", entry.getKey(), ex);
            }
        }

        EmcRegistry.LOGGER.info("EMC datapack reload complete: {} value(s) applied from {} file(s)",
                applied, found.size());
    }
}
