package dev.casino;

import dev.casino.block.*;
import dev.casino.block.entity.*;
import dev.casino.command.CasinoCommand;
import dev.casino.economy.CasinoAccountManager;
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
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.security.SecureRandom;

public class Casino implements ModInitializer {
    public static final String MOD_ID = "casino";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Cryptographically strong RNG for server-owned game seeds. */
    public static final SecureRandom SLOT_RANDOM = new SecureRandom();

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
    public static final PinballMachineBlock PINBALL_BLOCK = new PinballMachineBlock();
    public static final TexasHoldemFloorBlock TEXAS_HOLDEM_BLOCK = new TexasHoldemFloorBlock();
    public static final ExchangeBlock EXCHANGE_BLOCK = new ExchangeBlock();
    public static final SlotMachineBlock SLOT_MACHINE_BLOCK = new SlotMachineBlock();

    // Creative mode tab: "Chips Casino" with slot_machine icon, holding all 9 block items.
    public static final CreativeModeTab CASINO_TAB = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            id("casino_tab"),
            new CreativeModeTab.Builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.literal("Chips Casino"))
                    .icon(() -> new ItemStack(SLOT_MACHINE_BLOCK.asItem()))
                    .displayItems((features, emitter) -> {
                        emitter.accept(ROULETTE_BLOCK.asItem());
                        emitter.accept(BLACKJACK_BLOCK.asItem());
                        emitter.accept(BACCARAT_BLOCK.asItem());
                        emitter.accept(POKER_MACHINE_BLOCK.asItem());
                        emitter.accept(PACHINKO_BLOCK.asItem());
                        emitter.accept(PINBALL_BLOCK.asItem());
                        emitter.accept(TEXAS_HOLDEM_BLOCK.asItem());
                        emitter.accept(EXCHANGE_BLOCK.asItem());
                        emitter.accept(SLOT_MACHINE_BLOCK.asItem());
                    })
                    .build()
    );

    // Block Entities
    public static BlockEntityType<RouletteBlockEntity> ROULETTE_BE_TYPE;
    public static BlockEntityType<BlackjackBlockEntity> BLACKJACK_BE_TYPE;
    public static BlockEntityType<BaccaratBlockEntity> BACCARAT_BE_TYPE;
    public static BlockEntityType<PokerMachineBlockEntity> POKER_MACHINE_BE_TYPE;
    public static BlockEntityType<PachinkoBlockEntity> PACHINKO_BE_TYPE;
    public static BlockEntityType<PinballBlockEntity> PINBALL_BE_TYPE;
    public static BlockEntityType<TexasHoldemBlockEntity> TEXAS_HOLDEM_BE_TYPE;
    public static BlockEntityType<ExchangeBlockEntity> EXCHANGE_BE_TYPE;
    public static BlockEntityType<SlotMachineBlockEntity> SLOT_MACHINE_BE_TYPE;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Chips Casino (1.20.1 Fabric)...");

        // 1. Register Blocks & Items
        registerBlock("roulette_table", ROULETTE_BLOCK);
        registerBlock("blackjack_table", BLACKJACK_BLOCK);
        registerBlock("baccarat_table", BACCARAT_BLOCK);
        registerBlock("poker_machine", POKER_MACHINE_BLOCK);
        registerBlock("pachinko_cabinet", PACHINKO_BLOCK);
        registerBlock("pinball_machine", PINBALL_BLOCK);
        registerBlock("texas_holdem_floor", TEXAS_HOLDEM_BLOCK);
        registerBlock("exchange", EXCHANGE_BLOCK);
        registerBlock("slot_machine", SLOT_MACHINE_BLOCK);

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
        PINBALL_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                new ResourceLocation("casino", "pinball_machine"),
                BlockEntityType.Builder.of(PinballBlockEntity::new, PINBALL_BLOCK).build(null)
        );
        TEXAS_HOLDEM_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("texas_holdem_floor"),
                BlockEntityType.Builder.of(TexasHoldemBlockEntity::new, TEXAS_HOLDEM_BLOCK).build(null)
        );

        EXCHANGE_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("exchange"),
                BlockEntityType.Builder.of(ExchangeBlockEntity::new, EXCHANGE_BLOCK).build(null)
        );
        SLOT_MACHINE_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("slot_machine"),
                BlockEntityType.Builder.of(SlotMachineBlockEntity::new, SLOT_MACHINE_BLOCK).build(null)
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
                    Component.literal("Casino Dark Mode"),
                    ResourcePackActivationType.NORMAL
            );
        });

        // 7. Data-driven Chips table (data/<ns>/prices/*.json, reloadable via /reload)
        dev.casino.api.PriceDataLoader.register();

        // 8. Creative Mode Tab (stub — registration filled in by C stage)
        registerCreativeTab();

        LOGGER.info("Casino 1.20.1 initialized successfully!");
    }

    private static void registerBlock(String name, Block block) {
        ResourceLocation identifier = id(name);
        Registry.register(BuiltInRegistries.BLOCK, identifier, block);
        Registry.register(BuiltInRegistries.ITEM, identifier, new BlockItem(block, new Item.Properties()));
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    private static void registerCreativeTab() {
        // Eagerly registered above as the CASINO_TAB field initializer; nothing to do here.
    }
}
