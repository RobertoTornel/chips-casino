package dev.macasino.block;

import dev.macasino.Casino;
import dev.macasino.block.entity.CrapsBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class CrapsTableBlock extends BaseCasinoBlock {

    public CrapsTableBlock() {
        super("craps_table");
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CrapsBlockEntity(pos, state);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        if (type == Casino.CRAPS_BE_TYPE.get()) {
            return (lvl, pos2, st, be) -> CrapsBlockEntity.serverTick(lvl, pos2, st, (CrapsBlockEntity) be);
        }
        return null;
    }
}
