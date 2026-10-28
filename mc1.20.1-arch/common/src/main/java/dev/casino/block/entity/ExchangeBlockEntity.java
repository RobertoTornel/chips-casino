package dev.casino.block.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.casino.Casino;
import dev.casino.api.PriceTable;
import dev.casino.economy.Exchange;
import dev.casino.economy.Wallet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * Server side of the Exchange block.
 *
 * <p>State sent to the client is deliberately a snapshot of *prices*, not of the player's
 * inventory: the client renders what things are worth, the server owns what actually
 * happens. Every mutating action re-derives the price here, so a doctored client cannot
 * ask for a better rate.
 */
public class ExchangeBlockEntity extends BaseCasinoBlockEntity {

    /** How many entries the client is shown at once. */
    private static final int SHOWCASE = 12;

    /** Actions this block entity owns outright; the base class must not intercept them. */
    private static final java.util.Set<String> EXCHANGE_ACTIONS = java.util.Set.of(
            "EXCHANGE_SELL_SLOT", "SELL_MAIN_HAND", "SELL_ALL_TRADABLE",
            "EXCHANGE_BUY", "EXCHANGE_REFRESH");

    public ExchangeBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.EXCHANGE_BE_TYPE.get(), pos, state);
    }

    @Override
    public String getGameType() {
        return "EXCHANGE";
    }

    @Override
    public String serializeState(ServerPlayer player) {
        JsonObject obj = new JsonObject();
        obj.addProperty("balance", Wallet.getBalance(player.getUUID()));
        obj.addProperty("sellMultiplier", PriceTable.SELL_MULTIPLIER);
        obj.addProperty("buyMultiplier", PriceTable.BUY_MULTIPLIER);
        obj.addProperty("currency", Wallet.CURRENCY);
        obj.addProperty("symbol", Wallet.SYMBOL);

        // A short, stable showcase of what the house trades, with both rates resolved
        // server side so the numbers on screen are the numbers that will be charged.
        JsonArray showcase = new JsonArray();
        int shown = 0;
        for (Map.Entry<String, Long> entry : PriceTable.getAllPrices().entrySet()) {
            if (shown++ >= SHOWCASE) {
                break;
            }
            long base = entry.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("id", entry.getKey());
            row.addProperty("base", base);
            row.addProperty("sell", PriceTable.sellPrice(base));
            row.addProperty("buy", PriceTable.buyPrice(base));
            showcase.add(row);
        }
        obj.add("showcase", showcase);
        return GSON.toJson(obj);
    }

    @Override
    protected boolean handlesAction(String action) {
        return EXCHANGE_ACTIONS.contains(action.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    protected void handleGameAction(ServerPlayer player, String action, String data) {
        switch (action.toUpperCase()) {
            // ---- sell the stack in a hotbar/main-inventory slot -------------------
            case "EXCHANGE_SELL_SLOT" -> {
                try {
                    JsonObject json = GSON.fromJson(data, JsonObject.class);
                    int slot = json.get("slot").getAsInt();
                    int count = json.has("count") ? json.get("count").getAsInt() : 1;
                    Exchange.sellFromSlot(player, slot, count);
                } catch (Exception ignored) {
                    // a malformed payload must not take the block entity down
                    player.sendSystemMessage(Component.literal("§6[Casino] §cInvalid request — the exchange could not process that action"));
                }
                broadcastSync();
            }

            // ---- sell whatever is in the main hand --------------------------------
            case "SELL_MAIN_HAND" -> {
                ItemStack hand = player.getMainHandItem();
                Exchange.sellStack(player, hand, hand.getCount());
                broadcastSync();
            }

            // ---- buy a specific item ----------------------------------------------
                        // ---- sell every tradable stack the player is carrying ----------------
            //
            // Walks the inventory once and sells each stack in place. Doing it here rather
            // than in the GUI keeps the client honest: it only ever asks for the operation,
            // never names the items or the amounts.
            case "SELL_ALL_TRADABLE" -> {
                var inv = player.getInventory();
                for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                    net.minecraft.world.item.ItemStack st = inv.getItem(slot);
                    if (dev.casino.economy.Exchange.isTradable(st)) {
                        dev.casino.economy.Exchange.sellStack(player, st, st.getCount());
                    }
                }
                broadcastSync();
            }

            // Just re-push the showcase; the screen asked for a redraw after a trade.
            case "EXCHANGE_REFRESH" -> broadcastSync();

            case "EXCHANGE_BUY" -> {
                try {
                    JsonObject json = GSON.fromJson(data, JsonObject.class);
                    ResourceLocation rl = ResourceLocation.tryParse(json.get("itemId").getAsString());
                    int count = json.has("count") ? json.get("count").getAsInt() : 1;
                    if (rl != null) {
                        Item item = BuiltInRegistries.ITEM.get(rl);
                        Exchange.buyFor(player, item, count);
                    }
                } catch (Exception ignored) {
                    player.sendSystemMessage(Component.literal("§6[Casino] §cInvalid request — the exchange could not process that action"));
                }
                broadcastSync();
            }

            default -> {
                // not an exchange action
            }
        }
    }
}
