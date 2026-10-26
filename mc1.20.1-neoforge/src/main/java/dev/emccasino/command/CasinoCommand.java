package dev.emccasino.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.emccasino.api.EmcRegistry;
import dev.emccasino.economy.CasinoAccountManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class CasinoCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("casino")
                .then(Commands.literal("balance")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            long bal = CasinoAccountManager.getBalance(player.getUUID());
                            ctx.getSource().sendSuccess(() -> Component.literal("§6[Casino] §fYour EMC Casino balance: §e" + bal + " EMC"), false);
                            return 1;
                        })
                        .then(Commands.argument("target", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                    long bal = CasinoAccountManager.getBalance(target.getUUID());
                                    ctx.getSource().sendSuccess(() -> Component.literal("§6[Casino] §e" + target.getName().getString() + "§f's balance: §e" + bal + " EMC"), false);
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
                                            ctx.getSource().sendSuccess(() -> Component.literal("§6[Casino] §aAdded §e" + amount + " EMC §ato " + target.getName().getString() + " (New: " + newBal + ")"), true);
                                            target.sendSystemMessage(Component.literal("§6[Casino] §aYou received §e" + amount + " EMC§a!"));
                                            return 1;
                                        })
                                )
                        )
                )
                .then(Commands.literal("setemc")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("item", StringArgumentType.string())
                                .then(Commands.argument("value", LongArgumentType.longArg(0))
                                        .executes(ctx -> {
                                            String item = StringArgumentType.getString(ctx, "item");
                                            long val = LongArgumentType.getLong(ctx, "value");
                                            EmcRegistry.setEmc(item, val);
                                            ctx.getSource().sendSuccess(() -> Component.literal("§6[Casino] §fSet EMC for §b" + item + " §fto §e" + val + " EMC"), true);
                                            return 1;
                                        })
                                )
                        )
                )
                .then(Commands.literal("getemc")
                        .then(Commands.argument("item", StringArgumentType.string())
                                .executes(ctx -> {
                                    String item = StringArgumentType.getString(ctx, "item");
                                    long base = EmcRegistry.getEmc(item);
                                    long dep = EmcRegistry.getDepositValue(base);
                                    long with = EmcRegistry.getWithdrawCost(base);
                                    ctx.getSource().sendSuccess(() -> Component.literal("§6[Casino] §b" + item + "§f: Base=§e" + base + "§f, Deposit(0.8x)=§a" + dep + "§f, Withdraw(1.2x)=§c" + with), false);
                                    return 1;
                                })
                        )
                )
        );
    }
}
