package dev.macasino.block;

import dev.macasino.block.entity.SlotMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class SlotMachineBlock extends BaseCasinoBlock {

    public SlotMachineBlock() {
        super("slot_machine");
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SlotMachineBlockEntity(pos, state);
    }
}
