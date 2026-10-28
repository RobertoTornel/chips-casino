package dev.macasino.block;

import dev.macasino.block.entity.PachinkoBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class PachinkoCabinetBlock extends BaseCasinoBlock {

    public PachinkoCabinetBlock() {
        super("pachinko_cabinet");
    }
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PachinkoBlockEntity(pos, state);
    }
}
