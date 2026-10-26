package dev.emccasino.block.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.emccasino.EmcCasino;
import dev.emccasino.economy.CasinoAccountManager;
import dev.emccasino.game.BaccaratEngine;
import dev.emccasino.game.Card;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

public class BaccaratBlockEntity extends BaseCasinoBlockEntity {
    private final BaccaratEngine engine = new BaccaratEngine();
    private BaccaratEngine.BaccaratResult lastResult = null;

    public BaccaratBlockEntity(BlockPos pos, BlockState state) {
        super(EmcCasino.BACCARAT_BE_TYPE, pos, state);
    }

    @Override
    public String getGameType() {
        return "BACCARAT";
    }

    @Override
    public String serializeState(ServerPlayer player) {
        JsonObject obj = new JsonObject();
        if (lastResult != null) {
            obj.addProperty("outcome", lastResult.outcome.name());
            obj.addProperty("playerTotal", lastResult.playerTotal);
            obj.addProperty("bankerTotal", lastResult.bankerTotal);
            obj.addProperty("payout", lastResult.payout);

            JsonArray pCards = new JsonArray();
            for (Card c : lastResult.playerCards) pCards.add(c.toShortString());
            obj.add("playerCards", pCards);

            JsonArray bCards = new JsonArray();
            for (Card c : lastResult.bankerCards) bCards.add(c.toShortString());
            obj.add("bankerCards", bCards);
        } else {
            obj.addProperty("outcome", "NONE");
        }
        return GSON.toJson(obj);
    }

    @Override
    protected void handleGameAction(ServerPlayer player, String action, String data) {
        if ("PLAY".equalsIgnoreCase(action)) {
            try {
                JsonObject json = GSON.fromJson(data, JsonObject.class);
                BaccaratEngine.BetTarget target = BaccaratEngine.BetTarget.valueOf(json.get("target").getAsString());
                long bet = json.get("bet").getAsLong();

                if (bet > 0 && CasinoAccountManager.deductBalance(player.getUUID(), bet)) {
                    lastResult = engine.play(target, bet);
                    if (lastResult.payout > 0) {
                        CasinoAccountManager.addBalance(player.getUUID(), lastResult.payout);
                    }
                    syncTo(player);
                }
            } catch (Exception ignored) {}
        }
    }
}
