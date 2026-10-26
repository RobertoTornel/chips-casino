package dev.emccasino.block;

import dev.emccasino.block.entity.BaseCasinoBlockEntity;
import dev.emccasino.economy.CasinoAccountManager;
import dev.emccasino.network.OpenCasinoScreenPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public abstract class BaseCasinoBlock extends Block implements EntityBlock {
    public BaseCasinoBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(2.5f)
                .sound(SoundType.WOOD));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BaseCasinoBlockEntity casinoBe) {
                casinoBe.addViewer(serverPlayer);
                long balance = CasinoAccountManager.getBalance(serverPlayer.getUUID());
                String initialState = casinoBe.serializeState(serverPlayer);
                ServerPlayNetworking.send(serverPlayer, new OpenCasinoScreenPayload(pos, casinoBe.getGameType(), initialState, balance));
            }
        }
        return InteractionResult.SUCCESS;
    }
}
