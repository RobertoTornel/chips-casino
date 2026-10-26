package dev.emccasino.api;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * KubeJS-facing bridge for the EMC table.
 *
 * KubeJS server scripts can reach these methods without a compile-time dependency:
 *
 * <pre>{@code
 * // kubejs/server_scripts/emc_casino.js
 * const EMC = Java.loadClass('dev.emccasino.api.EmcKubeJsBridge')
 *
 * ServerEvents.loaded(event => {
 *   EMC.set('minecraft:diamond', 9000)
 *   EMC.set('minecraft:emerald', 20000)
 *   EMC.remove('minecraft:dirt')
 *   console.info('diamond deposit = ' + EMC.deposit('minecraft:diamond'))
 * })
 * }</pre>
 *
 * Every mutator is synchronous and safe to call from the server thread. Values set
 * here are lost on restart unless the same script runs again — for persistent values
 * write a data pack file at {@code data/<namespace>/emc/<file>.json} instead, which
 * {@link EmcDataLoader} picks up and re-applies on every /reload.
 */
public final class EmcKubeJsBridge {

    private EmcKubeJsBridge() {
    }

    /** Set the base EMC value of an item id (e.g. {@code "minecraft:diamond"}). */
    public static void set(String itemId, long emc) {
        EmcRegistry.setEmc(itemId, emc);
    }

    /** Remove an item from the EMC table so it can no longer be deposited or bought. */
    public static void remove(String itemId) {
        EmcRegistry.removeEmc(itemId);
    }

    /** Base EMC value of an item id, or 0 if it has none. */
    public static long get(String itemId) {
        return EmcRegistry.getEmc(itemId);
    }

    /** What the player receives when depositing one of this item (0.8x). */
    public static long deposit(String itemId) {
        return EmcRegistry.getDepositValue(EmcRegistry.getEmc(itemId));
    }

    /** What it costs to withdraw one of this item (1.2x). */
    public static long withdraw(String itemId) {
        return EmcRegistry.getWithdrawCost(EmcRegistry.getEmc(itemId));
    }

    /** True when the item id has an EMC value defined. */
    public static boolean has(String itemId) {
        return EmcRegistry.hasEmc(itemId);
    }

    /**
     * Set an EMC value using an ItemStack-like lookup by registry name.
     * Returns the resolved item id, or {@code null} when the id is unknown.
     */
    public static String setById(String itemId, long emc) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        if (item == null) {
            return null;
        }
        ResourceLocation resolved = BuiltInRegistries.ITEM.getKey(item);
        EmcRegistry.setEmc(resolved.toString(), emc);
        return resolved.toString();
    }

    /** Number of items that currently have an EMC value. */
    public static int size() {
        return EmcRegistry.getAllEmcValues().size();
    }

    /**
     * Convenience helper so scripts can price whatever the player is holding.
     * Returns 0 when the stack is empty or unpriced.
     */
    public static long depositOfStack(ItemStack stack) {
        return EmcRegistry.getDepositValue(stack);
    }

    /** Convenience helper mirroring {@link #depositOfStack} for withdrawals. */
    public static long withdrawOfStack(ItemStack stack) {
        return EmcRegistry.getWithdrawCost(stack);
    }
}
