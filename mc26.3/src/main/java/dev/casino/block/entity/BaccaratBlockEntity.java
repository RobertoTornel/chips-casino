package dev.casino.block.entity;

import com.google.gson.JsonObject;
import dev.casino.Casino;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.game.BaccaratEngine;
import dev.casino.game.BaccaratTable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.UUID;

/**
 * Server-side bridge between the baccarat screen and the {@link BaccaratTable} model.
 *
 * <p>The split of responsibility is the same one the blackjack table uses, so the two
 * read as one design: {@link BaccaratTable} owns every rule (the shoe, the naturals, the
 * banker's third-card table, the commission, settling) and it owns the animation clock —
 * it decides when the next card lands and when the settled hats come down. This class
 * owns nothing but the edge: it translates network verbs such as {@code BET} or
 * {@code DEAL} into table calls, it moves chips through {@link CasinoAccountManager}
 * whenever a verb costs or returns a stake, and it pushes the serialized table state to
 * every viewer on the beats that matter.
 *
 * <p>Payouts are pulled from {@link BaccaratTable#drainPayouts()} in {@link #serverTick}
 * and credited there, so a round's winnings are paid exactly once no matter how often the
 * block entity is ticked or how many players are watching.
 */
public class BaccaratBlockEntity extends BaseCasinoBlockEntity {
    /** Heartbeat period in ticks: 20 tps / 4 = 5 state packets per second per viewer. */
    private static final int HEARTBEAT_TICKS = 4;

    private final BaccaratTable table = new BaccaratTable();

    private int heartbeatTicks;
    private BaccaratTable.Phase lastPhase = BaccaratTable.Phase.WAITING_FOR_PLAYERS;
    private int lastPlayerCards = -1;
    private int lastBankerCards = -1;

    public BaccaratBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.BACCARAT_BE_TYPE, pos, state);
    }

    @Override
    public String getGameType() {
        return "BACCARAT";
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BaccaratBlockEntity be) {
        if (level.isClientSide()) {
            return;
        }
        be.table.tickServer();

        // The table queues what the house owes; draining here (and nowhere else) is what
        // makes a round pay out once. The map is emptied by the drain, so a second call
        // in the same round sees nothing.
        Map<UUID, Long> owed = be.table.drainPayouts();
        for (Map.Entry<UUID, Long> e : owed.entrySet()) {
            CasinoAccountManager.addBalance(e.getKey(), e.getValue());
        }

        be.syncIfChanged();
    }

    /**
     * Broadcasts on meaningful beats only: phase change, either hand gaining a card, or at
     * least once every {@value #HEARTBEAT_TICKS} ticks. Sending the full table state every
     * tick would be 20 packets per second per viewer.
     */
    private void syncIfChanged() {
        BaccaratTable.Phase phase = table.getPhase();
        int playerCards = table.getPlayerHand().size();
        int bankerCards = table.getBankerHand().size();

        boolean changed = phase != lastPhase || playerCards != lastPlayerCards || bankerCards != lastBankerCards;
        lastPhase = phase;
        lastPlayerCards = playerCards;
        lastBankerCards = bankerCards;

        if (++heartbeatTicks >= HEARTBEAT_TICKS) {
            heartbeatTicks = 0;
            changed = true;
        }
        if (changed) {
            broadcastSync();
        }
    }

    @Override
    public String serializeState(ServerPlayer player) {
        return table.toJson(player.getUUID()).toString();
    }

    @Override
    public void addViewer(ServerPlayer player) {
        super.addViewer(player);
        UUID uuid = player.getUUID();
        if (table.seatOf(uuid) < 0) {
            table.join(uuid, player.getName().getString());
            // The super call synced before the seat existed, so send the seated view too.
            syncTo(player);
        }
    }

    @Override
    public void removeViewer(ServerPlayer player) {
        super.removeViewer(player);
        table.leave(player.getUUID());
    }

    @Override
    protected boolean handlesAction(String action) {
        return switch (action) {
            case "JOIN", "LEAVE", "BET", "DEAL", "TOGGLE_BOTS" -> true;
            default -> false;
        };
    }

    @Override
    protected void handleGameAction(ServerPlayer player, String action, String data) {
        UUID uuid = player.getUUID();
        switch (action) {
            case "JOIN" -> {
                try {
                    table.join(uuid, player.getName().getString());
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("baccarat action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "LEAVE" -> {
                try {
                    table.leave(uuid);
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("baccarat action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "BET" -> {
                try {
                    JsonObject payload = GSON.fromJson(data, JsonObject.class);
                    long amount = payload.get("bet").getAsLong();
                    BaccaratEngine.BetTarget target = BaccaratEngine.BetTarget.valueOf(payload.get("target").getAsString());

                    int seat = table.seatOf(uuid);
                    if (seat < 0 || amount < table.getMinBet() || amount > table.getMaxBet()) {
                        return;
                    }
                    long previous = table.getSeats()[seat].bet;

                    // Wallet order is deliberate, and it is the same order blackjack uses.
                    // Validate first, so a rejected stake never touches the wallet. Then
                    // refund the stake already on the felt — a player is allowed to change
                    // their amount or their line before the shoe deals — and only then
                    // charge the new one. If the charge fails, put the old stake back so a
                    // player who cannot cover the raise stays exactly where they were.
                    CasinoAccountManager.addBalance(uuid, previous);
                    if (!CasinoAccountManager.deductBalance(uuid, amount)) {
                        CasinoAccountManager.deductBalance(uuid, previous);
                        return;
                    }
                    if (!table.placeBet(uuid, target, amount)) {
                        // The table refused it after all — the betting window closed between
                        // the click and the tick. Unwind the swap both ways.
                        CasinoAccountManager.addBalance(uuid, amount);
                        CasinoAccountManager.deductBalance(uuid, previous);
                    }
                } catch (Exception e) {
                    Casino.LOGGER.warn("baccarat action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "DEAL" -> {
                try {
                    table.requestDeal();
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("baccarat action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "TOGGLE_BOTS" -> {
                try {
                    table.toggleAutoFillBots();
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("baccarat action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            default -> {
            }
        }
    }
}
