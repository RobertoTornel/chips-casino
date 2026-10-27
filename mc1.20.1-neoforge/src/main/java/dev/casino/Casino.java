package dev.casino;

import dev.casino.block.*;
import dev.casino.block.entity.*;
import dev.casino.command.CasinoCommand;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.network.CasinoNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.security.SecureRandom;

@Mod(Casino.MODID)
public class Casino {
    public static final String MODID = "casino";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    /** Cryptographically strong RNG for server-owned game seeds. */
    public static final SecureRandom SLOT_RANDOM = new SecureRandom();

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);

    // Blocks
    public static final RegistryObject<Block> ROULETTE_BLOCK = BLOCKS.register("roulette_table", RouletteTableBlock::new);
    public static final RegistryObject<Block> BLACKJACK_BLOCK = BLOCKS.register("blackjack_table", BlackjackTableBlock::new);
    public static final RegistryObject<Block> BACCARAT_BLOCK = BLOCKS.register("baccarat_table", BaccaratTableBlock::new);
    public static final RegistryObject<Block> POKER_MACHINE_BLOCK = BLOCKS.register("poker_machine", PokerMachineBlock::new);
    public static final RegistryObject<Block> PACHINKO_BLOCK = BLOCKS.register("pachinko_cabinet", PachinkoCabinetBlock::new);
    public static final RegistryObject<Block> PINBALL_BLOCK = BLOCKS.register("pinball_machine", dev.casino.block.PinballMachineBlock::new);
    public static final RegistryObject<Block> TEXAS_HOLDEM_BLOCK = BLOCKS.register("texas_holdem_floor", TexasHoldemFloorBlock::new);
    public static final RegistryObject<Block> EXCHANGE_BLOCK = BLOCKS.register("exchange", ExchangeBlock::new);
    public static final RegistryObject<Block> SLOT_MACHINE_BLOCK = BLOCKS.register("slot_machine", SlotMachineBlock::new);

    // Creative mode tab (stub — C stage fills real registration, PLAN-FLAIR.md Pass 1)
    // Tab "Chips Casino" with slot_machine block item as icon, holding all 9 block items.
    public static final CreativeModeTab CASINO_TAB = null; // TODO: register CreativeModeTab here

    // Items
    public static final RegistryObject<Item> ROULETTE_ITEM = ITEMS.register("roulette_table", () -> new BlockItem(ROULETTE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> BLACKJACK_ITEM = ITEMS.register("blackjack_table", () -> new BlockItem(BLACKJACK_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> BACCARAT_ITEM = ITEMS.register("baccarat_table", () -> new BlockItem(BACCARAT_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> POKER_MACHINE_ITEM = ITEMS.register("poker_machine", () -> new BlockItem(POKER_MACHINE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> PACHINKO_ITEM = ITEMS.register("pachinko_cabinet", () -> new BlockItem(PACHINKO_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> PINBALL_ITEM = ITEMS.register("pinball_machine", () -> new BlockItem(PINBALL_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> TEXAS_HOLDEM_ITEM = ITEMS.register("texas_holdem_floor", () -> new BlockItem(TEXAS_HOLDEM_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> EXCHANGE_ITEM = ITEMS.register("exchange", () -> new BlockItem(EXCHANGE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> SLOT_MACHINE_ITEM = ITEMS.register("slot_machine", () -> new BlockItem(SLOT_MACHINE_BLOCK.get(), new Item.Properties()));

    // Block Entities
    public static final RegistryObject<BlockEntityType<RouletteBlockEntity>> ROULETTE_BE_TYPE = BLOCK_ENTITIES.register("roulette_table",
            () -> BlockEntityType.Builder.of(RouletteBlockEntity::new, ROULETTE_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<BlackjackBlockEntity>> BLACKJACK_BE_TYPE = BLOCK_ENTITIES.register("blackjack_table",
            () -> BlockEntityType.Builder.of(BlackjackBlockEntity::new, BLACKJACK_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<BaccaratBlockEntity>> BACCARAT_BE_TYPE = BLOCK_ENTITIES.register("baccarat_table",
            () -> BlockEntityType.Builder.of(BaccaratBlockEntity::new, BACCARAT_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<PokerMachineBlockEntity>> POKER_MACHINE_BE_TYPE = BLOCK_ENTITIES.register("poker_machine",
            () -> BlockEntityType.Builder.of(PokerMachineBlockEntity::new, POKER_MACHINE_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<PachinkoBlockEntity>> PACHINKO_BE_TYPE = BLOCK_ENTITIES.register("pachinko_cabinet",
            () -> BlockEntityType.Builder.of(PachinkoBlockEntity::new, PACHINKO_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<PinballBlockEntity>> PINBALL_BE_TYPE = BLOCK_ENTITIES.register("pinball_machine",
            () -> BlockEntityType.Builder.of(PinballBlockEntity::new, PINBALL_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<TexasHoldemBlockEntity>> TEXAS_HOLDEM_BE_TYPE = BLOCK_ENTITIES.register("texas_holdem_floor",
            () -> BlockEntityType.Builder.of(TexasHoldemBlockEntity::new, TEXAS_HOLDEM_BLOCK.get()).build(null));

    public static final RegistryObject<BlockEntityType<ExchangeBlockEntity>> EXCHANGE_BE_TYPE = BLOCK_ENTITIES.register("exchange",
            () -> BlockEntityType.Builder.of(ExchangeBlockEntity::new, EXCHANGE_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<SlotMachineBlockEntity>> SLOT_MACHINE_BE_TYPE = BLOCK_ENTITIES.register("slot_machine",
            () -> BlockEntityType.Builder.of(SlotMachineBlockEntity::new, SLOT_MACHINE_BLOCK.get()).build(null));

    public Casino() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);

        // Creative Mode Tab (stub — registration filled in by C stage)
        registerCreativeTab();

        modBus.addListener(this::commonSetup);

        // Forge Event Bus
        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(CasinoNetwork::init);
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(dev.casino.api.PriceDataLoader.instance());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CasinoCommand.register(event.getDispatcher());
    }

    private void onServerStarted(ServerStartedEvent event) {
        CasinoAccountManager.init(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        CasinoAccountManager.save();
    }

    private static void registerCreativeTab() {
        // TODO: DeferredRegister<CreativeModeTab> on ForgeRegistries.CREATIVE_MODE_TABS with
        // icon = () -> new ItemStack(SLOT_MACHINE_BLOCK.get().asItem()), title = "Chips Casino",
        // contents = all 9 block items (PLAN-FLAIR.md Pass 1).
        // Loader API for CreativeModeTab varies; left as compiling stub for C stage.
    }
}
