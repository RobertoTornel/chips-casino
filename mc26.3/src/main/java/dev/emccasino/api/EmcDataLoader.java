package dev.emccasino.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Data-pack driven EMC table loader.
 *
 * Listens on the SERVER_DATA pack type and merges every JSON object found at
 * {@code data/<namespace>/emc/*.json} into {@link EmcRegistry}.
 *
 * A data pack (or KubeJS server script writing a data pack) can therefore ship
 * values like:
 * <pre>
 * { "minecraft:diamond": 8192, "minecraft:emerald": 16384 }
 * </pre>
 *
 * Entries are merged over the compiled-in defaults, so a pack only needs to
 * list the items it wants to change. Because the reload listener runs on every
 * server data reload (/reload), edits apply without restarting.
 */
public final class EmcDataLoader implements IdentifiableResourceReloadListener, net.minecraft.server.packs.resources.ResourceManagerReloadListener {
    private static final Gson GSON = new Gson();
    private static final Identifier ID = Identifier.fromNamespaceAndPath("emc_casino", "emc_values");

    private EmcDataLoader() {
    }

    public static void register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new EmcDataLoader());
    }

    @Override
    public Identifier getFabricId() {
        return ID;
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        // listResources(path, selector) where path = "emc"
        Map<Identifier, Resource> found = manager.listResources(
                "emc",
                id -> id.getNamespace().equals("emc_casino") && id.getPath().endsWith(".json")
        );

        int applied = 0;
        for (Map.Entry<Identifier, Resource> entry : found.entrySet()) {
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
