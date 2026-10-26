package dev.casino.economy;

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
 * Player wallet: the mod's own money system.
 *
 * <p>Long-based, per-player, persisted to {@code <world>/casino/wallets.json}. The API is
 * deliberately shaped like the well-known Bukkit economy services — getBalance / deposit /
 * withdraw / has / createAccount / transfer — so that anyone who has written against one of
 * those before can use this immediately, and so a platform bridge can be dropped in later
 * without touching a single call site.
 *
 * <p>All amounts are whole units. Withdrawals are atomic: {@link #withdraw(UUID, long)}
 * returns {@code false} and changes nothing if the player cannot cover the amount, which is
 * what stops a bet from going through twice or a purchase from going negative.
 */
public final class Wallet {

    private static final Logger LOGGER = LoggerFactory.getLogger("CasinoWallet");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, Long> BALANCES = new ConcurrentHashMap<>();

    private static Path file = null;

    private Wallet() {
    }

    /** The currency name shown in the UI. */
    public static final String CURRENCY = "Chips";
    /** Short symbol shown next to amounts. */
    public static final String SYMBOL = "◈";

    // ---------------------------------------------------------------- lifecycle

    public static void init(MinecraftServer server) {
        try {
            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("casino");
            Files.createDirectories(dir);
            file = dir.resolve("wallets.json");
            load();
        } catch (Exception e) {
            LOGGER.error("Could not initialise the wallet store", e);
        }
    }

    // ---------------------------------------------------------------- queries

    /** Balance of a player, or 0 if they have no account yet. */
    public static long getBalance(UUID id) {
        return BALANCES.getOrDefault(id, 0L);
    }

    /** True if the player has an account (even one with a zero balance). */
    public static boolean hasAccount(UUID id) {
        return BALANCES.containsKey(id);
    }

    /** True if the balance covers {@code amount}. */
    public static boolean has(UUID id, long amount) {
        return amount <= 0 || getBalance(id) >= amount;
    }

    // ---------------------------------------------------------------- mutation

    /** Creates an account with {@code starting} (0 if none). Returns false if one existed. */
    public static boolean createAccount(UUID id, long starting) {
        if (BALANCES.containsKey(id)) {
            return false;
        }
        BALANCES.put(id, Math.max(0L, starting));
        save();
        return true;
    }

    public static boolean createAccount(UUID id) {
        return createAccount(id, 0L);
    }

    /** Adds money. Negative amounts are ignored — use {@link #withdraw} to take money. */
    public static void deposit(UUID id, long amount) {
        if (amount <= 0) {
            return;
        }
        BALANCES.merge(id, amount, Long::sum);
        save();
    }

    /**
     * Takes money if the player can cover it.
     *
     * @return true if the withdrawal happened; false (and no change) if it could not be covered
     */
    public static boolean withdraw(UUID id, long amount) {
        if (amount <= 0) {
            return true;
        }
        long current = getBalance(id);
        if (current < amount) {
            return false;
        }
        BALANCES.put(id, current - amount);
        save();
        return true;
    }

    /** Sets a balance outright. Admin/debug use. */
    public static void setBalance(UUID id, long amount) {
        BALANCES.put(id, Math.max(0L, amount));
        save();
    }

    /**
     * Moves money between two players atomically — either both sides happen or neither does.
     *
     * @return true on success
     */
    public static boolean transfer(UUID from, UUID to, long amount) {
        if (amount <= 0 || from.equals(to)) {
            return false;
        }
        long source = getBalance(from);
        if (source < amount) {
            return false;
        }
        BALANCES.put(from, source - amount);
        BALANCES.merge(to, amount, Long::sum);
        save();
        return true;
    }

    /** Wipes every balance. Used by {@code /casino wallet reset <player>}. */
    public static void clear(UUID id) {
        BALANCES.remove(id);
        save();
    }

    // ---------------------------------------------------------------- persistence

    private static void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(file)) {
            Type type = new TypeToken<Map<String, Long>>() {}.getType();
            Map<String, Long> raw = GSON.fromJson(r, type);
            if (raw == null) {
                return;
            }
            BALANCES.clear();
            for (Map.Entry<String, Long> e : raw.entrySet()) {
                try {
                    BALANCES.put(UUID.fromString(e.getKey()), Math.max(0L, e.getValue()));
                } catch (IllegalArgumentException ignored) {
                    // a malformed uuid in the file should not stop the rest loading
                }
            }
            LOGGER.info("Loaded {} wallet(s)", BALANCES.size());
        } catch (Exception e) {
            LOGGER.error("Could not read wallets.json", e);
        }
    }

    private static void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Map<String, Long> raw = new ConcurrentHashMap<>();
            for (Map.Entry<UUID, Long> e : BALANCES.entrySet()) {
                raw.put(e.getKey().toString(), e.getValue());
            }
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(raw, w);
            }
        } catch (Exception e) {
            LOGGER.error("Could not write wallets.json", e);
        }
    }
}
