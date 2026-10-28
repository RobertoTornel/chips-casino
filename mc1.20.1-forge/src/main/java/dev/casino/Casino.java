package dev.casino;

import dev.casino.block.*;
import dev.casino.block.entity.*;
import dev.casino.command.CasinoCommand;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.network.CasinoNetwork;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
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
    public static final RegistryObject<Block> CRAPS_BLOCK = BLOCKS.register("craps_table", CrapsTableBlock::new);
    public static final RegistryObject<Block> WHEEL_OF_FORTUNE_BLOCK = BLOCKS.register("wheel_of_fortune", WheelOfFortuneBlock::new);
    public static final RegistryObject<Block> KENO_BLOCK = BLOCKS.register("keno", KenoBlock::new);

    // Creative mode tab: "Chips Casino" with slot_machine icon, holding all 9 block items.
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final RegistryObject<CreativeModeTab> CASINO_TAB = CREATIVE_TABS.register("casino_tab",
            () -> new CreativeModeTab.Builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.literal("Chips Casino"))
                    .icon(() -> new ItemStack(SLOT_MACHINE_BLOCK.get().asItem()))
                    .displayItems((features, emitter) -> {
                        emitter.accept(ROULETTE_BLOCK.get().asItem());
                        emitter.accept(BLACKJACK_BLOCK.get().asItem());
                        emitter.accept(BACCARAT_BLOCK.get().asItem());
                        emitter.accept(POKER_MACHINE_BLOCK.get().asItem());
                        emitter.accept(PACHINKO_BLOCK.get().asItem());
                        emitter.accept(PINBALL_BLOCK.get().asItem());
                        emitter.accept(TEXAS_HOLDEM_BLOCK.get().asItem());
                        emitter.accept(EXCHANGE_BLOCK.get().asItem());
                        emitter.accept(SLOT_MACHINE_BLOCK.get().asItem());
                        emitter.accept(CRAPS_BLOCK.get().asItem());
                        emitter.accept(WHEEL_OF_FORTUNE_BLOCK.get().asItem());
                        emitter.accept(KENO_BLOCK.get().asItem());
                    })
                    .build()
    );

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
    public static final RegistryObject<Item> CRAPS_ITEM = ITEMS.register("craps_table", () -> new BlockItem(CRAPS_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> WHEEL_OF_FORTUNE_ITEM = ITEMS.register("wheel_of_fortune", () -> new BlockItem(WHEEL_OF_FORTUNE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> KENO_ITEM = ITEMS.register("keno", () -> new BlockItem(KENO_BLOCK.get(), new Item.Properties()));

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
    public static final RegistryObject<BlockEntityType<CrapsBlockEntity>> CRAPS_BE_TYPE = BLOCK_ENTITIES.register("craps_table",
            () -> BlockEntityType.Builder.of(CrapsBlockEntity::new, CRAPS_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<WheelOfFortuneBlockEntity>> WHEEL_OF_FORTUNE_BE_TYPE = BLOCK_ENTITIES.register("wheel_of_fortune",
            () -> BlockEntityType.Builder.of(WheelOfFortuneBlockEntity::new, WHEEL_OF_FORTUNE_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<KenoBlockEntity>> KENO_BE_TYPE = BLOCK_ENTITIES.register("keno",
            () -> BlockEntityType.Builder.of(KenoBlockEntity::new, KENO_BLOCK.get()).build(null));

    public Casino() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        CREATIVE_TABS.register(modBus);

        // Creative Mode Tab registered via CREATIVE_TABS above;

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
        CasinoAccountManager.saveNow();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            CasinoAccountManager.tick();
        }
    }

    // Creative Mode Tab is registered above via CREATIVE_TABS.register(modBus) —
    // no separate registration method needed for Forge/NeoForge.
}
