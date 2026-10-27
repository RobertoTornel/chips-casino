package dev.casino.block;

import dev.casino.Casino;
import dev.casino.block.entity.BlackjackBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class BlackjackTableBlock extends BaseCasinoBlock {

    public BlackjackTableBlock() {
        super("blackjack_table");
    }
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BlackjackBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        if (type == Casino.BLACKJACK_BE_TYPE.get()) {
            return (lvl, pos, st, be) -> BlackjackBlockEntity.serverTick(lvl, pos, st, (BlackjackBlockEntity) be);
        }
        return null;
    }
}
