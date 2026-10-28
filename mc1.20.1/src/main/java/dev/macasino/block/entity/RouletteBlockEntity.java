package dev.macasino.block.entity;

import com.google.gson.JsonObject;
import dev.macasino.Casino;
import dev.macasino.economy.CasinoAccountManager;
import dev.macasino.game.RouletteEngine;
import dev.macasino.game.RouletteTable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Server-side bridge between the roulette screen and the {@link RouletteTable} model.
 *
 * <p>The split of responsibility is the one baccarat and blackjack already use, so the three
 * read as one design: {@link RouletteTable} owns every rule (the felt, the draw, the payout
 * table) and it owns the animation clock — it decides when the window shuts, how far the
 * wheel has turned and when the number goes up. This class owns nothing but the edge: it
 * translates network verbs such as {@code BET} or {@code SPIN} into table calls, it moves
 * chips through {@link CasinoAccountManager} whenever a verb costs or returns a stake, and it
 * pushes the serialized table to every viewer on the beats that matter.
 *
 * <p>Payouts are pulled from {@link RouletteTable#drainPayouts()} in {@link #serverTick} and
 * credited there, so a round's winnings are paid exactly once no matter how often the block
 * entity is ticked or how many players are watching.
 */
public class RouletteBlockEntity extends BaseCasinoBlockEntity {
    /** Heartbeat period in ticks: 20 tps / 4 = 5 state packets per second per viewer. */
    private static final int HEARTBEAT_TICKS = 4;
    /**
     * Floor between two change-driven broadcasts. The wheel turns 30+ degrees in its first
     * ticks, so a per-tick "the angle moved" packet would be 20 a second per viewer just to
     * draw a rotation every client can already work out from the clock.
     */
    private static final int MIN_SYNC_GAP = 2;
    /** Degrees per wheel bucket, for the change detector. */
    private static final double WHEEL_BUCKET_DEGREES = 6.0;

    private final RouletteTable table = new RouletteTable();

    private int heartbeatTicks;
    private int sinceSync;
    private RouletteTable.Phase lastPhase = RouletteTable.Phase.WAITING_FOR_PLAYERS;
    private int lastWheelBucket = -1;
    private int lastStateToken;

    public RouletteBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.ROULETTE_BE_TYPE, pos, state);
    }

    @Override
    public String getGameType() {
        return "ROULETTE";
    }

    public RouletteTable getTable() {
        return table;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RouletteBlockEntity be) {
        if (level.isClientSide()) {
            return;
        }
        // Clean up disconnected players from viewers
        for (ServerPlayer p : new ArrayList<>(be.viewers)) {
            if (p.isRemoved()) {
                be.cleanupPlayer(p);
            }
        }

        be.table.tickServer();

        // The table queues what the house owes; draining here (and nowhere else) is what
        // makes a round pay out once. The map is emptied by the drain, so a second call in
        // the same round sees nothing.
        Map<UUID, Long> owed = be.table.drainPayouts();
        for (Map.Entry<UUID, Long> e : owed.entrySet()) {
            CasinoAccountManager.addBalance(e.getKey(), e.getValue());
        }

        be.syncIfChanged();
    }

    /**
     * Broadcasts on meaningful beats only: a phase change, the wheel crossing another
     * {@value #WHEEL_BUCKET_DEGREES}-degree sector, anything the seats or the felt changed,
     * or at least once every {@value #HEARTBEAT_TICKS} ticks.
     */
    private void syncIfChanged() {
        sinceSync++;
        RouletteTable.Phase phase = table.getPhase();
        int bucket = (int) (table.getWheelAngleDegrees() / WHEEL_BUCKET_DEGREES);
        int token = table.stateToken();

        boolean changed = phase != lastPhase || bucket != lastWheelBucket || token != lastStateToken;
        lastPhase = phase;
        lastWheelBucket = bucket;
        lastStateToken = token;

        boolean heartbeat = ++heartbeatTicks >= HEARTBEAT_TICKS;
        if (heartbeat) {
            heartbeatTicks = 0;
        }
        if ((changed && sinceSync >= MIN_SYNC_GAP) || heartbeat) {
            sinceSync = 0;
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
        // A seat left open behind a closed screen would keep a phantom bettor at the table
        // and, mid-spin, would hold a committed stake for a player who is no longer there.
        refundBets(player.getUUID());
        table.leave(player.getUUID());
    }

    @Override
    protected boolean handlesAction(String action) {
        return switch (action) {
            case "JOIN", "LEAVE", "BET", "CLEAR", "UNDO", "SPIN", "TOGGLE_BOTS" -> true;
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
                    Casino.LOGGER.warn("roulette action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "LEAVE" -> {
                try {
                    // Refund whatever is still refundable, then give up the seat. Chips on a
                    // felt that is already spinning are committed and stay with the house.
                    refundBets(uuid);
                    table.leave(uuid);
                    broadcastSync();
                    syncTo(player);
                } catch (Exception e) {
                    Casino.LOGGER.warn("roulette action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "BET" -> {
                try {
                    JsonObject json = GSON.fromJson(data, JsonObject.class);
                    RouletteEngine.BetType type = RouletteEngine.BetType.valueOf(json.get("type").getAsString());
                    int number = json.has("number") ? json.get("number").getAsInt() : 0;
                    long amount = json.get("amount").getAsLong();
                    if (table.seatOf(uuid) < 0) {
                        return;
                    }
                    if (amount < table.getMinBet() || amount > table.getMaxBet()) {
                        return;
                    }
                    int target = type == RouletteEngine.BetType.STRAIGHT ? number : 0;

                    // Wallet order is deliberate: validate affordability before any balance
                    // moves, then swap old stake for new in one clean step. If anything fails
                    // after the deduct, the felt is rolled back to exactly its prior layout.
                    long previous = table.stakeOn(uuid, type, target);
                    long delta = amount - previous;

                    if (delta > 0 && !CasinoAccountManager.deductBalance(uuid, delta)) {
                        // Not enough chips for the increase: nothing changes on the felt.
                        return;
                    }
                    if (previous > 0) {
                        CasinoAccountManager.addBalance(uuid, previous);
                    }
                    if (!table.placeBet(uuid, type, target, amount)) {
                        // The table refused (window shut, or the layout is full): unwind the
                        // swap so the felt is byte-identical to before this packet.
                        CasinoAccountManager.addBalance(uuid, amount);
                        if (previous > 0) {
                            CasinoAccountManager.deductBalance(uuid, previous);
                        }
                        return;
                    }
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("roulette action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "CLEAR" -> {
                try {
                    long refund = table.clearBets(uuid);
                    if (refund > 0) {
                        CasinoAccountManager.addBalance(uuid, refund);
                    }
                    broadcastSync();
                    syncTo(player);
                } catch (Exception e) {
                    Casino.LOGGER.warn("roulette action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "UNDO" -> {
                try {
                    long refund = table.undoLastBet(uuid);
                    if (refund > 0) {
                        CasinoAccountManager.addBalance(uuid, refund);
                    }
                    broadcastSync();
                    syncTo(player);
                } catch (Exception e) {
                    Casino.LOGGER.warn("roulette action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "SPIN" -> {
                try {
                    table.requestSpin(uuid);
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("roulette action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "TOGGLE_BOTS" -> {
                try {
                    table.toggleAutoFillBots();
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("roulette action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            default -> {
            }
        }
    }

    /** Credits back every chip a seat still has that the house may legally return. */
    private void refundBets(UUID uuid) {
        long refund = table.clearBets(uuid);
        if (refund > 0) {
            CasinoAccountManager.addBalance(uuid, refund);
        }
    }

    /**
     * Test hook: pins the wheel to one number. Not reachable from a client verb — no action
     * maps to it — so it exists only for the JVM harness and for a server operator's console.
     */
    public void forceWinningNumber(int number) {
        table.forceWinningNumber(number);
    }
}
