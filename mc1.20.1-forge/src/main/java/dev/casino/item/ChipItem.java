package dev.casino.item;

import dev.casino.economy.Wallet;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A physical chip: right-click to cash it into your wallet.
 *
 * <p>The tangible form of money, for servers that want to trade chips as items. The scrip
 * is worth its face only once cashed in — while it sits in an inventory it is just an
 * item, which is what lets it be dropped, traded or stored in a chest like anything else.
 */
public class ChipItem extends Item {

    /** Face value of a single chip. */
    public static final long FACE_VALUE = 100L;

    public ChipItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }
        int n = stack.getCount();
        long value = FACE_VALUE * n;
        stack.setCount(0);
        Wallet.deposit(player.getUUID(), value);
        player.sendSystemMessage(Component.literal(
                "\u00a76[Casino] \u00a7aCashed in \u00a7e" + n + " chip(s) \u00a7afor \u00a7e"
                        + value + " " + Wallet.CURRENCY
                        + "\u00a7a (balance \u00a7e" + Wallet.getBalance(player.getUUID()) + "\u00a7a)"));
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("\u00a77Face value: \u00a7e" + FACE_VALUE + " " + Wallet.CURRENCY + " each"));
        lines.add(Component.literal("\u00a78Right-click to cash in"));
    }
}
