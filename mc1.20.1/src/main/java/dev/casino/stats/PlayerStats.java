package dev.casino.stats;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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

/**
 * Persistent per-player gambling statistics.
 * <p>
 * Tracks games played, won, lost, total wagered, total won, biggest single win,
 * jackpots hit, and play time. Used for the stats command, achievements, and VIP
 * level calculation. Everything is server-owned; clients only see what we send them.
 */
public final class PlayerStats {
    private static final Logger LOGGER = LoggerFactory.getLogger("CasinoStats");
    private static final Gson GSON = new Gson();

    public static final long DAILY_BONUS = 500L;
    public static final long DAILY_BONUS_COOLDOWN_MS = 24 * 60 * 60 * 1000;

    private static Path file = null;
    private static final ConcurrentHashMap<UUID, PlayerRecord> records = new ConcurrentHashMap<>();

    private PlayerStats() {}

    public static void init(MinecraftServer server) {
        try {
            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("casino");
            Files.createDirectories(dir);
            file = dir.resolve("player_stats.json");
            load();
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise player stats", e);
        }
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            JsonObject raw = new JsonObject();
            for (var e : records.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("gamesPlayed", e.getValue().gamesPlayed);
                o.addProperty("gamesWon", e.getValue().gamesWon);
                o.addProperty("gamesLost", e.getValue().gamesLost);
                o.addProperty("totalWagered", e.getValue().totalWagered);
                o.addProperty("totalWon", e.getValue().totalWon);
                o.addProperty("biggestWin", e.getValue().biggestWin);
                o.addProperty("jackpotsHit", e.getValue().jackpotsHit);
                o.addProperty("xp", e.getValue().xp);
                o.addProperty("lastDailyBonusMs", e.getValue().lastDailyBonusMs);
                raw.add(e.getKey().toString(), o);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(raw, w);
            }
            if (Files.exists(file)) Files.move(file, file.resolveSibling(file.getFileName() + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write player_stats.json", e);
        }
    }

    private static void load() {
        if (file == null || !Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            JsonObject raw = GSON.fromJson(r, JsonObject.class);
            if (raw == null) return;
            for (var e : raw.entrySet()) {
                try {
                    UUID id = UUID.fromString(e.getKey());
                    JsonObject o = e.getValue().getAsJsonObject();
                    PlayerRecord rec = new PlayerRecord();
                    rec.gamesPlayed = o.has("gamesPlayed") ? o.get("gamesPlayed").getAsLong() : 0;
                    rec.gamesWon = o.has("gamesWon") ? o.get("gamesWon").getAsLong() : 0;
                    rec.gamesLost = o.has("gamesLost") ? o.get("gamesLost").getAsLong() : 0;
                    rec.totalWagered = o.has("totalWagered") ? o.get("totalWagered").getAsLong() : 0;
                    rec.totalWon = o.has("totalWon") ? o.get("totalWon").getAsLong() : 0;
                    rec.biggestWin = o.has("biggestWin") ? o.get("biggestWin").getAsLong() : 0;
                    rec.jackpotsHit = o.has("jackpotsHit") ? o.get("jackpotsHit").getAsLong() : 0;
                    rec.xp = o.has("xp") ? o.get("xp").getAsLong() : 0;
                    rec.lastDailyBonusMs = o.has("lastDailyBonusMs") ? o.get("lastDailyBonusMs").getAsLong() : 0;
                    records.put(id, rec);
                } catch (Exception ignored) {}
            }
        } catch (Exception ex) {
            LOGGER.error("Could not read player_stats.json", ex);
        }
    }

    public static PlayerRecord get(UUID id) {
        return records.computeIfAbsent(id, k -> new PlayerRecord());
    }

    /** Record a wager. Returns true if the player leveled up. */
    public static boolean wager(UUID id, long amount, String game) {
        PlayerRecord rec = get(id);
        rec.gamesPlayed++;
        rec.totalWagered += amount;
        rec.xp += amount / 100; // 1 XP per 100 chips wagered
        boolean leveled = rec.xp >= levelXp(rec.level() + 1);
        save();
        return leveled;
    }

    /** Record a win. */
    public static void win(UUID id, long amount, boolean jackpot) {
        PlayerRecord rec = get(id);
        rec.gamesWon++;
        rec.totalWon += amount;
        if (amount > rec.biggestWin) rec.biggestWin = amount;
        if (jackpot) rec.jackpotsHit++;
        rec.xp += amount / 50; // bonus XP for winning
        save();
    }

    /** Record a loss. */
    public static void lose(UUID id) {
        PlayerRecord rec = get(id);
        rec.gamesLost++;
        save();
    }

    /** Claim the daily bonus if cooldown has elapsed. Returns the amount, or 0 if not ready. */
    public static long claimDailyBonus(UUID id) {
        PlayerRecord rec = get(id);
        long now = System.currentTimeMillis();
        if (now - rec.lastDailyBonusMs >= DAILY_BONUS_COOLDOWN_MS) {
            rec.lastDailyBonusMs = now;
            Wallet.deposit(id, DAILY_BONUS);
            rec.xp += 100;
            save();
            return DAILY_BONUS;
        }
        return 0;
    }

    public static long dailyBonusRemainingMs(UUID id) {
        PlayerRecord rec = get(id);
        long now = System.currentTimeMillis();
        long remaining = DAILY_BONUS_COOLDOWN_MS - (now - rec.lastDailyBonusMs);
        return remaining > 0 ? remaining : 0;
    }

    public static int level(PlayerRecord rec) {
        // Level = floor(log2(xp / 1000 + 1))
        return (int) (Math.log(rec.xp / 1000.0 + 1.0) / Math.log(2.0));
    }

    public static long levelXp(int level) {
        return (long) (1000.0 * (Math.pow(2.0, level) - 1.0));
    }

    public static long xpToNextLevel(PlayerRecord rec) {
        int lvl = level(rec);
        long next = levelXp(lvl + 1);
        return Math.max(0, next - rec.xp);
    }

    public static class PlayerRecord {
        public long gamesPlayed = 0;
        public long gamesWon = 0;
        public long gamesLost = 0;
        public long totalWagered = 0;
        public long totalWon = 0;
        public long biggestWin = 0;
        public long jackpotsHit = 0;
        public long xp = 0;
        public long lastDailyBonusMs = 0;

        public int level() { return PlayerStats.level(this); }
        public long xpToNextLevel() { return PlayerStats.xpToNextLevel(this); }
        public double winRate() {
            return gamesPlayed == 0 ? 0 : (double) gamesWon / gamesPlayed * 100.0;
        }
    }
}
