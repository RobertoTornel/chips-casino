package dev.casino.block.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.casino.Casino;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.game.RouletteEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class RouletteBlockEntity extends BaseCasinoBlockEntity {
    private final RouletteEngine engine = new RouletteEngine();
    private RouletteEngine.SpinResult lastResult = null;

    public RouletteBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.ROULETTE_BE_TYPE.get(), pos, state);
    }

    @Override
    public String getGameType() {
        return "ROULETTE";
    }

    @Override
    public String serializeState(ServerPlayer player) {
        JsonObject obj = new JsonObject();
        if (lastResult != null) {
            obj.addProperty("winningNumber", lastResult.winningNumber);
            obj.addProperty("isRed", lastResult.isRed);
            obj.addProperty("isBlack", lastResult.isBlack);
            obj.addProperty("isGreen", lastResult.isGreen);
            obj.addProperty("lastWon", lastResult.totalWon);
        } else {
            obj.addProperty("winningNumber", -1);
        }
        return GSON.toJson(obj);
    }

    @Override
    protected void handleGameAction(ServerPlayer player, String action, String data) {
        if ("SPIN".equalsIgnoreCase(action)) {
            try {
                JsonObject json = GSON.fromJson(data, JsonObject.class);
                JsonArray betsArray = json.getAsJsonArray("bets");
                List<RouletteEngine.Bet> bets = new ArrayList<>();
                long totalBet = 0;

                for (JsonElement el : betsArray) {
                    JsonObject b = el.getAsJsonObject();
                    RouletteEngine.BetType type = RouletteEngine.BetType.valueOf(b.get("type").getAsString());
                    int num = b.has("number") ? b.get("number").getAsInt() : 0;
                    long amt = b.get("amount").getAsLong();
                    if (amt > 0) {
                        bets.add(new RouletteEngine.Bet(type, num, amt));
                        totalBet += amt;
                    }
                }

                if (totalBet > 0 && CasinoAccountManager.deductBalance(player.getUUID(), totalBet)) {
                    lastResult = engine.spin(bets);
                    if (lastResult.totalWon > 0) {
                        CasinoAccountManager.addBalance(player.getUUID(), lastResult.totalWon);
                    }
                    broadcastSync();
                }
            } catch (Exception ignored) {}
        }
    }
}
