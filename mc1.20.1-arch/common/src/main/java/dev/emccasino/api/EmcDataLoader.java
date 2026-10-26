package dev.emccasino.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Data-pack driven EMC table loader (platform-neutral).
 *
 * <p>Merges every JSON object found at {@code data/emc_casino/emc/*.json} into
 * {@link EmcRegistry}. Called once on server start, and again on every {@code /reload}
 * because each platform module registers {@link #reload(ResourceManager)} as its own
 * reload listener (Fabric: {@code ResourceManagerHelper}, Forge/NeoForge:
 * {@code AddReloadListenerEvent}).</p>
 */
public final class EmcDataLoader {
    private static final Gson GSON = new Gson();

    private EmcDataLoader() {
    }

    /** Re-applies every {@code data/emc_casino/emc/*.json} file onto the EMC table. */
    public static void reload(ResourceManager manager) {
        if (manager == null) {
            return;
        }

        Map<ResourceLocation, Resource> found;
        try {
            found = manager.listResources(
                    "emc",
                    id -> id.getNamespace().equals("emc_casino") && id.getPath().endsWith(".json"));
        } catch (Exception ex) {
            EmcRegistry.LOGGER.warn("Failed to enumerate EMC data files", ex);
            return;
        }

        int applied = 0;
        for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
            try (InputStreamReader reader = new InputStreamReader(
                    entry.getValue().open(), StandardCharsets.UTF_8)) {
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
