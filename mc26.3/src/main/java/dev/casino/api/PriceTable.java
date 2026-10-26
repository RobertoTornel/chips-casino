package dev.casino.api;

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
 * Hardcoded & editable base price table with a house-edge balancing rule:
 * 0.8x sell multiplier (players selling items to the house)
 * 1.2x buy multiplier (players buying items from the house)
 *
 * Fully scriptable by KubeJS (via PriceTable.setPrice(...) or Java.loadClass)
 * and configurable via datapack JSON in data/casino/prices/values.json.
 */
public class PriceTable {
    static final Logger LOGGER = LoggerFactory.getLogger("PriceTable");
    private static final Map<String, Long> PRICES = new ConcurrentHashMap<>();

    public static final double SELL_MULTIPLIER = 0.8;
    public static final double BUY_MULTIPLIER = 1.2;

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

    private static void put(String id, long value) {
        PRICES.put(id, value);
    }

    /**
     * KubeJS compatibility hook:
     * e.g. PriceTable.setPrice("minecraft:diamond", 10000);
     */
    public static void setPrice(String itemId, long value) {
        if (value <= 0) {
            PRICES.remove(itemId);
        } else {
            PRICES.put(itemId, value);
        }
        LOGGER.info("Set price for {}: {}", itemId, value);
    }

    public static void removePrice(String itemId) {
        PRICES.remove(itemId);
    }

    public static boolean hasPrice(String itemId) {
        return PRICES.containsKey(itemId) && PRICES.get(itemId) > 0;
    }

    public static boolean hasPrice(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return hasPrice(id.toString());
    }

    public static long getPrice(String itemId) {
        return PRICES.getOrDefault(itemId, 0L);
    }

    public static long getPrice(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0L;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return getPrice(id.toString());
    }

    /**
     * Sell multiplier (0.8x):
     * Putting items in gives 80% of their base Chips value.
     */
    public static long sellPrice(long basePrice) {
        if (basePrice <= 0) return 0L;
        return Math.max(1L, (long) Math.floor(basePrice * SELL_MULTIPLIER));
    }

    public static long sellPrice(ItemStack stack) {
        long base = getPrice(stack);
        if (base <= 0) return 0L;
        return sellPrice(base) * stack.getCount();
    }

    /**
     * Withdraw multiplier (1.2x):
     * Withdrawing items costs 120% of their base Chips value.
     */
    public static long buyPrice(long basePrice) {
        if (basePrice <= 0) return 0L;
        return Math.max(1L, (long) Math.ceil(basePrice * BUY_MULTIPLIER));
    }

    public static long buyPrice(ItemStack stack) {
        long base = getPrice(stack);
        if (base <= 0) return 0L;
        return buyPrice(base) * stack.getCount();
    }

    public static Map<String, Long> getAllPrices() {
        return Collections.unmodifiableMap(PRICES);
    }

    public static void loadFromJson(JsonObject root) {
        if (root == null) return;
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            try {
                long val = entry.getValue().getAsLong();
                setPrice(entry.getKey(), val);
                count++;
            } catch (Exception e) {
                LOGGER.warn("Invalid Chips value for {}: {}", entry.getKey(), entry.getValue());
            }
        }
        LOGGER.info("Loaded {} Chips definitions from JSON", count);
    }
}
