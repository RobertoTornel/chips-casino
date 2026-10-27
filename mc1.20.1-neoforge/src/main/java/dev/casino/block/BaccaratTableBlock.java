package dev.casino.block;

import dev.casino.Casino;
import dev.casino.block.entity.BaccaratBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class BaccaratTableBlock extends BaseCasinoBlock {

    public BaccaratTableBlock() {
        super("baccarat_table");
    }
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BaccaratBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        if (type == Casino.BACCARAT_BE_TYPE.get()) {
            return (lvl, pos, st, be) -> BaccaratBlockEntity.serverTick(lvl, pos, st, (BaccaratBlockEntity) be);
        }
        return null;
    }
}
