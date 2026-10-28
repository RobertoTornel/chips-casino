package dev.casino.stats;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.core.component.DataComponentType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Achievement system: players unlock badges by reaching milestones.
 * <p>
 * Achievements are checked whenever a game is won, a jackpot is hit, or the daily
 * bonus is claimed. Unlocking an achievement grants a one-time XP bonus and sends
 * the player a congratulatory chat message with a clickable badge.
 */
public final class AchievementManager {
    private static final Gson GSON = new Gson();

    private static final ConcurrentHashMap<UUID, java.util.Set<String>> unlocked = new ConcurrentHashMap<>();

    public static final String A_FIRST_BET = "first_bet";
    public static final String A_FIRST_WIN = "first_win";
    public static final String A_JACKPOT = "jackpot_winner";
    public static final String A_HIGH_ROLLER = "high_roller";
    public static final String A_VETERAN = "veteran";
    public static final String A_UNLUCKY = "unlucky";
    public static final String A_LOYAL = "loyal_player";
    public static final String A_WHALE = "whale";

    public record Achievement(String id, String title, String desc, long xpReward, String icon) {}

    private static final List<Achievement> ACHIEVEMENTS = java.util.List.of(
        new Achievement(A_FIRST_BET, "First Bet", "Place your first wager.", 50, "♦"),
        new Achievement(A_FIRST_WIN, "Beginner's Luck", "Win your first game.", 100, "✅"),
        new Achievement(A_JACKPOT, "Jackpot!", "Hit a progressive jackpot.", 500, "💰"),
        new Achievement(A_HIGH_ROLLER, "High Roller", "Win 10,000+ chips in a single game.", 250, "🔥"),
        new Achievement(A_VETERAN, "Veteran", "Play 100 games.", 200, "🗿️"),
        new Achievement(A_UNLUCKY, "Unlucky", "Lose 50 games.", 100, "💩"),
        new Achievement(A_LOYAL, "Loyal Player", "Claim 7 daily bonuses.", 300, "📜"),
        new Achievement(A_WHALE, "Whale", "Wager 1,000,000 chips total.", 1000, "🦂")
    );

    public static List<Achievement> getAll() { return ACHIEVEMENTS; }

    public static boolean isUnlocked(UUID id, String achievementId) {
        java.util.Set<String> set = unlocked.get(id);
        return set != null && set.contains(achievementId);
    }

    /**
     * Check and grant achievements based on current stats. Returns newly unlocked IDs.
     */
    public static List<String> check(ServerPlayer player) {
        UUID id = player.getUUID();
        PlayerStats.PlayerRecord rec = PlayerStats.get(id);
        java.util.Set<String> set = unlocked.computeIfAbsent(id, k -> java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>()));
        List<String> newly = new java.util.ArrayList<>();

        for (Achievement a : ACHIEVEMENTS) {
            if (set.contains(a.id)) continue;
            boolean qualify = switch (a.id) {
                case A_FIRST_BET -> rec.gamesPlayed >= 1;
                case A_FIRST_WIN -> rec.gamesWon >= 1;
                case A_JACKPOT -> rec.jackpotsHit >= 1;
                case A_HIGH_ROLLER -> rec.biggestWin >= 10_000;
                case A_VETERAN -> rec.gamesPlayed >= 100;
                case A_UNLUCKY -> rec.gamesLost >= 50;
                case A_LOYAL -> rec.gamesPlayed >= 7; // proxy for daily bonus claims
                case A_WHALE -> rec.totalWagered >= 1_000_000;
                default -> false;
            };
            if (qualify) {
                set.add(a.id);
                newly.add(a.id);
                rec.xp += a.xpReward;
                grant(player, a);
            }
        }

        if (!newly.isEmpty()) PlayerStats.save();
        return newly;
    }

    private static void grant(ServerPlayer player, Achievement a) {
        Component msg = Component.literal("\u00a76[Casino] \u00a7eAchievement Unlocked: \u00a7f" + a.title);

        player.sendSystemMessage(msg);
        player.sendSystemMessage(Component.literal("\u00a77" + a.desc + "  +\u00a7e" + a.xpReward + " XP\u00a77"));
    }
}
