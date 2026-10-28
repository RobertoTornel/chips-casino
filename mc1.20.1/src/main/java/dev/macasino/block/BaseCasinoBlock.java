package dev.macasino.block;

import dev.macasino.block.entity.BaseCasinoBlockEntity;
import dev.macasino.economy.CasinoAccountManager;
import dev.macasino.network.CasinoNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public abstract class BaseCasinoBlock extends Block implements EntityBlock {
    /** Registration path, used by 26.x for the block id. Unused on 1.20.1. */
    protected final String path;

    public BaseCasinoBlock() {
        this("casino_block");
    }

    protected BaseCasinoBlock(String path) {
        super(BlockBehaviour.Properties.of()
                .strength(2.5f)
                .sound(SoundType.WOOD));
        this.path = path;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!level.isClientSide() && hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer serverPlayer) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BaseCasinoBlockEntity casinoBe) {
                casinoBe.addViewer(serverPlayer);
                long balance = CasinoAccountManager.getBalance(serverPlayer.getUUID());
                String initialState = casinoBe.serializeState(serverPlayer);
                CasinoNetwork.sendOpenScreen(serverPlayer, pos, casinoBe.getGameType(), initialState, balance);
            }
        }
        return InteractionResult.SUCCESS;
    }
}
