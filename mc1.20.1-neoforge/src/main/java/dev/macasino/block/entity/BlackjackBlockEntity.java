package dev.macasino.block.entity;

import com.google.gson.JsonObject;
import dev.macasino.Casino;
import dev.macasino.economy.CasinoAccountManager;
import dev.macasino.game.BlackjackTable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Server-side bridge between the blackjack screen and the {@link BlackjackTable} model.
 *
 * <p>The table owns every rule, the shoe, the animation clock and the outcome of a round;
 * it also decides when money is owed. This class does nothing but (a) translate network
 * verbs such as {@code HIT} or {@code SPLIT} into table calls, (b) move chips through
 * {@link CasinoAccountManager} when a verb costs a stake, and (c) push state to viewers.
 *
 * <p>Payouts are pulled from {@link BlackjackTable#drainPayouts()} in {@link #serverTick}
 * and credited there, so each round's winnings are paid exactly once no matter how often
 * the table is ticked or how many viewers are watching.
 */
public class BlackjackBlockEntity extends BaseCasinoBlockEntity {
    /** Heartbeat period in ticks: 20 tps / 4 = 5 state packets per second per viewer. */
    private static final int HEARTBEAT_TICKS = 4;

    private final BlackjackTable table = new BlackjackTable();

    private int heartbeatTicks;
    private BlackjackTable.Phase lastPhase = BlackjackTable.Phase.WAITING_FOR_PLAYERS;
    private int lastActiveSeat = -1;
    private int lastDealerCards = -1;

    public BlackjackBlockEntity(BlockPos pos, BlockState state) {
        super(Casino.BLACKJACK_BE_TYPE.get(), pos, state);
    }

    @Override
    public String getGameType() {
        return "BLACKJACK";
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BlackjackBlockEntity be) {
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
        // makes a round pay out once. The map is emptied by the drain, so a second call
        // in the same round sees nothing.
        Map<UUID, Long> owed = be.table.drainPayouts();
        for (Map.Entry<UUID, Long> e : owed.entrySet()) {
            CasinoAccountManager.addBalance(e.getKey(), e.getValue());
        }

        be.syncIfChanged();
    }

    /**
     * Broadcasts on meaningful beats only: phase change, active seat change, a dealer card
     * arriving, or at least once every {@value #HEARTBEAT_TICKS} ticks. Sending the full
     * table state every tick would be 20 packets per second per viewer.
     */
    private void syncIfChanged() {
        BlackjackTable.Phase phase = table.getPhase();
        int activeSeat = table.getActiveSeat();
        int dealerCards = table.getDealerHand().cards.size();

        boolean changed = phase != lastPhase || activeSeat != lastActiveSeat || dealerCards != lastDealerCards;
        lastPhase = phase;
        lastActiveSeat = activeSeat;
        lastDealerCards = dealerCards;

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
        return table.toJson(player.getUUID(), table.seatOf(player.getUUID()) >= 0).toString();
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
            case "JOIN", "LEAVE", "BET", "DEAL", "HIT", "STAND", "DOUBLE", "SPLIT", "TOGGLE_BOTS" -> true;
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
                    Casino.LOGGER.warn("blackjack action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "LEAVE" -> {
                try {
                    table.leave(uuid);
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("blackjack action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "BET" -> {
                try {
                    long amount = GSON.fromJson(data, JsonObject.class).get("bet").getAsLong();
                    int seat = table.seatOf(uuid);
                    if (seat < 0) {
                        return;
                    }
                    long previous = table.getSeats()[seat].bet;

                    // Wallet order is deliberate: validate, then refund the old stake, then
                    // charge the new one. Validating first means a rejected amount never
                    // touches the balance; refunding first would let a malformed bet hand
                    // out chips and leave the seat with a bet nobody paid for.
                    if (amount < table.getMinBet() || amount > table.getMaxBet()) {
                        return;
                    }
                    if (previous > 0) {
                        CasinoAccountManager.addBalance(uuid, previous);
                    }
                    if (!CasinoAccountManager.deductBalance(uuid, amount)) {
                        // Not enough chips for the new stake: put the old stake back so the
                        // seat keeps exactly the bet it had before this packet.
                        if (previous > 0) {
                            CasinoAccountManager.deductBalance(uuid, previous);
                        }
                        return;
                    }
                    if (!table.placeBet(uuid, amount)) {
                        // The table refused (round already running): return the new chips
                        // and re-charge the old stake, which is still the seat's bet.
                        CasinoAccountManager.addBalance(uuid, amount);
                        if (previous > 0) {
                            CasinoAccountManager.deductBalance(uuid, previous);
                        }
                    }
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("blackjack action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "DEAL" -> {
                try {
                    table.requestDeal();
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("blackjack action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "HIT" -> {
                try {
                    table.hit(uuid);
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("blackjack action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "STAND" -> {
                try {
                    table.stand(uuid);
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("blackjack action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "DOUBLE" -> {
                try {
                    int seat = table.seatOf(uuid);
                    if (seat < 0) {
                        return;
                    }
                    BlackjackTable.Seat s = table.getSeats()[seat];
                    BlackjackTable.Hand h = s.activeHand();
                    if (h == null) {
                        return;
                    }
                    // Doubling matches the current stake, so the extra chips are h.bet.
                    long extra = h.bet;
                    if (!CasinoAccountManager.deductBalance(uuid, extra)) {
                        return;
                    }
                    if (!table.doubleDown(uuid)) {
                        // Illegal double (hand not eligible): the stake is not owed after all.
                        CasinoAccountManager.addBalance(uuid, extra);
                    }
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("blackjack action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "SPLIT" -> {
                try {
                    int seat = table.seatOf(uuid);
                    if (seat < 0) {
                        return;
                    }
                    BlackjackTable.Seat s = table.getSeats()[seat];
                    BlackjackTable.Hand h = s.activeHand();
                    if (h == null) {
                        return;
                    }
                    // Splitting posts a second stake equal to the hand being split.
                    long extra = h.bet;
                    if (!CasinoAccountManager.deductBalance(uuid, extra)) {
                        return;
                    }
                    if (!table.split(uuid)) {
                        // Illegal split (no pair, or four hands already): refund.
                        CasinoAccountManager.addBalance(uuid, extra);
                    }
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("blackjack action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            case "TOGGLE_BOTS" -> {
                try {
                    table.toggleAutoFillBots();
                    broadcastSync();
                } catch (Exception e) {
                    Casino.LOGGER.warn("blackjack action {} failed for {}", action, player.getName().getString(), e);
                }
            }
            default -> {
                // handlesAction gates this; anything else belongs to the base class.
            }
        }
    }
}
