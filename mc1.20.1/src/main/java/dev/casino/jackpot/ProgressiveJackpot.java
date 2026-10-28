package dev.casino.jackpot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.casino.Casino;
import dev.casino.economy.Wallet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-owned progressive jackpot.
 * <p>
 * A small percentage of every bet placed at any table or machine is swept into the
 * jackpot pool. The jackpot is awarded when a player hits a rare jackpot event
 * (slot 7-7-7, roulette straight-up 0, blackjack natural, baccarat natural 9, etc.).
 * The winner takes the entire pool and it resets to the seed value.
 * <p>
 * Everything is server-authoritative: the pool, the seed, and the win decision all
 * live here. Clients only ever see the current jackpot amount in the UI.
 */
public final class ProgressiveJackpot {
    private static final Logger LOGGER = LoggerFactory.getLogger("CasinoJackpot");
    private static final Gson GSON = new Gson();

    /** What fraction of each bet (0..1) flows into the jackpot. */
    public static final double CONTRIBUTION_RATE = 0.02; // 2%

    /** Starting value of the jackpot after a win. */
    public static final long SEED = 10_000L;

    private static Path file = null;
    private static final ConcurrentHashMap<String, Long> pools = new ConcurrentHashMap<>(); // gameId -> pool

    private ProgressiveJackpot() {}

    public static void init(MinecraftServer server) {
        try {
            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("casino");
            Files.createDirectories(dir);
            file = dir.resolve("jackpot.json");
            load();
            // Ensure every known game has a pool entry
            pools.putIfAbsent("slots", SEED);
            pools.putIfAbsent("roulette", SEED);
            pools.putIfAbsent("blackjack", SEED);
            pools.putIfAbsent("baccarat", SEED);
            pools.putIfAbsent("poker", SEED);
            pools.putIfAbsent("craps", SEED);
            pools.putIfAbsent("keno", SEED);
            pools.putIfAbsent("wheel", SEED);
            save();
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise progressive jackpot", e);
        }
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            JsonObject raw = new JsonObject();
            for (var e : pools.entrySet()) raw.addProperty(e.getKey(), e.getValue());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(raw, w);
            }
            if (Files.exists(file)) Files.move(file, file.resolveSibling(file.getFileName() + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write jackpot.json", e);
        }
    }

    private static void load() {
        if (file == null || !Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            JsonObject raw = GSON.fromJson(r, JsonObject.class);
            if (raw == null) return;
            for (var e : raw.entrySet()) {
                try { pools.put(e.getKey(), e.getValue().getAsLong()); }
                catch (Exception ignored) {}
            }
        } catch (Exception e) {
            LOGGER.error("Could not read jackpot.json", e);
        }
    }

    /** Sweep a contribution from a bet into the jackpot for the given game. */
    public static void contribute(String gameId, long bet) {
        if (bet <= 0) return;
        long contribution = (long) (bet * CONTRIBUTION_RATE);
        if (contribution <= 0) return;
        pools.merge(gameId, contribution, Long::sum);
    }

    /** Award the entire jackpot pool for a game to a player, then reset to seed. */
    public static long award(String gameId, UUID winner) {
        long pool = pools.getOrDefault(gameId, SEED);
        if (pool <= 0) pool = SEED;
        pools.put(gameId, SEED);
        save();
        Wallet.deposit(winner, pool);
        LOGGER.info("Jackpot won! {} won {} Chips on {}", winner, pool, gameId);
        return pool;
    }

    public static long getPool(String gameId) {
        return pools.getOrDefault(gameId, SEED);
    }

    public static boolean shouldHitJackpot(String gameId) {
        // Base chance scales with pool size: larger pool = more frequent payouts
        // so the jackpot doesn't grow forever without being won.
        long pool = getPool(gameId);
        double baseChance = 1.0 / 50000.0; // 1 in 50k at seed
        double poolMultiplier = Math.min(10.0, pool / (double) SEED); // cap at 10x
        return ThreadLocalRandom.current().nextDouble() < baseChance * poolMultiplier;
    }
}
