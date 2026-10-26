package dev.casino.block.entity;

import com.google.gson.JsonObject;
import dev.casino.Casino;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.game.PinballEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pinball machine block entity.
 *
 * <p>Unlike the other games, a pinball ball spans many player actions: launch, a series of
 * flipper inputs, then drain. The authoritative per-player run therefore lives here, in a
 * {@link PinballRun}, rather than being recomputed from a single action.
 *
 * <p>The stake is taken at launch and the payout is granted once, at drain, so a player
 * cannot abandon a losing ball to avoid paying for it and cannot double-collect a winning
 * one by sending DRAIN twice.
 */
public class PinballBlockEntity extends BaseCasinoBlockEntity {

    /** One in-progress ball. Mutable on purpose: the server thread owns it. */
    private static final class PinballRun {
        final PinballEngine engine;
        final long bet;

        PinballRun(PinballEngine engine, long bet) {
            this.engine = engine;
            this.bet = bet;
        }
    }

    private final Map<UUID, PinballRun> runs = new ConcurrentHashMap<>();
    private final Map<UUID, PinballEngine.BallResult> lastResults = new ConcurrentHashMap<>();

    public PinballBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.PINBALL_BE_TYPE.get(), pos, state);
    }

    @Override
    public String getGameType() {
        return "PINBALL";
    }

    @Override
    public String serializeState(ServerPlayer player) {
        JsonObject obj = new JsonObject();

        PinballRun run = runs.get(player.getUUID());
        PinballEngine.BallResult last = lastResults.get(player.getUUID());

        if (run != null) {
            obj.addProperty("state", run.engine.state().name());
            obj.addProperty("bet", run.bet);
            obj.addProperty("bounce", run.engine.bounce());
            obj.addProperty("bumpers", run.engine.bumpersHit());
            obj.addProperty("score", run.engine.score());
            obj.addProperty("inPlay", true);
        } else {
            obj.addProperty("state", "READY");
            obj.addProperty("inPlay", false);
            obj.addProperty("bet", 0);
            obj.addProperty("bounce", 0);
            obj.addProperty("bumpers", 0);
            obj.addProperty("score", 0);
        }

        if (last != null) {
            obj.addProperty("lastPayout", last.payout);
            obj.addProperty("lastBumpers", last.bumpersHit);
            obj.addProperty("lastScore", last.score);
        } else {
            obj.addProperty("lastPayout", 0);
            obj.addProperty("lastBumpers", 0);
            obj.addProperty("lastScore", 0);
        }

        // Surface the tuned return so the player can see the odds they are playing against.
        obj.addProperty("rtpSkilled", PinballEngine.expectedReturnSkilled());
        obj.addProperty("rtpCasual", PinballEngine.expectedReturnUnskilled());
        return GSON.toJson(obj);
    }

    @Override
    protected void handleGameAction(ServerPlayer player, String action, String data) {
        UUID id = player.getUUID();

        switch (action.toUpperCase()) {
            case "PINBALL_LAUNCH" -> launch(player, id, data);
            case "PINBALL_FLIP" -> flip(player, id, data);
            case "PINBALL_DRAIN" -> drain(player, id);
            default -> { /* unknown action: ignore rather than throw */ }
        }
    }

    private void launch(ServerPlayer player, UUID id, String data) {
        if (runs.containsKey(id)) {
            // Already has a ball in play: refuse, so a launch cannot overwrite a live run
            // (which would let a player escape a bad ball without losing the stake).
            return;
        }
        long bet;
        try {
            JsonObject json = GSON.fromJson(data, JsonObject.class);
            bet = json.get("bet").getAsLong();
        } catch (Exception e) {
            return;
        }
        if (bet <= 0) return;
        if (!CasinoAccountManager.deductBalance(id, bet)) return;

        PinballEngine engine = new PinballEngine();
        engine.launch(bet);
        runs.put(id, new PinballRun(engine, bet));
        syncTo(player);
    }

    private void flip(ServerPlayer player, UUID id, String data) {
        PinballRun run = runs.get(id);
        if (run == null) return;

        double power;
        try {
            JsonObject json = GSON.fromJson(data, JsonObject.class);
            power = json.has("power") ? json.get("power").getAsDouble() : 0.5;
        } catch (Exception e) {
            power = 0.5;
        }

        boolean alive = run.engine.tick(power);
        if (!alive) {
            settle(player, id, run);
        } else {
            syncTo(player);
        }
    }

    private void drain(ServerPlayer player, UUID id) {
        PinballRun run = runs.remove(id);
        if (run == null) return;
        settle(player, id, run);
    }

    /**
     * Pay out a finished ball exactly once. The run has already been removed from the map
     * by the caller, so a duplicate action cannot double-pay.
     */
    private void settle(ServerPlayer player, UUID id, PinballRun run) {
        PinballEngine.BallResult result = run.engine.finish();
        lastResults.put(id, result);
        if (result.payout > 0) {
            CasinoAccountManager.addBalance(id, result.payout);
        }
        syncTo(player);
    }
}
