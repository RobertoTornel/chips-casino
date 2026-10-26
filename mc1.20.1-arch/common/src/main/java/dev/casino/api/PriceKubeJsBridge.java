package dev.casino.api;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * KubeJS-facing bridge for the Chips table.
 *
 * KubeJS server scripts can reach these methods without a compile-time dependency:
 *
 * <pre>{@code
 * // kubejs/server_scripts/casino.js
 * const Casino = Java.loadClass('dev.casino.api.PriceKubeJsBridge')
 *
 * ServerEvents.loaded(event => {
 *   Casino.set('minecraft:diamond', 9000)
 *   Casino.set('minecraft:emerald', 20000)
 *   Casino.remove('minecraft:dirt')
 *   console.info('house buys diamond at ' + Casino.sellPrice('minecraft:diamond'))
 * })
 * }</pre>
 *
 * Every mutator is synchronous and safe to call from the server thread. Values set
 * here are lost on restart unless the same script runs again — for persistent values
 * write a data pack file at {@code data/<namespace>/prices/<file>.json} instead, which
 * {@link PriceDataLoader} picks up and re-applies on every /reload.
 */
public final class PriceKubeJsBridge {

    private PriceKubeJsBridge() {
    }

    /** Set the base Chips value of an item id (e.g. {@code "minecraft:diamond"}). */
    public static void set(String itemId, long value) {
        PriceTable.setPrice(itemId, value);
    }

    /** Remove an item from the price table so the house neither buys nor sells it. */
    public static void remove(String itemId) {
        PriceTable.removePrice(itemId);
    }

    /** Base Chips value of an item id, or 0 if it has none. */
    public static long get(String itemId) {
        return PriceTable.getPrice(itemId);
    }

    /** What the house pays when you sell one of this item (0.8x of base). */
    public static long sellPrice(String itemId) {
        return PriceTable.sellPrice(PriceTable.getPrice(itemId));
    }

    /** What it costs to withdraw one of this item (1.2x). */
    public static long buyPrice(String itemId) {
        return PriceTable.buyPrice(PriceTable.getPrice(itemId));
    }

    /** True when the item id has an Chips value defined. */
    public static boolean has(String itemId) {
        return PriceTable.hasPrice(itemId);
    }

    /**
     * Set an Chips value using an ItemStack-like lookup by registry name.
     * Returns the resolved item id, or {@code null} when the id is unknown.
     */
    public static String setById(String itemId, long value) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        if (item == null) {
            return null;
        }
        ResourceLocation resolved = BuiltInRegistries.ITEM.getKey(item);
        PriceTable.setPrice(resolved.toString(), value);
        return resolved.toString();
    }

    /** Number of items that currently have an Chips value. */
    public static int size() {
        return PriceTable.getAllPrices().size();
    }

    /**
     * Convenience helper so scripts can price whatever the player is holding.
     * Returns 0 when the stack is empty or unpriced.
     */
    public static long sellValueOfStack(ItemStack stack) {
        return PriceTable.sellPrice(stack);
    }

    /** Convenience helper mirroring {@link #sellValueOfStack} for buying. */
    public static long buyCostOfStack(ItemStack stack) {
        return PriceTable.buyPrice(stack);
    }
}
