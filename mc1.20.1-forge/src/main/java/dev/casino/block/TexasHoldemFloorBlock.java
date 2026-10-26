package dev.casino.block;

import dev.casino.Casino;
import dev.casino.block.entity.TexasHoldemBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class TexasHoldemFloorBlock extends BaseCasinoBlock {
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TexasHoldemBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        if (type == Casino.TEXAS_HOLDEM_BE_TYPE.get()) {
            return (lvl, pos, st, be) -> TexasHoldemBlockEntity.serverTick(lvl, pos, st, (TexasHoldemBlockEntity) be);
        }
        return null;
    }
}
