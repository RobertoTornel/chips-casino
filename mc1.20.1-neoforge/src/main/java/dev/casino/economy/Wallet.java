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
import java.nio.file.StandardCopyOption;
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
 *
 * <p><b>Persistence is batched.</b> Mutations mark the store dirty and {@link #tick()}
 * flushes to disk every {@code SAVE_INTERVAL_TICKS} (default 600 ticks = 30 s at 20 TPS).
 * {@link #save()} forces an immediate write and is only called on server stop. This avoids
 * one disk write per bet — under load a busy table can easily do 20+ balance mutations per
 * second, and writing the whole wallet file for each one would tank TPS.
 */
public final class Wallet {

    private static final Logger LOGGER = LoggerFactory.getLogger("CasinoWallet");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, Long> BALANCES = new ConcurrentHashMap<>();

    /** Dirty flag: true when an in-memory balance differs from what is on disk. */
    private static volatile boolean dirty = false;

    /** Ticks since the last successful disk flush. */
    private static int ticksSinceSave = 0;

    /** Flush the wallet file at least this often, even if no change occurred (defensive). */
    private static final int SAVE_INTERVAL_TICKS = 600; // 30 s @ 20 TPS

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
            dirty = false;
            ticksSinceSave = 0;
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise the wallet store", e);
        }
    }

    /** Call once per server tick from {@link CasinoAccountManager#tick()}. Flushes dirty
     *  balances to disk on a schedule so the file is never more than {@code SAVE_INTERVAL_TICKS}
     *  behind memory. */
    public static void tick() {
        if (file == null) {
            return;
        }
        ticksSinceSave++;
        if (dirty && ticksSinceSave >= SAVE_INTERVAL_TICKS) {
            save();
        }
    }

    /** Force an immediate disk flush. Called on server stop so nothing is lost. */
    public static void saveNow() {
        if (dirty) {
            save();
        }
    }

    /** Mark the store dirty without writing. Use this for bulk operations. */
    static void markDirty() {
        dirty = true;
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
        if (starting < 0) {
            LOGGER.warn("createAccount called with negative starting amount {} for {}, clamping to 0", starting, id);
        }
        BALANCES.put(id, Math.max(0L, starting));
        markDirty();
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
        markDirty();
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
        markDirty();
        return true;
    }

    /** Sets a balance outright. Admin/debug use. */
    public static void setBalance(UUID id, long amount) {
        if (amount < 0) {
            LOGGER.warn("setBalance called with negative amount {} for {}, clamping to 0", amount, id);
        }
        BALANCES.put(id, Math.max(0L, amount));
        markDirty();
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
        synchronized (BALANCES) {
            long source = getBalance(from);
            if (source < amount) {
                return false;
            }
            BALANCES.put(from, source - amount);
            BALANCES.merge(to, amount, Long::sum);
            markDirty();
            return true;
        }
    }

    /** Wipes every balance. Used by {@code /casino wallet reset <player>}. */
    public static void clear(UUID id) {
        BALANCES.put(id, 0L);
        markDirty();
    }

    // ---------------------------------------------------------------- persistence

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
            // Atomic write: write to a temp file then rename into place, so a crash
            // mid-write can never leave a truncated wallets.json behind. Keep one
            // backup so we can recover from any corruption that predates the rename.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Path bak = file.resolveSibling(file.getFileName() + ".bak");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(raw, w);
            }
            if (Files.exists(file)) {
                Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
            ticksSinceSave = 0;
        } catch (Exception e) {
            throw new IllegalStateException("Could not write wallets.json", e);
        }
    }

    /** Restore balances from the backup file if the primary is missing or unreadable. */
    private static void loadFromBackup() {
        if (file == null) {
            return;
        }
        Path bak = file.resolveSibling(file.getFileName() + ".bak");
        if (!Files.exists(bak)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(bak)) {
            Type type = new TypeToken<Map<String, Long>>() {}.getType();
            Map<String, Long> raw = GSON.fromJson(r, type);
            if (raw == null) {
                return;
            }
            BALANCES.clear();
            int n = 0;
            for (Map.Entry<String, Long> e : raw.entrySet()) {
                try {
                    long v = e.getValue();
                    if (v < 0) {
                        LOGGER.warn("Loaded negative balance {} for {}, clamping to 0", v, e.getKey());
                    }
                    BALANCES.put(UUID.fromString(e.getKey()), Math.max(0L, v));
                    n++;
                } catch (IllegalArgumentException ignored) {
                    // a malformed uuid in the backup should not stop the rest loading
                }
            }
            LOGGER.info("Recovered {} wallet(s) from backup", n);
        } catch (Exception e) {
            LOGGER.error("Could not read wallets backup", e);
        }
    }

    private static void load() {
        if (file == null || !Files.exists(file)) {
            loadFromBackup();
            return;
        }
        try (Reader r = Files.newBufferedReader(file)) {
            Type type = new TypeToken<Map<String, Long>>() {}.getType();
            Map<String, Long> raw = GSON.fromJson(r, type);
            if (raw == null || raw.isEmpty()) {
                loadFromBackup();
                return;
            }
            BALANCES.clear();
            int n = 0;
            for (Map.Entry<String, Long> e : raw.entrySet()) {
                try {
                    long v = e.getValue();
                    if (v < 0) {
                        LOGGER.warn("Loaded negative balance {} for {}, clamping to 0", v, e.getKey());
                    }
                    BALANCES.put(UUID.fromString(e.getKey()), Math.max(0L, v));
                    n++;
                } catch (IllegalArgumentException ignored) {
                    // a malformed uuid in the file should not stop the rest loading
                }
            }
            LOGGER.info("Loaded {} wallet(s)", n);
        } catch (Exception e) {
            LOGGER.error("Could not read wallets.json", e);
            loadFromBackup();
        }
    }
}
