package dev.emccasino;

import dev.emccasino.block.*;
import dev.emccasino.block.entity.*;
import dev.emccasino.command.CasinoCommand;
import dev.emccasino.economy.CasinoAccountManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmcCasino implements ModInitializer {
    public static final String MOD_ID = "emc_casino";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Network Channel IDs
    public static final ResourceLocation ACTION_ID = id("action");
    public static final ResourceLocation SYNC_ID = id("sync");
    public static final ResourceLocation OPEN_SCREEN_ID = id("open_screen");

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
        LOGGER.info("Initializing EMC Casino (1.20.1 Fabric)...");

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
                BlockEntityType.Builder.of(RouletteBlockEntity::new, ROULETTE_BLOCK).build(null)
        );
        BLACKJACK_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("blackjack_table"),
                BlockEntityType.Builder.of(BlackjackBlockEntity::new, BLACKJACK_BLOCK).build(null)
        );
        BACCARAT_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("baccarat_table"),
                BlockEntityType.Builder.of(BaccaratBlockEntity::new, BACCARAT_BLOCK).build(null)
        );
        POKER_MACHINE_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("poker_machine"),
                BlockEntityType.Builder.of(PokerMachineBlockEntity::new, POKER_MACHINE_BLOCK).build(null)
        );
        PACHINKO_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("pachinko_cabinet"),
                BlockEntityType.Builder.of(PachinkoBlockEntity::new, PACHINKO_BLOCK).build(null)
        );
        TEXAS_HOLDEM_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("texas_holdem_floor"),
                BlockEntityType.Builder.of(TexasHoldemBlockEntity::new, TEXAS_HOLDEM_BLOCK).build(null)
        );

        // 3. Register Network Receiver
        ServerPlayNetworking.registerGlobalReceiver(ACTION_ID, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String action = buf.readUtf();
            String data = buf.readUtf();
            server.execute(() -> {
                BlockEntity be = player.level().getBlockEntity(pos);
                if (be instanceof BaseCasinoBlockEntity casinoBe) {
                    casinoBe.handleAction(player, action, data);
                }
            });
        });

        // 4. Lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register(CasinoAccountManager::init);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> CasinoAccountManager.save());

        // 5. Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            CasinoCommand.register(dispatcher);
        });

        // 6. Builtin Dark Mode Resource Pack
        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(container -> {
            ResourceManagerHelper.registerBuiltinResourcePack(
                    id("dark_mode"),
                    container,
                    Component.literal("EMC Casino Dark Mode"),
                    ResourcePackActivationType.NORMAL
            );
        });

        // 7. Data-driven EMC table (data/<ns>/emc/*.json, reloadable via /reload)
        dev.emccasino.api.EmcDataLoader.register();

        LOGGER.info("EMC Casino 1.20.1 initialized successfully!");
    }

    private static void registerBlock(String name, Block block) {
        ResourceLocation identifier = id(name);
        Registry.register(BuiltInRegistries.BLOCK, identifier, block);
        Registry.register(BuiltInRegistries.ITEM, identifier, new BlockItem(block, new Item.Properties()));
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
