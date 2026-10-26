package dev.emccasino.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hardcoded & editable EMC values table with ProjectE-style balancing:
 * 0.8x deposit multiplier (cashing in items to balance)
 * 1.2x withdraw multiplier (cashing out balance to items)
 *
 * Fully scriptable by KubeJS (via EmcRegistry.setEmc(...) or Java.loadClass)
 * and configurable via datapack JSON in data/emc_casino/emc/values.json.
 */
public class EmcRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("EmcRegistry");
    private static final Map<String, Long> EMC_MAP = new ConcurrentHashMap<>();

    public static final double DEPOSIT_MULTIPLIER = 0.8;
    public static final double WITHDRAW_MULTIPLIER = 1.2;

    static {
        registerDefaults();
    }

    public static void registerDefaults() {
        // Minerals & Gems
        put("minecraft:cobblestone", 1L);
        put("minecraft:stone", 1L);
        put("minecraft:dirt", 1L);
        put("minecraft:sand", 1L);
        put("minecraft:gravel", 4L);
        put("minecraft:flint", 4L);
        put("minecraft:coal", 128L);
        put("minecraft:charcoal", 32L);
        put("minecraft:copper_ingot", 64L);
        put("minecraft:raw_copper", 64L);
        put("minecraft:iron_ingot", 256L);
        put("minecraft:raw_iron", 256L);
        put("minecraft:gold_ingot", 2048L);
        put("minecraft:raw_gold", 2048L);
        put("minecraft:redstone", 64L);
        put("minecraft:lapis_lazuli", 864L);
        put("minecraft:diamond", 8192L);
        put("minecraft:emerald", 16384L);
        put("minecraft:netherite_scrap", 14336L);
        put("minecraft:netherite_ingot", 57344L);
        put("minecraft:nether_quartz", 256L);
        put("minecraft:amethyst_shard", 32L);
        put("minecraft:obsidian", 64L);
        put("minecraft:crying_obsidian", 768L);
        put("minecraft:glowstone_dust", 384L);

        // Rare & Boss Drops
        put("minecraft:nether_star", 139264L);
        put("minecraft:dragon_egg", 262144L);
        put("minecraft:beacon", 140000L);
        put("minecraft:totem_of_undying", 32768L);
        put("minecraft:elytra", 131072L);
        put("minecraft:trident", 16384L);
        put("minecraft:heart_of_the_sea", 32768L);
        put("minecraft:nautilus_shell", 1024L);

        // Mob Drops
        put("minecraft:ender_pearl", 1024L);
        put("minecraft:blaze_rod", 1536L);
        put("minecraft:ghast_tear", 4096L);
        put("minecraft:gunpowder", 192L);
        put("minecraft:slime_ball", 32L);
        put("minecraft:leather", 64L);
        put("minecraft:feather", 48L);
        put("minecraft:string", 12L);
        put("minecraft:bone", 144L);
        put("minecraft:arrow", 14L);
        put("minecraft:spider_eye", 128L);
        put("minecraft:shulker_shell", 2048L);

        // Woods & Plants
        put("minecraft:oak_log", 32L);
        put("minecraft:spruce_log", 32L);
        put("minecraft:birch_log", 32L);
        put("minecraft:jungle_log", 32L);
        put("minecraft:acacia_log", 32L);
        put("minecraft:dark_oak_log", 32L);
        put("minecraft:mangrove_log", 32L);
        put("minecraft:cherry_log", 32L);
        put("minecraft:bamboo", 16L);
        put("minecraft:wheat", 24L);
        put("minecraft:carrot", 24L);
        put("minecraft:potato", 24L);
        put("minecraft:sugar_cane", 32L);
        put("minecraft:apple", 128L);
        put("minecraft:golden_apple", 16512L);
        put("minecraft:enchanted_golden_apple", 147456L);

        // Food & Misc
        put("minecraft:bread", 72L);
        put("minecraft:beef", 64L);
        put("minecraft:cooked_beef", 64L);
        put("minecraft:porkchop", 64L);
        put("minecraft:cooked_porkchop", 64L);
        put("minecraft:egg", 32L);
        put("minecraft:glass", 1L);
        put("minecraft:brick", 16L);
        put("minecraft:prismarine_shard", 256L);
        put("minecraft:prismarine_crystals", 512L);
    }

    private static void put(String id, long emc) {
        EMC_MAP.put(id, emc);
    }

    /**
     * KubeJS compatibility hook:
     * e.g. EmcRegistry.setEmc("minecraft:diamond", 10000);
     */
    public static void setEmc(String itemId, long emc) {
        if (emc <= 0) {
            EMC_MAP.remove(itemId);
        } else {
            EMC_MAP.put(itemId, emc);
        }
        LOGGER.info("Set EMC for {}: {}", itemId, emc);
    }

    public static void removeEmc(String itemId) {
        EMC_MAP.remove(itemId);
    }

    public static boolean hasEmc(String itemId) {
        return EMC_MAP.containsKey(itemId) && EMC_MAP.get(itemId) > 0;
    }

    public static boolean hasEmc(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return hasEmc(id.toString());
    }

    public static long getEmc(String itemId) {
        return EMC_MAP.getOrDefault(itemId, 0L);
    }

    public static long getEmc(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0L;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return getEmc(id.toString());
    }

    /**
     * Deposit multiplier (0.8x):
     * Putting items in gives 80% of their base EMC value.
     */
    public static long getDepositValue(long baseEmc) {
        if (baseEmc <= 0) return 0L;
        return Math.max(1L, (long) Math.floor(baseEmc * DEPOSIT_MULTIPLIER));
    }

    public static long getDepositValue(ItemStack stack) {
        long base = getEmc(stack);
        if (base <= 0) return 0L;
        return getDepositValue(base) * stack.getCount();
    }

    /**
     * Withdraw multiplier (1.2x):
     * Withdrawing items costs 120% of their base EMC value.
     */
    public static long getWithdrawCost(long baseEmc) {
        if (baseEmc <= 0) return 0L;
        return Math.max(1L, (long) Math.ceil(baseEmc * WITHDRAW_MULTIPLIER));
    }

    public static long getWithdrawCost(ItemStack stack) {
        long base = getEmc(stack);
        if (base <= 0) return 0L;
        return getWithdrawCost(base) * stack.getCount();
    }

    public static Map<String, Long> getAllEmcValues() {
        return Collections.unmodifiableMap(EMC_MAP);
    }

    public static void loadFromJson(JsonObject root) {
        if (root == null) return;
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            try {
                long val = entry.getValue().getAsLong();
                setEmc(entry.getKey(), val);
                count++;
            } catch (Exception e) {
                LOGGER.warn("Invalid EMC value for {}: {}", entry.getKey(), entry.getValue());
            }
        }
        LOGGER.info("Loaded {} EMC definitions from JSON", count);
    }
}
