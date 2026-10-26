package dev.casino.block;

import dev.casino.block.entity.BlackjackBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class BlackjackTableBlock extends BaseCasinoBlock {

    public BlackjackTableBlock() {
        super("blackjack_table");
    }
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BlackjackBlockEntity(pos, state);
    }
}
