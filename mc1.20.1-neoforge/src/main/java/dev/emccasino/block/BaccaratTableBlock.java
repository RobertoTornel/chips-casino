package dev.emccasino.block;

import dev.emccasino.block.entity.BaccaratBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class BaccaratTableBlock extends BaseCasinoBlock {
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BaccaratBlockEntity(pos, state);
    }
}
