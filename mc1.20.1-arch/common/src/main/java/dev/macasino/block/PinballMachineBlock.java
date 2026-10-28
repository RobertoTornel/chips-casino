package dev.macasino.block;

import dev.macasino.block.entity.PinballBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class PinballMachineBlock extends BaseCasinoBlock {

    public PinballMachineBlock() {
        super("pinball_machine");
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PinballBlockEntity(pos, state);
    }
}
