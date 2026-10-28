package dev.casino;

import dev.casino.api.PriceDataLoader;
import dev.casino.api.PriceTable;
import dev.casino.block.*;
import dev.casino.block.entity.*;
import dev.casino.jackpot.ProgressiveJackpot;
import dev.casino.stats.PlayerStats;
import dev.casino.loan.LoanManager;
import dev.casino.command.CasinoCommand;
import dev.casino.economy.CasinoAccountManager;
import dev.casino.network.CasinoActionPayload;
import dev.casino.network.CasinoSyncPayload;
import dev.casino.network.OpenCasinoScreenPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
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
import java.util.Set;

public class Casino implements ModInitializer {
    public static final String MOD_ID = "casino";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** Server-owned secure random for gambling outcomes. Client cannot predict or influence. */
    public static final SecureRandom SLOT_RANDOM = new SecureRandom();

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
    public static final CrapsTableBlock CRAPS_BLOCK = new CrapsTableBlock();
    public static final WheelOfFortuneBlock WHEEL_OF_FORTUNE_BLOCK = new WheelOfFortuneBlock();
    public static final KenoBlock KENO_BLOCK = new KenoBlock();

    // Creative mode tab placeholder — registered in onInitialize() after blocks exist,
    // because Block.asItem() requires the block to be registered first.
    public static CreativeModeTab CASINO_TAB;



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
    public static BlockEntityType<CrapsBlockEntity> CRAPS_BE_TYPE;
    public static BlockEntityType<WheelOfFortuneBlockEntity> WHEEL_OF_FORTUNE_BE_TYPE;
    public static BlockEntityType<KenoBlockEntity> KENO_BE_TYPE;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Casino (26.3)...");

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
        registerBlock("craps_table", CRAPS_BLOCK);
        registerBlock("wheel_of_fortune", WHEEL_OF_FORTUNE_BLOCK);
        registerBlock("keno", KENO_BLOCK);

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
        PINBALL_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("pinball_machine"),
                new BlockEntityType<>(PinballBlockEntity::new, Set.of(PINBALL_BLOCK))
        );

        EXCHANGE_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("exchange"),
                new BlockEntityType<>(ExchangeBlockEntity::new, Set.of(EXCHANGE_BLOCK))
        );
        SLOT_MACHINE_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("slot_machine"),
                new BlockEntityType<>(SlotMachineBlockEntity::new, Set.of(SLOT_MACHINE_BLOCK))
        );
        CRAPS_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("craps_table"),
                new BlockEntityType<>(CrapsBlockEntity::new, Set.of(CRAPS_BLOCK))
        );
        WHEEL_OF_FORTUNE_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("wheel_of_fortune"),
                new BlockEntityType<>(WheelOfFortuneBlockEntity::new, Set.of(WHEEL_OF_FORTUNE_BLOCK))
        );
        KENO_BE_TYPE = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("keno"),
                new BlockEntityType<>(KenoBlockEntity::new, Set.of(KENO_BLOCK))
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
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            CasinoAccountManager.init(server);
            ProgressiveJackpot.init(server);
            PlayerStats.init(server);
            LoanManager.init(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            CasinoAccountManager.saveNow();
            ProgressiveJackpot.save();
            PlayerStats.save();
            LoanManager.save();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> CasinoAccountManager.tick());

        // 6. Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            CasinoCommand.register(dispatcher);
        });

        // 7. Builtin Dark Mode Resource Pack
        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(container -> {
            ResourceManagerHelper.registerBuiltinResourcePack(
                    id("dark_mode"),
                    container,
                    Component.literal("Casino Dark Mode"),
                    ResourcePackActivationType.NORMAL
            );
        });

        // 8. Data-driven Chips table (data/<ns>/prices/*.json, reloadable via /reload)
        PriceDataLoader.register();

        // 9. Creative Mode Tab — registered here (not as a static field) because
        // Block.asItem() requires the block to be registered first.
        CASINO_TAB = Registry.register(
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
                            emitter.accept(CRAPS_BLOCK.asItem());
                            emitter.accept(WHEEL_OF_FORTUNE_BLOCK.asItem());
                            emitter.accept(KENO_BLOCK.asItem());
                        })
                        .build()
        );

        // 10. Creative Mode Tab stub (no-op — registration happens above)
        registerCreativeTab();

        LOGGER.info("Casino ready: 12 games, sell at 0.8x / buy at 1.2x, bots active");
    }

    private static void registerBlock(String name, Block block) {
        Identifier identifier = id(name);
        Registry.register(BuiltInRegistries.BLOCK, identifier, block);

        // 26.x also requires an explicit id on Item.Properties, for the same reason
        // as blocks: effectiveDescriptionId() reads it during construction.
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, identifier);
        Item.Properties itemProps = new Item.Properties().setId(itemKey);
        Registry.register(BuiltInRegistries.ITEM, identifier, new BlockItem(block, itemProps));
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void registerCreativeTab() {
        // Eagerly registered above as the CASINO_TAB field initializer; nothing to do here.
    }
}
