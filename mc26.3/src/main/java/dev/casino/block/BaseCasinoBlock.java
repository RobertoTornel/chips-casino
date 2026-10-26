package dev.casino.block;

import dev.casino.block.entity.BaseCasinoBlockEntity;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.network.OpenCasinoScreenPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
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

    /**
     * 26.x requires a block's {@link ResourceKey} to be present on its properties
     * before construction — {@code Properties.effectiveDrops()} and
     * {@code effectiveDescriptionId()} both read it, and a missing id throws a
     * NullPointerException at class-initialisation time.
     */
    public static ResourceKey<Block> blockKey(String path) {
        return ResourceKey.create(Registries.BLOCK, dev.casino.Casino.id(path));
    }

    protected BaseCasinoBlock(String path) {
        super(BlockBehaviour.Properties.of()
                .strength(2.5f)
                .sound(SoundType.WOOD)
                .setId(blockKey(path)));
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
