package dev.macasino.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import dev.macasino.api.PriceTable;
import dev.macasino.economy.CasinoAccountManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import dev.macasino.economy.Exchange;
import dev.macasino.economy.Wallet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class CasinoCommand {
    /** Tab-completes every item id that currently has a base price. */
    private static final SuggestionProvider<CommandSourceStack> ITEM_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    PriceTable.getAllPrices().keySet(), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("macasino")
                .then(Commands.literal("balance")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            long bal = CasinoAccountManager.getBalance(player.getUUID());
                            ctx.getSource().sendSuccess(() -> Component.literal("§6[Casino] §fBalance: §e" + bal + " " + Wallet.CURRENCY), false);
                            return 1;
                        })
                        .then(Commands.argument("target", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                    long bal = CasinoAccountManager.getBalance(target.getUUID());
                                    ctx.getSource().sendSuccess(() -> Component.literal("§6[Casino] §e" + target.getName().getString() + "§f's balance: §e" + bal + " " + Wallet.CURRENCY), false);
                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("give")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            long amount = LongArgumentType.getLong(ctx, "amount");
                                            CasinoAccountManager.addBalance(target.getUUID(), amount);
                                            long newBal = CasinoAccountManager.getBalance(target.getUUID());
                                            ctx.getSource().sendSuccess(() -> Component.literal("§6[Casino] §aAdded §e" + amount + " " + Wallet.CURRENCY + " §ato " + target.getName().getString() + " (New: " + newBal + ")"), true);
                                            target.sendSystemMessage(Component.literal("§6[Casino] §aYou received §e" + amount + " " + Wallet.CURRENCY + "§a!"));
                                            return 1;
                                        })
                                )
                        )
                )
                .then(Commands.literal("setprice")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                .suggests(ITEM_SUGGESTIONS)
                                .then(Commands.argument("value", LongArgumentType.longArg(0))
                                        .executes(ctx -> {
                                            String item = ResourceLocationArgument.getId(ctx, "item").toString();
                                            long val = LongArgumentType.getLong(ctx, "value");
                                            PriceTable.setPrice(item, val);
                                            ctx.getSource().sendSuccess(() -> Component.literal("§6[Casino] §fSet base price of §b" + item + " §fto §e" + val), true);
                                            return 1;
                                        })
                                )
                        )
                )
                .then(Commands.literal("price")
                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                .suggests(ITEM_SUGGESTIONS)
                                .executes(ctx -> {
                                    String item = ResourceLocationArgument.getId(ctx, "item").toString();
                                    long base = PriceTable.getPrice(item);
                                    long dep = PriceTable.sellPrice(base);
                                    long with = PriceTable.buyPrice(base);
                                    ctx.getSource().sendSuccess(() -> Component.literal("§6[Casino] §b" + item + "§f: base §e" + base + "§7 | §fhouse buys §a" + dep + "§7 | §fhouse sells §c" + with), false);
                                    return 1;
                                })
                        )
                )

                // ---- wallet administration ----------------------------------
                .then(Commands.literal("wallet")
                        .then(Commands.literal("set")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                                .executes(ctx -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                                    long amount = LongArgumentType.getLong(ctx, "amount");
                                                    Wallet.setBalance(target.getUUID(), amount);
                                                    long now = Wallet.getBalance(target.getUUID());
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "\u00a76[Casino] \u00a7fSet \u00a7e" + target.getName().getString()
                                                                    + "\u00a7f's balance to \u00a7e" + now + " " + Wallet.CURRENCY), true);
                                                    return 1;
                                                })
                                        )
                                )
                        )
                        .then(Commands.literal("take")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                                .executes(ctx -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                                    long amount = LongArgumentType.getLong(ctx, "amount");
                                                    boolean ok = Wallet.withdraw(target.getUUID(), amount);
                                                    long now = Wallet.getBalance(target.getUUID());
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            ok ? "\u00a76[Casino] \u00a7aTook \u00a7e" + amount + " " + Wallet.CURRENCY
                                                                    + "\u00a7a, balance now \u00a7e" + now
                                                            : "\u00a76[Casino] \u00a7cNot enough " + Wallet.CURRENCY
                                                                    + " (has \u00a7e" + now + "\u00a7c)"), true);
                                                    return 1;
                                                })
                                        )
                                )
                        )
                        .then(Commands.literal("reset")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            Wallet.clear(target.getUUID());
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "\u00a76[Casino] \u00a7fCleared \u00a7e" + target.getName().getString()
                                                            + "\u00a7f's wallet"), true);
                                            return 1;
                                        })
                                )
                        )
                )

                // ---- exchange: sell / buy against the house ------------------
                .then(Commands.literal("sell")
                        .then(Commands.literal("hand")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    ItemStack hand = player.getMainHandItem();
                                    if (!Exchange.isTradable(hand)) {
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "\u00a76[Casino] \u00a7cThat item has no price \u2014 the house will not buy it"), false);
                                        return 0;
                                    }
                                    int n = hand.getCount();
                                    long earned = Exchange.sellStack(player, hand, n);
                                    long now = Wallet.getBalance(player.getUUID());
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "\u00a76[Casino] \u00a7aSold \u00a7e" + n + "\u00a7a for \u00a7e"
                                                    + earned + " " + Wallet.CURRENCY + "\u00a7a (balance \u00a7e" + now + "\u00a7a)"), false);
                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("buy")
                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                .suggests(ITEM_SUGGESTIONS)
                                .executes(ctx -> buy(ctx, 1))
                                .then(Commands.argument("count", LongArgumentType.longArg(1, 2304))
                                        .executes(ctx -> buy(ctx, (int) LongArgumentType.getLong(ctx, "count")))
                                )
                        )
                )
        );
    }

    /** Shared body for {@code /casino buy <item> [count]}. */
    private static int buy(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, int count) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            String itemId = ResourceLocationArgument.getId(ctx, "item").toString();
            Item item = Exchange.resolve(itemId);
            if (item == null) {
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "\u00a76[Casino] \u00a7cThere is no item called \u00a7e" + itemId), false);
                return 0;
            }
            long unit = Exchange.unitBuyPrice(item);
            if (unit <= 0) {
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "\u00a76[Casino] \u00a7cThe house does not sell \u00a7e" + itemId), false);
                return 0;
            }
            long total = unit * count;
            long balance = Wallet.getBalance(player.getUUID());
            if (balance < total) {
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "\u00a76[Casino] \u00a7cThat costs \u00a7e" + total + " " + Wallet.CURRENCY
                                + "\u00a7c, you have \u00a7e" + balance), false);
                return 0;
            }
            if (!Exchange.buyFor(player, item, count)) {
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "\u00a76[Casino] \u00a7cNo room in your inventory"), false);
                return 0;
            }
            long now = Wallet.getBalance(player.getUUID());
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "\u00a76[Casino] \u00a7aBought \u00a7e" + count + "x " + itemId
                            + "\u00a7a for \u00a7e" + total + " " + Wallet.CURRENCY
                            + "\u00a7a (balance \u00a7e" + now + "\u00a7a)"), false);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }
}
