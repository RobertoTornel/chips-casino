package dev.macasino.block;

import dev.macasino.Casino;
import dev.macasino.block.entity.KenoBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class KenoBlock extends BaseCasinoBlock {

    public KenoBlock() {
        super("keno");
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new KenoBlockEntity(pos, state);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        if (type == Casino.KENO_BE_TYPE.get()) {
            return (lvl, pos2, st, be) -> KenoBlockEntity.serverTick(lvl, pos2, st, (KenoBlockEntity) be);
        }
        return null;
    }
}
