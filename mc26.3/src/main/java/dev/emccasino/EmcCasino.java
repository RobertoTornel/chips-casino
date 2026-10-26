package dev.emccasino;

import dev.emccasino.api.EmcRegistry;
import dev.emccasino.block.*;
import dev.emccasino.block.entity.*;
import dev.emccasino.command.CasinoCommand;
import dev.emccasino.economy.CasinoAccountManager;
import dev.emccasino.network.CasinoActionPayload;
import dev.emccasino.network.CasinoSyncPayload;
import dev.emccasino.network.OpenCasinoScreenPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class EmcCasino implements ModInitializer {
    public static final String MOD_ID = "emc_casino";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Blocks
    public static final RouletteTableBlock ROULETTE_BLOCK = new RouletteTableBlock();
    public static final BlackjackTableBlock BLACKJACK_BLOCK = new BlackjackTableBlock();
    public static final BaccaratTableBlock BACCARAT_BLOCK = new BaccaratTableBlock();
    public static final PokerMachineBlock POKER_MACHINE_BLOCK = new PokerMachineBlock();
    public static final PachinkoCabinetBlock PACHINKO_BLOCK = new PachinkoCabinetBlock();
    public static final TexasHoldemFloorBlock TEXAS_HOLDEM_BLOCK = new TexasHoldemFloorBlock();

    // Block Entities
    public static BlockEntityType<RouletteBlockEntity> ROULETTE_BE_TYPE;
    public static BlockEntityType<BlackjackBlockEntity> BLACKJACK_BE_TYPE;
    public static BlockEntityType<BaccaratBlockEntity> BACCARAT_BE_TYPE;
    public static BlockEntityType<PokerMachineBlockEntity> POKER_MACHINE_BE_TYPE;
    public static BlockEntityType<PachinkoBlockEntity> PACHINKO_BE_TYPE;
    public static BlockEntityType<TexasHoldemBlockEntity> TEXAS_HOLDEM_BE_TYPE;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing EMC Casino (26.3)...");

        // 1. Register Blocks & Items
        registerBlock("roulette_table", ROULETTE_BLOCK);
        registerBlock("blackjack_table", BLACKJACK_BLOCK);
        registerBlock("baccarat_table", BACCARAT_BLOCK);
        registerBlock("poker_machine", POKER_MACHINE_BLOCK);
        registerBlock("pachinko_cabinet", PACHINKO_BLOCK);
        registerBlock("texas_holdem_floor", TEXAS_HOLDEM_BLOCK);

        // 2. Register Block Entity Types
        ROULETTE_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("roulette_table"),
                new BlockEntityType<>(RouletteBlockEntity::new, Set.of(ROULETTE_BLOCK))
        );
        BLACKJACK_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("blackjack_table"),
                new BlockEntityType<>(BlackjackBlockEntity::new, Set.of(BLACKJACK_BLOCK))
        );
        BACCARAT_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("baccarat_table"),
                new BlockEntityType<>(BaccaratBlockEntity::new, Set.of(BACCARAT_BLOCK))
        );
        POKER_MACHINE_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("poker_machine"),
                new BlockEntityType<>(PokerMachineBlockEntity::new, Set.of(POKER_MACHINE_BLOCK))
        );
        PACHINKO_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("pachinko_cabinet"),
                new BlockEntityType<>(PachinkoBlockEntity::new, Set.of(PACHINKO_BLOCK))
        );
        TEXAS_HOLDEM_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("texas_holdem_floor"),
                new BlockEntityType<>(TexasHoldemBlockEntity::new, Set.of(TEXAS_HOLDEM_BLOCK))
        );

        // 3. Register Packets
        PayloadTypeRegistry.serverboundPlay().register(CasinoActionPayload.TYPE, CasinoActionPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CasinoSyncPayload.TYPE, CasinoSyncPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OpenCasinoScreenPayload.TYPE, OpenCasinoScreenPayload.STREAM_CODEC);

        // 4. Register Network Receiver
        ServerPlayNetworking.registerGlobalReceiver(CasinoActionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> {
                BlockPos pos = payload.pos();
                BlockEntity be = player.level().getBlockEntity(pos);
                if (be instanceof BaseCasinoBlockEntity casinoBe) {
                    casinoBe.handleAction(player, payload.action(), payload.data());
                }
            });
        });

        // 5. Lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register(CasinoAccountManager::init);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> CasinoAccountManager.save());

        // 6. Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            CasinoCommand.register(dispatcher);
        });

        // 7. Builtin Dark Mode Resource Pack
        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(container -> {
            ResourceManagerHelper.registerBuiltinResourcePack(
                    id("dark_mode"),
                    container,
                    Component.literal("EMC Casino Dark Mode"),
                    ResourcePackActivationType.NORMAL
            );
        });

        LOGGER.info("EMC Casino initialized with 6 games, 0.8x deposit / 1.2x withdraw economy, and bot AI!");
    }

    private static void registerBlock(String name, Block block) {
        Identifier identifier = id(name);
        Registry.register(BuiltInRegistries.BLOCK, identifier, block);
        Registry.register(BuiltInRegistries.ITEM, identifier, new BlockItem(block, new Item.Properties()));
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
