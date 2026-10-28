package dev.macasino.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Data-pack driven Chips table loader (Forge / NeoForge 1.20.1).
 *
 * Merges every JSON object found at {@code data/<namespace>/prices/*.json}
 * into {@link PriceTable}, re-applied on every /reload.
 *
 * Registered through {@code AddReloadListenerEvent} on the Forge event bus.
 */
public final class PriceDataLoader implements ResourceManagerReloadListener {
    private static final Gson GSON = new Gson();
    private static PriceDataLoader INSTANCE;

    public static PriceDataLoader instance() {
        if (INSTANCE == null) {
            INSTANCE = new PriceDataLoader();
        }
        return INSTANCE;
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        Map<ResourceLocation, Resource> found = manager.listResources(
                "prices",
                id -> id.getPath().endsWith(".json")
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
