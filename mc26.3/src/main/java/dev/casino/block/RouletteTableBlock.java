package dev.casino.block;

import dev.casino.Casino;
import dev.casino.block.entity.RouletteBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class RouletteTableBlock extends BaseCasinoBlock {

    public RouletteTableBlock() {
        super("roulette_table");
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RouletteBlockEntity(pos, state);
    }

    /**
     * The wheel needs a clock: the betting window, the deceleration and the settle hold are
     * all counted in ticks. Guarded on the block entity type, exactly like
     * {@link BlackjackTableBlock}, so a stray ticker can never be handed a mismatched
     * block entity.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        if (type == Casino.ROULETTE_BE_TYPE) {
            return (lvl, pos, st, be) -> RouletteBlockEntity.serverTick(lvl, pos, st, (RouletteBlockEntity) be);
        }
        return null;
    }
}
