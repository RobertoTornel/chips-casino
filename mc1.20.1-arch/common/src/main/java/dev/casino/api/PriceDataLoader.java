package dev.casino.api;

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
 * Data-pack driven Chips table loader (platform-neutral).
 *
 * <p>Merges every JSON object found at {@code data/casino/prices/*.json} into
 * {@link PriceTable}. Called once on server start, and again on every {@code /reload}
 * because each platform module registers {@link #reload(ResourceManager)} as its own
 * reload listener (Fabric: {@code ResourceManagerHelper}, Forge/NeoForge:
 * {@code AddReloadListenerEvent}).</p>
 */
public final class PriceDataLoader {
    private static final Gson GSON = new Gson();

    private PriceDataLoader() {
    }

    /** Re-applies every {@code data/casino/prices/*.json} file onto the Chips table. */
    public static void reload(ResourceManager manager) {
        if (manager == null) {
            return;
        }

        Map<ResourceLocation, Resource> found;
        try {
            found = manager.listResources(
                    "prices",
                    id -> id.getNamespace().equals("casino") && id.getPath().endsWith(".json"));
        } catch (Exception ex) {
            PriceTable.LOGGER.warn("Failed to enumerate Chips data files", ex);
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
                            PriceTable.setPrice(e.getKey(), e.getValue().getAsLong());
                            applied++;
                        } catch (Exception badValue) {
                            PriceTable.LOGGER.warn("Invalid Chips value for {} in {}: {}",
                                    e.getKey(), entry.getKey(), e.getValue());
                        }
                    }
                }
            } catch (Exception ex) {
                PriceTable.LOGGER.warn("Failed to read Chips data file {}", entry.getKey(), ex);
            }
        }

        PriceTable.LOGGER.info("price datapack reload complete: {} value(s) applied from {} file(s)",
                applied, found.size());
    }
}
