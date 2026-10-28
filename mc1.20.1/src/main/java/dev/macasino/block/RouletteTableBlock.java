package dev.macasino.block;

import dev.macasino.block.entity.RouletteBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class RouletteTableBlock extends BaseCasinoBlock {
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RouletteBlockEntity(pos, state);
    }
}
