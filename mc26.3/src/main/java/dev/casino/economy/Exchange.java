package dev.casino.economy;

import dev.casino.api.PriceTable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The house exchange: where the player's items become money and money becomes items.
 *
 * <p>Two rates, both derived from the same base price so the house always wins by a fixed
 * margin:
 * <ul>
 *   <li><b>Selling</b> an item pays {@code floor(base * 0.8)} — the player gets less than it is worth.</li>
 *   <li><b>Buying</b> an item costs {@code ceil(base * 1.2)} — the player pays more than it is worth.</li>
 * </ul>
 * Selling then immediately buying back the same item always loses money, and buying then
 * selling back always loses money, at every base value. That asymmetry is the only reason
 * this is a game and not an infinite money printer.
 *
 * <p>This class holds the rules only. It touches no GUI and no network, so the same logic
 * backs the block GUI, the command and any future automation.
 */
public final class Exchange {

    private Exchange() {
    }

    /** What the player receives for selling one unit of this stack. */
    public static long sellValue(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0L;
        }
        long base = PriceTable.getPrice(stack);
        if (base <= 0) {
            return 0L;
        }
        return PriceTable.sellPrice(base) * stack.getCount();
    }

    /** What one unit of this item costs to buy. */
    public static long unitBuyPrice(Item item) {
        if (item == null) {
            return 0L;
        }
        long base = PriceTable.getPrice(new ItemStack(item));
        return base <= 0 ? 0L : PriceTable.buyPrice(base);
    }

    /** True if the item can be traded at all. */
    public static boolean isTradable(ItemStack stack) {
        return !stack.isEmpty() && PriceTable.getPrice(stack) > 0;
    }

    /** Resolve {@code "minecraft:diamond"} to an Item, or null. */
    public static Item resolve(String id) {
        Identifier rl = Identifier.tryParse(id);
        if (rl == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getValue(rl);
    }

    /**
     * Sells from an inventory slot index.
     *
     * @return the money credited
     */
    public static long sellFromSlot(net.minecraft.world.entity.player.Player player,
                                    int slot, int count) {
        if (player == null) {
            return 0L;
        }
        return sellStack(player, player.getInventory().getItem(slot), count);
    }

    /**
     * Sells units from an already-obtained stack, regardless of where it lives.
     *
     * <p>This is the version-portable entry point: 26.x and 1.20.1 differ in how you ask
     * the inventory which slot is selected, but they agree on {@code ItemStack} mutation,
     * so all the real work happens here.
     *
     * @return the money credited
     */
    public static long sellStack(net.minecraft.world.entity.player.Player player,
                                 ItemStack stack, int count) {
        if (player == null || stack == null || count <= 0 || !isTradable(stack)) {
            return 0L;
        }
        int sold = Math.min(count, stack.getCount());
        long unitSell = PriceTable.sellPrice(PriceTable.getPrice(stack));
        long credit = unitSell * sold;
        if (credit <= 0) {
            return 0L;
        }
        stack.shrink(sold);
        Wallet.deposit(player.getUUID(), credit);
        return credit;
    }

    /**
     * Buys {@code count} units of an item, if the player can afford it and there is room.
     *
     * <p>The money is taken first and refunded if the items cannot be placed, so a full
     * inventory cannot swallow the payment.
     *
     * @return true if the purchase completed
     */
    public static boolean buyFor(net.minecraft.world.entity.player.Player player,
                                 Item item, int count) {
        if (player == null || item == null || count <= 0) {
            return false;
        }
        long unit = unitBuyPrice(item);
        if (unit <= 0) {
            return false;
        }
        long total = unit * count;
        if (!Wallet.withdraw(player.getUUID(), total)) {
            return false;
        }

        ItemStack bought = new ItemStack(item, count);
        if (!player.getInventory().add(bought)) {
            // no room: put the money back and report failure
            Wallet.deposit(player.getUUID(), total);
            return false;
        }
        return true;
    }
}
