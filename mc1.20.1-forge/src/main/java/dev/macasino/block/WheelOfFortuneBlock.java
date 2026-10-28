package dev.macasino.block;

import dev.macasino.Casino;
import dev.macasino.block.entity.WheelOfFortuneBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class WheelOfFortuneBlock extends BaseCasinoBlock {

    public WheelOfFortuneBlock() {
        super("wheel_of_fortune");
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WheelOfFortuneBlockEntity(pos, state);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        if (type == Casino.WHEEL_OF_FORTUNE_BE_TYPE.get()) {
            return (lvl, pos2, st, be) -> WheelOfFortuneBlockEntity.serverTick(lvl, pos2, st, (WheelOfFortuneBlockEntity) be);
        }
        return null;
    }
}
