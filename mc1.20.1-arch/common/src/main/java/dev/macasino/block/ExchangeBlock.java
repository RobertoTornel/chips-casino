package dev.macasino.block;

import dev.macasino.block.entity.ExchangeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Exchange: the block where items become money and money becomes items.
 *
 * <p>It is the only place the two conversion rates are exposed to the player, and it is
 * deliberately a separate block from the game tables — the games are the fun, this is the
 * banking.
 */
public class ExchangeBlock extends BaseCasinoBlock {

    public ExchangeBlock() {
        super("exchange");
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ExchangeBlockEntity(pos, state);
    }
}
