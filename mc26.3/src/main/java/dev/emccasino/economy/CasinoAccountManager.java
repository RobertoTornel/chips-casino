package dev.emccasino.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player EMC casino balances.
 * Saved to world save directory as emc_casino_accounts.json.
 */
public class CasinoAccountManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CasinoAccountManager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, Long> ACCOUNTS = new ConcurrentHashMap<>();
    private static Path dataFilePath = null;

    public static void init(MinecraftServer server) {
        try {
            Path worldDir = server.getWorldPath(LevelResource.ROOT);
            dataFilePath = worldDir.resolve("emc_casino_accounts.json");
            load();
        } catch (Exception e) {
            LOGGER.error("Failed to initialize CasinoAccountManager", e);
        }
    }

    public static long getBalance(UUID uuid) {
        return ACCOUNTS.getOrDefault(uuid, 0L);
    }

    public static void setBalance(UUID uuid, long amount) {
        ACCOUNTS.put(uuid, Math.max(0L, amount));
        save();
    }

    public static void addBalance(UUID uuid, long amount) {
        if (amount <= 0) return;
        ACCOUNTS.merge(uuid, amount, (oldVal, add) -> Math.max(0L, oldVal + add));
        save();
    }

    public static boolean deductBalance(UUID uuid, long amount) {
        if (amount <= 0) return true;
        long current = getBalance(uuid);
        if (current < amount) {
            return false;
        }
        ACCOUNTS.put(uuid, current - amount);
        save();
        return true;
    }

    public static void load() {
        if (dataFilePath == null || !Files.exists(dataFilePath)) return;
        try (Reader reader = Files.newBufferedReader(dataFilePath)) {
            Type type = new TypeToken<Map<String, Long>>() {}.getType();
            Map<String, Long> raw = GSON.fromJson(reader, type);
            if (raw != null) {
                ACCOUNTS.clear();
                for (Map.Entry<String, Long> entry : raw.entrySet()) {
                    try {
                        ACCOUNTS.put(UUID.fromString(entry.getKey()), entry.getValue());
                    } catch (Exception ignored) {}
                }
                LOGGER.info("Loaded {} casino accounts.", ACCOUNTS.size());
            }
        } catch (Exception e) {
            LOGGER.error("Could not load casino account data", e);
        }
    }

    public static void save() {
        if (dataFilePath == null) return;
        try {
            Files.createDirectories(dataFilePath.getParent());
            Map<String, Long> raw = new ConcurrentHashMap<>();
            for (Map.Entry<UUID, Long> entry : ACCOUNTS.entrySet()) {
                raw.put(entry.getKey().toString(), entry.getValue());
            }
            try (Writer writer = Files.newBufferedWriter(dataFilePath)) {
                GSON.toJson(raw, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Could not save casino account data", e);
        }
    }
}
