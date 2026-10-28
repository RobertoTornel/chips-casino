package dev.macasino.economy;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Thin compatibility facade over {@link Wallet}.
 *
 * <p>The machines and screens across this codebase speak this older vocabulary
 * ({@code addBalance}, {@code deductBalance}); new code should use {@link Wallet} directly.
 * Keeping the facade means the money rules live in exactly one place while call sites
 * migrate at their own pace.
 *
 * @deprecated use {@link Wallet}.
 */
@Deprecated
public final class CasinoAccountManager {

    private CasinoAccountManager() {
    }

    public static void init(MinecraftServer server) {
        Wallet.init(server);
    }

    /** Call once per server tick. Flushes dirty wallet balances to disk on a schedule
     *  instead of writing on every mutation. */
    public static void tick() {
        Wallet.tick();
    }

    /** Force an immediate wallet flush. Call on server stop. */
    public static void saveNow() {
        Wallet.saveNow();
    }

    public static long getBalance(UUID uuid) {
        return Wallet.getBalance(uuid);
    }

    public static void setBalance(UUID uuid, long amount) {
        Wallet.setBalance(uuid, amount);
    }

    public static void addBalance(UUID uuid, long amount) {
        Wallet.deposit(uuid, amount);
    }

    public static boolean deductBalance(UUID uuid, long amount) {
        return Wallet.withdraw(uuid, amount);
    }

    public static void load() {
        // Wallet owns its own persistence; nothing to do here.
    }

    public static void save() {
        // Wallet owns its own persistence; nothing to do here.
    }
}
