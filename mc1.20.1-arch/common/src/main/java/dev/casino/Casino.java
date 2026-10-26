package dev.casino;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.casino.api.PriceDataLoader;
import dev.casino.block.*;
import dev.casino.item.ChipItem;
import dev.casino.block.entity.*;
import dev.casino.command.CasinoCommand;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.network.CasinoNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform-neutral mod core (Architectury multiloader).
 *
 * <p>Registration, commands, lifecycle hooks and networking descriptors all live here;
 * the {@code fabric}, {@code forge} and {@code neoforge} modules only supply the loader
 * entrypoint that calls {@link #init()} (plus a client entrypoint that calls
 * {@link dev.casino.client.CasinoClientInit#initClient()}).</p>
 */
public final class Casino {
    public static final String MOD_ID = "casino";
    public static final String MOD_NAME = "Casino";
    public static final String VERSION = "0.1.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ---------------------------------------------------------------------
    // Registries (Architectury wraps Fabric/Forge/NeoForge registry plumbing)
    // ---------------------------------------------------------------------
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(MOD_ID, Registries.BLOCK);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(MOD_ID, Registries.ITEM);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    // ---- Blocks ----------------------------------------------------------
    public static final RegistrySupplier<RouletteTableBlock> ROULETTE_BLOCK =
            BLOCKS.register("roulette_table", RouletteTableBlock::new);
    public static final RegistrySupplier<BlackjackTableBlock> BLACKJACK_BLOCK =
            BLOCKS.register("blackjack_table", BlackjackTableBlock::new);
    public static final RegistrySupplier<BaccaratTableBlock> BACCARAT_BLOCK =
            BLOCKS.register("baccarat_table", BaccaratTableBlock::new);
    public static final RegistrySupplier<PokerMachineBlock> POKER_MACHINE_BLOCK =
            BLOCKS.register("poker_machine", PokerMachineBlock::new);
    public static final RegistrySupplier<PachinkoCabinetBlock> PACHINKO_BLOCK =
            BLOCKS.register("pachinko_cabinet", PachinkoCabinetBlock::new);
    public static final RegistrySupplier<TexasHoldemFloorBlock> TEXAS_HOLDEM_BLOCK =
            BLOCKS.register("texas_holdem_floor", TexasHoldemFloorBlock::new);
    public static final RegistrySupplier<ExchangeBlock> EXCHANGE_BLOCK =
            BLOCKS.register("exchange", ExchangeBlock::new);

    // ---- Block entities --------------------------------------------------
    public static final RegistrySupplier<BlockEntityType<RouletteBlockEntity>> ROULETTE_BE_TYPE =
            BLOCK_ENTITIES.register("roulette_table",
                    () -> makeType(RouletteBlockEntity::new, ROULETTE_BLOCK.get()));
    public static final RegistrySupplier<BlockEntityType<BlackjackBlockEntity>> BLACKJACK_BE_TYPE =
            BLOCK_ENTITIES.register("blackjack_table",
                    () -> makeType(BlackjackBlockEntity::new, BLACKJACK_BLOCK.get()));
    public static final RegistrySupplier<BlockEntityType<BaccaratBlockEntity>> BACCARAT_BE_TYPE =
            BLOCK_ENTITIES.register("baccarat_table",
                    () -> makeType(BaccaratBlockEntity::new, BACCARAT_BLOCK.get()));
    public static final RegistrySupplier<BlockEntityType<PokerMachineBlockEntity>> POKER_MACHINE_BE_TYPE =
            BLOCK_ENTITIES.register("poker_machine",
                    () -> makeType(PokerMachineBlockEntity::new, POKER_MACHINE_BLOCK.get()));
    public static final RegistrySupplier<BlockEntityType<PachinkoBlockEntity>> PACHINKO_BE_TYPE =
            BLOCK_ENTITIES.register("pachinko_cabinet",
                    () -> makeType(PachinkoBlockEntity::new, PACHINKO_BLOCK.get()));
    public static final RegistrySupplier<BlockEntityType<TexasHoldemBlockEntity>> TEXAS_HOLDEM_BE_TYPE =
            BLOCK_ENTITIES.register("texas_holdem_floor",
                    () -> makeType(TexasHoldemBlockEntity::new, TEXAS_HOLDEM_BLOCK.get()));
    public static final RegistrySupplier<BlockEntityType<ExchangeBlockEntity>> EXCHANGE_BE_TYPE =
            BLOCK_ENTITIES.register("exchange",
                    () -> makeType(ExchangeBlockEntity::new, EXCHANGE_BLOCK.get()));

    // ---- Block items -----------------------------------------------------
    public static final RegistrySupplier<Item> ROULETTE_ITEM =
            ITEMS.register("roulette_table", () -> new BlockItem(ROULETTE_BLOCK.get(), new Item.Properties()));
    public static final RegistrySupplier<Item> BLACKJACK_ITEM =
            ITEMS.register("blackjack_table", () -> new BlockItem(BLACKJACK_BLOCK.get(), new Item.Properties()));
    public static final RegistrySupplier<Item> BACCARAT_ITEM =
            ITEMS.register("baccarat_table", () -> new BlockItem(BACCARAT_BLOCK.get(), new Item.Properties()));
    public static final RegistrySupplier<Item> POKER_MACHINE_ITEM =
            ITEMS.register("poker_machine", () -> new BlockItem(POKER_MACHINE_BLOCK.get(), new Item.Properties()));
    public static final RegistrySupplier<Item> PACHINKO_ITEM =
            ITEMS.register("pachinko_cabinet", () -> new BlockItem(PACHINKO_BLOCK.get(), new Item.Properties()));
    public static final RegistrySupplier<Item> TEXAS_HOLDEM_ITEM =
            ITEMS.register("texas_holdem_floor", () -> new BlockItem(TEXAS_HOLDEM_BLOCK.get(), new Item.Properties()));
    public static final RegistrySupplier<Item> EXCHANGE_ITEM =
            ITEMS.register("exchange", () -> new BlockItem(EXCHANGE_BLOCK.get(), new Item.Properties()));
    public static final RegistrySupplier<Item> CHIP_ITEM =
            ITEMS.register("chip", () -> new ChipItem(new Item.Properties()));

    private static boolean initialized;

    private Casino() {
    }

    /** Called once from every loader's mod entrypoint. */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        LOGGER.info("Initializing {} v{} (Architectury multiloader)", MOD_NAME, VERSION);

        // Order matters: blocks -> block entities (reference blocks) -> block items.
        BLOCKS.register();
        BLOCK_ENTITIES.register();
        ITEMS.register();

        // Common (C2S) packet handler.
        CasinoNetwork.initCommon();

        // Commands: /casino balance|give|setprice|price|reload
        CommandRegistrationEvent.EVENT.register(
                (dispatcher, registryAccess, environment) -> CasinoCommand.register(dispatcher));

        // World-scoped Chips account persistence.
        LifecycleEvent.SERVER_STARTED.register(server -> {
            CasinoAccountManager.init(server);
            PriceDataLoader.reload(server.getResourceManager());
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> CasinoAccountManager.save());

        LOGGER.info("{} core registered: 6 blocks, 6 block entities, 6 items", MOD_NAME);
    }

    /** Id helper that keeps every registry/asset id in the {@code casino} namespace. */
    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    private static <T extends BlockEntity> BlockEntityType<T> makeType(
            BlockEntityType.BlockEntitySupplier<T> supplier, Block block) {
        return BlockEntityType.Builder.of(supplier, block).build(null);
    }
}
