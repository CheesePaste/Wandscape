package com.wsteam.wandscape;

import com.wsteam.wandscape.engine.BuildingNoSpawnZoneHandler;
import com.wsteam.wandscape.engine.ColonyApiImpl;
import com.wsteam.wandscape.engine.HostileTargetingHandler;
import com.wsteam.wandscape.engine.colony.ColonyLevelData;
import com.wsteam.wandscape.engine.colony.ColonyLevelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.event.level.BlockEvent;
import com.wsteam.wandscape.building.internal.BuildCompleteListener;
import com.wsteam.wandscape.building.internal.DemolishCompleteListener;
import com.wsteam.wandscape.building.internal.BuildingApiImpl;
import com.wsteam.wandscape.building.internal.BuildingBreakHandler;
import com.wsteam.wandscape.building.internal.BuildingInteractHandler;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.DailySettlementSystem;
import com.wsteam.wandscape.stats.internal.StatisticsCollector;
import com.wsteam.wandscape.building.internal.DecorationBonusSystem;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.ShopStockManager;
import com.wsteam.wandscape.building.internal.WonderEffectApplier;
import com.wsteam.wandscape.building.scanner.CreativeScannerBlock;
import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.ScannerBlock;
import com.wsteam.wandscape.building.scanner.ScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.network.ScannerExportPacket;
import com.wsteam.wandscape.building.scanner.network.ScannerSyncPacket;
import com.wsteam.wandscape.command.ColonyCommand;
import com.wsteam.wandscape.command.FillBuildingCommand;
import com.wsteam.wandscape.command.AuditElementsCommand;
import com.wsteam.wandscape.command.GenerateElementMappingsCommand;
import com.wsteam.wandscape.command.LogFilterCommand;
import com.wsteam.wandscape.command.NavTestCommand;
import com.wsteam.wandscape.command.PublishBlueprintCommand;
import com.wsteam.wandscape.command.RecoveryCommand;
import com.wsteam.wandscape.command.SeedWarehouseCommand;
import com.wsteam.wandscape.command.ConsumeWarehouseCommand;
import com.wsteam.wandscape.command.StressTestCommand;
import com.wsteam.wandscape.command.TransportCommand;
import com.wsteam.wandscape.command.TouristCommand;
import com.wsteam.wandscape.magic.entity.MagicBeamEntity;
import com.wsteam.wandscape.magic.internal.MagicCastManager;
import com.wsteam.wandscape.magic.internal.MagicCircleLoader;
import com.wsteam.wandscape.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.magic.internal.SpellcastingApiImpl;
import com.wsteam.wandscape.shared.network.MagicCircleCastPacket;
import com.wsteam.wandscape.road.engine.RoadApiImpl;
import com.wsteam.wandscape.road.engine.RoadSavedData;
import com.wsteam.wandscape.road.engine.RoadSegmentListener;
import com.wsteam.wandscape.engine.boundary.WandscapeBlockInteractExecutor;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.production.ProductionRecipeLoader;
import com.wsteam.wandscape.production.network.CraftingStationPacket;
import com.wsteam.wandscape.production.network.PotionStationPacket;
import com.wsteam.wandscape.production.network.RequestProductionTaskPacket;
import com.wsteam.wandscape.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.building.network.ConstructionSiteDataPacket;
import com.wsteam.wandscape.building.network.HotelOpenPacket;
import com.wsteam.wandscape.building.network.BuildingInfoPacket;
import com.wsteam.wandscape.building.network.AltarCastRequestPacket;
import com.wsteam.wandscape.building.network.AltarOpenPacket;
import com.wsteam.wandscape.building.network.ShopMaxStockPacket;
import com.wsteam.wandscape.building.network.ShopOpenPacket;
import com.wsteam.wandscape.building.network.TavernOpenPacket;
import com.wsteam.wandscape.building.network.TavernRecruitPacket;
import com.wsteam.wandscape.building.network.TownHallOpenPacket;
import com.wsteam.wandscape.building.network.TownHallWarehouseRequestPacket;
import com.wsteam.wandscape.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.building.network.TaskQueueModifyPacket;
import com.wsteam.wandscape.building.network.NodeDataPacket;
import com.wsteam.wandscape.building.network.RequestGatherTaskPacket;
import com.wsteam.wandscape.warehouse.WarehouseManager;
import com.wsteam.wandscape.warehouse.WarehouseNotificationHandler;
import com.wsteam.wandscape.warehouse.network.WarehouseActionPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;
import com.wsteam.wandscape.road.network.DestroyFillPacket;
import com.wsteam.wandscape.road.network.FillBoxPacket;
import com.wsteam.wandscape.road.network.RoadPlacePacket;
import com.wsteam.wandscape.projection.network.ProjectionEnterPacket;
import com.wsteam.wandscape.projection.network.ProjectionEnterResponsePacket;
import com.wsteam.wandscape.projection.network.ProjectionExitPacket;
import com.wsteam.wandscape.projection.network.ProjectionPlacePacket;
import com.wsteam.wandscape.projection.network.ProjectionSlotsRefreshPacket;
import com.wsteam.wandscape.projection.network.ProjectionNetwork;
import com.wsteam.wandscape.projection.network.BuildingDebugRequestPacket;
import com.wsteam.wandscape.projection.network.BuildingDebugResponsePacket;
import com.wsteam.wandscape.projection.network.BuildingActionPacket;
import com.wsteam.wandscape.overview.network.OverviewEntityInteractPacket;
import com.wsteam.wandscape.overview.network.OverviewInteractPacket;

import net.minecraft.commands.Commands;
import com.wsteam.wandscape.engine.source.blueprint.BlueprintConfigLoader;
import com.wsteam.wandscape.task.source.PlayerManualSource;
import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.element.internal.ElementApiImpl;
import com.wsteam.wandscape.element.internal.ElementMappingLoader;
import com.wsteam.wandscape.element.item.ElementItem;
import com.wsteam.wandscape.engine.TaskPoolSavedData;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.bootstrap.EngineBootstrap;
import com.wsteam.wandscape.engine.service.ColonyMetricsService;
import com.wsteam.wandscape.engine.service.ChunkLoadManager;
import com.wsteam.wandscape.npc.entity.EvilMage;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.npc.internal.NpcApiImpl;
import com.wsteam.wandscape.npc.network.NpcDataPacket;
import com.wsteam.wandscape.npc.network.NpcEquipPacket;
import com.wsteam.wandscape.npc.network.NpcRenamePacket;
import com.wsteam.wandscape.npc.network.NpcStrategyPacket;
import com.wsteam.wandscape.npc.network.NpcTogglePacket;
import com.wsteam.wandscape.tourist.entity.TouristEntity;
import com.wsteam.wandscape.tourist.internal.HotelStayHandler;
import com.wsteam.wandscape.tourist.internal.MarkerPreviewManager;
import com.wsteam.wandscape.tourist.internal.TavernApiImpl;
import com.wsteam.wandscape.tourist.internal.TavernRecruitStorage;
import com.wsteam.wandscape.tourist.internal.TouristApiImpl;
import com.wsteam.wandscape.tourist.internal.TouristSimSystem;
import com.wsteam.wandscape.tourist.internal.TouristSpawnSystem;
import com.wsteam.wandscape.tourist.internal.TouristSpotManager;
import com.wsteam.wandscape.tourist.network.TouristDataPacket;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.wand.internal.WandApiImpl;
import com.wsteam.wandscape.wand.internal.WandPresetLoader;
import com.wsteam.wandscape.wand.item.WandItem;
import com.wsteam.wandscape.guidebook.item.GuideBookItem;
import com.wsteam.wandscape.guidebook.network.GuideBookOpenPacket;
import com.wsteam.wandscape.engine.transport.TransportItemEntity;
import com.wsteam.wandscape.engine.transport.TransportStartPacket;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.data.ElementType;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

@Mod(Wandscape.MODID)
public class Wandscape {
    public static final String MODID = "wandscape";
    private static final String TAG = "Wandscape";

    // ---- DeferredRegisters ----
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<TransportItemEntity>> TRANSPORT_ITEM =
            ENTITIES.register("transport_item", () ->
                    EntityType.Builder.<TransportItemEntity>of(TransportItemEntity::new, MobCategory.MISC)
                            .sized(0.25f, 0.25f)
                            .clientTrackingRange(10)
                            .updateInterval(20)
                            .build("transport_item"));
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, MODID);
    public static final DeferredRegister<net.minecraft.world.effect.MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, MODID);
    /** 自定义 EntityDataSerializer 必须注册到 ENTITY_DATA_SERIALIZERS（NeoForge 限制 vanilla 表），否则 SynchedEntityData 取不到 ID。 */
    public static final DeferredRegister<EntityDataSerializer<?>> ENTITY_DATA_SERIALIZERS =
            DeferredRegister.create(NeoForgeRegistries.ENTITY_DATA_SERIALIZERS, MODID);
    /** 光束终点序列化器（Optional<Vec3>），供 MagicBeamEntity 同步身体中心/方块命中点。 */
    public static final DeferredHolder<EntityDataSerializer<?>, EntityDataSerializer<?>> BEAM_TARGET_SERIALIZER =
            ENTITY_DATA_SERIALIZERS.register("beam_target", () -> MagicBeamEntity.OPTIONAL_VEC3);

    // ---- Debug target ----
    public static BlockPos debugDiamondTarget = null;

    // ---- Data loader ----
    public static final WandscapeDataLoader DATA_LOADER = new WandscapeDataLoader();

    // ---- 02 wand-system ----
    public static final DeferredItem<Item> WAND = ITEMS.register("wand",
            () -> new WandItem(new Item.Properties().stacksTo(1)));
    public static final WandPresetLoader WAND_PRESET_LOADER = new WandPresetLoader(DATA_LOADER);
    public static final WandApiImpl WAND_API = new WandApiImpl();

    // ---- 指南书（右键打开教程首页） ----
    public static final DeferredItem<Item> GUIDE_BOOK = ITEMS.register("guide_book",
            () -> new GuideBookItem(new Item.Properties()));

    // ---- 03 element-system ----
    public static final ElementMappingLoader ELEMENT_MAPPING_LOADER = new ElementMappingLoader(DATA_LOADER);
    public static final ElementApiImpl ELEMENT_API = new ElementApiImpl(ELEMENT_MAPPING_LOADER);

    // 元素物品（代表一种元素，供 JEI/配方展示；获得后自动存入所在殖民地仓库）
    public static final Map<ElementType, DeferredItem<Item>> ELEMENT_ITEMS = createElementItems();
    private static Map<ElementType, DeferredItem<Item>> createElementItems() {
        EnumMap<ElementType, DeferredItem<Item>> map = new EnumMap<>(ElementType.class);
        for (ElementType type : ElementType.values()) {
            map.put(type, ITEMS.register("element_" + type.getId(),
                    () -> new ElementItem(new Item.Properties().stacksTo(1), type)));
        }
        return map;
    }

    // ---- magic: magic circle loader (magic_circles 类目) ----
    public static final MagicCircleLoader MAGIC_CIRCLE_LOADER = new MagicCircleLoader(DATA_LOADER);
    // ---- magic: spellbook loader (magic_spells 类目) ----
    public static final SpellbookLoader SPELLBOOK_LOADER = new SpellbookLoader(DATA_LOADER);
    // ---- magic: 施法决策 API（P3 玩家策略 + 条件） ----
    public static final SpellcastingApiImpl SPELLCASTING_API = new SpellcastingApiImpl();

    static {
        WandscapeApis.setSpellcastingApi(SPELLCASTING_API);
    }

    // ---- 10 production-stations: loader ----
    public static ProductionRecipeLoader PRODUCTION_RECIPE_LOADER;

    // ---- 07 npc-system: entity ----
    public static final DeferredHolder<EntityType<?>, EntityType<WandscapeNpc>> WANDSCAPE_NPC =
            ENTITIES.register("wandscape_npc", () ->
                    EntityType.Builder.of(WandscapeNpc::new, MobCategory.CREATURE)
                            .sized(0.6f, 1.8f)
                            .clientTrackingRange(10)
                            .build("wandscape_npc"));

    // ---- 敌对测试法师：与 NPC 法师同外观/属性/施法管线，索敌生存玩家 ----
    public static final DeferredHolder<EntityType<?>, EntityType<EvilMage>> EVIL_MAGE =
            ENTITIES.register("evil_mage", () ->
                    EntityType.Builder.of(EvilMage::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.8f)
                            .clientTrackingRange(10)
                            .build("evil_mage"));

    // ---- 07 npc-system: particles ----
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> CAST_BOLT =
            PARTICLE_TYPES.register("cast_bolt", () -> new SimpleParticleType(false));

    // ---- magic: 法阵可染色点粒子 + 信标光束实体 ----
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> MAGIC_GLOW =
            PARTICLE_TYPES.register("magic_glow", () -> new SimpleParticleType(false));
    public static final DeferredHolder<EntityType<?>, EntityType<MagicBeamEntity>> MAGIC_BEAM =
            ENTITIES.register("magic_beam", () ->
                    EntityType.Builder.<MagicBeamEntity>of(MagicBeamEntity::new, MobCategory.MISC)
                            .sized(0.1f, 0.1f)
                            .clientTrackingRange(16)
                            .updateInterval(2)
                            .build("magic_beam"));

    // ---- 07 npc-system: spawn egg ----
    public static final DeferredItem<Item> WANDSCAPE_NPC_EGG =
            ITEMS.register("wandscape_npc_spawn_egg", () ->
                    new DeferredSpawnEggItem(
                            () -> (EntityType<? extends Mob>) (EntityType<?>) WANDSCAPE_NPC.get(),
                            0x4B0082,  // dark purple background
                            0xFFD700,  // gold highlight
                            new Item.Properties()));

    // ---- 敌对测试法师 spawn egg（深红/黑） ----
    public static final DeferredItem<Item> EVIL_MAGE_SPAWN_EGG =
            ITEMS.register("evil_mage_spawn_egg", () ->
                    new DeferredSpawnEggItem(
                            () -> (EntityType<? extends Mob>) (EntityType<?>) EVIL_MAGE.get(),
                            0x8B0000,  // dark red background
                            0x1A1A1A,  // black highlight
                            new Item.Properties()));

    // ---- tourist-system: entity ----
    public static final DeferredHolder<EntityType<?>, EntityType<TouristEntity>> TOURIST =
            ENTITIES.register("tourist", () ->
                    EntityType.Builder.of(TouristEntity::new, MobCategory.CREATURE)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(10)
                            .build("tourist"));

    // ---- tourist-system: spawn egg ----
    public static final DeferredItem<Item> TOURIST_SPAWN_EGG =
            ITEMS.register("tourist_spawn_egg", () ->
                    new DeferredSpawnEggItem(
                            () -> (EntityType<? extends Mob>) (EntityType<?>) TOURIST.get(),
                            0xFFAA00,  // orange background
                            0xFFFFFF,  // white highlight
                            new Item.Properties()));

    // ---- building-scanner blocks ----
    // Creative Building Scanner (full-featured, for creators) — renamed from building_scanner to
    // creative_building_scanner; the plain id "building_scanner" now belongs to the Survival scanner.
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    public static final DeferredHolder<Block, Block> CREATIVE_BUILDING_SCANNER = BLOCKS.register("creative_building_scanner",
            () -> (Block) new CreativeScannerBlock(BlockBehaviour.Properties.of().strength(2.0f).noOcclusion(),
                    Wandscape.CREATIVE_BUILDING_SCANNER_BE::get));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CreativeScannerBlockEntity>> CREATIVE_BUILDING_SCANNER_BE =
            BLOCK_ENTITY_TYPES.register("creative_building_scanner",
                    () -> BlockEntityType.Builder.of(
                            CreativeScannerBlockEntity::new,
                            CREATIVE_BUILDING_SCANNER.get()).build(null));

    public static final DeferredItem<Item> CREATIVE_BUILDING_SCANNER_ITEM =
            ITEMS.register("creative_building_scanner", () -> new BlockItem(CREATIVE_BUILDING_SCANNER.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> BUILDING_SCANNER = BLOCKS.register("building_scanner",
            () -> (Block) new ScannerBlock(BlockBehaviour.Properties.of().strength(2.0f).noOcclusion(),
                    Wandscape.BUILDING_SCANNER_BE::get));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ScannerBlockEntity>> BUILDING_SCANNER_BE =
            BLOCK_ENTITY_TYPES.register("building_scanner",
                    () -> BlockEntityType.Builder.of(
                            ScannerBlockEntity::new,
                            BUILDING_SCANNER.get()).build(null));

    public static final DeferredItem<Item> BUILDING_SCANNER_ITEM =
            ITEMS.register("building_scanner", () -> new BlockItem(BUILDING_SCANNER.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> INTERACT_SPOT_MARKER = BLOCKS.register("interact_spot_marker",
            () -> (Block) new com.wsteam.wandscape.building.scanner.InteractSpotMarkerBlock(
                    BlockBehaviour.Properties.of().strength(2.0f).noOcclusion()));
    public static final DeferredItem<Item> INTERACT_SPOT_MARKER_ITEM =
            ITEMS.register("interact_spot_marker", () -> new BlockItem(INTERACT_SPOT_MARKER.get(), new Item.Properties()));

    // ---- Creative tab ----
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> WANDSCAPE_TAB =
            CREATIVE_MODE_TABS.register("wandscape_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.wandscape"))
                    .icon(() -> new ItemStack(WAND.get()))
                    .displayItems((params, output) -> {
                        output.accept(WAND.get());
                        output.accept(WANDSCAPE_NPC_EGG.get());
                        output.accept(EVIL_MAGE_SPAWN_EGG.get());
                        output.accept(TOURIST_SPAWN_EGG.get());
                        output.accept(CREATIVE_BUILDING_SCANNER_ITEM.get());
                        output.accept(BUILDING_SCANNER_ITEM.get());
                        output.accept(INTERACT_SPOT_MARKER_ITEM.get());
                        output.accept(GUIDE_BOOK.get());
                        ELEMENT_ITEMS.values().forEach(item -> output.accept(item.get()));
                    })
                    .build());

    // ---- API instances ----
    private final BuildingApiImpl buildingApi = new BuildingApiImpl();
    private final BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();
    public static final BlueprintConfigLoader BLUEPRINT_CONFIG_LOADER = new BlueprintConfigLoader();
    public static final com.wsteam.wandscape.road.data.RoadPresetLoader ROAD_PRESET_LOADER =
            com.wsteam.wandscape.road.data.RoadPresetLoader.getInstance();
    private DecorationBonusSystem decorationBonusSystem;
    private ShopStockManager shopStockManager;
    private WonderEffectApplier wonderEffectApplier;
    private TavernApiImpl tavernApi;

    public Wandscape(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onEntityAttributeCreation);
        modEventBus.addListener(this::onRegisterPayloads);

        ITEMS.register(modEventBus);
        ENTITIES.register(modEventBus);
        PARTICLE_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        BLOCKS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MOB_EFFECTS.register(modEventBus);
        ENTITY_DATA_SERIALIZERS.register(modEventBus);
        WandscapeSounds.SOUNDS.register(modEventBus);
        com.wsteam.wandscape.magic.internal.WandscapeEffects.PETRIFICATION.getId();

        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(HostileTargetingHandler.class);
        NeoForge.EVENT_BUS.register(BuildingNoSpawnZoneHandler.class);
        NeoForge.EVENT_BUS.register(com.wsteam.wandscape.guard.SelfDefenseHandler.class);
        NeoForge.EVENT_BUS.register(com.wsteam.wandscape.guard.NpcSpellPowerHandler.class);
        NeoForge.EVENT_BUS.register(com.wsteam.wandscape.npc.internal.NpcDeathHandler.class);
        NeoForge.EVENT_BUS.register(BuildingInteractHandler.class);
        NeoForge.EVENT_BUS.register(BuildingBreakHandler.class);
        NeoForge.EVENT_BUS.register(com.wsteam.wandscape.shared.network.PanelStateTracker.class);
        DailySettlementSystem.register();
        StatisticsCollector.register();
        decorationBonusSystem = DecorationBonusSystem.register();
        shopStockManager = ShopStockManager.register();
        wonderEffectApplier = WonderEffectApplier.register();
        BuildingInteractHandler.setShopStockManager(shopStockManager);
        TouristSpawnSystem.register();
        HotelStayHandler.register();
        MarkerPreviewManager.register();
        WarehouseNotificationHandler.register();

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Keep log verbosity in sync with the config (applies on load and reload)
        modEventBus.addListener(this::onModConfig);

        // Register API implementations
        WandscapeApis.setBuildingApi(buildingApi);
        WandscapeApis.setNpcApi(new NpcApiImpl());
        WandscapeApis.setWarehouseApi(new WarehouseManager());
        WandscapeApis.setColonyApi(ColonyApiImpl.get());
        WandscapeApis.setTouristApi(new TouristApiImpl());
        tavernApi = new TavernApiImpl();
        WandscapeApis.setTavernApi(tavernApi);

        // Register config loaders with data loader
        configLoader.registerWith(DATA_LOADER);
        BLUEPRINT_CONFIG_LOADER.registerWith(DATA_LOADER);
        ROAD_PRESET_LOADER.registerWith(DATA_LOADER);
        WandscapeEngine.setBlueprintConfigLoader(BLUEPRINT_CONFIG_LOADER);

        // Production recipe loader
        PRODUCTION_RECIPE_LOADER = new ProductionRecipeLoader(DATA_LOADER, ELEMENT_MAPPING_LOADER);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            WandscapeClient.init(modEventBus, modContainer);
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        WandscapeApis.setWandApi(WAND_API);
        WandscapeApis.setElementApi(ELEMENT_API);
        Log.info(TAG, "Wandscape common setup — wand, element, buildings, npc ready");
    }

    private void onModConfig(ModConfigEvent event) {
        Log.setVerbose(Config.DEBUG.get());
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(MODID)
                .versioned("1.0")
                .playToClient(
                        WarehouseDataPacket.TYPE,
                        WarehouseDataPacket.STREAM_CODEC,
                        (packet, ctx) -> WarehouseDataPacket.handleClient(packet))
                .playToClient(
                        WorkstationDataPacket.TYPE,
                        WorkstationDataPacket.STREAM_CODEC,
                        (packet, ctx) -> WorkstationDataPacket.handleClient(packet))
                .playToClient(
                        CraftingStationPacket.TYPE,
                        CraftingStationPacket.STREAM_CODEC,
                        (packet, ctx) -> CraftingStationPacket.handleClient(packet))
                .playToClient(
                        ShopOpenPacket.TYPE,
                        ShopOpenPacket.STREAM_CODEC,
                        (packet, ctx) -> ShopOpenPacket.handleClient(packet))
                .playToClient(
                        com.wsteam.wandscape.building.network.BuildingConfigSyncChunkPacket.TYPE,
                        com.wsteam.wandscape.building.network.BuildingConfigSyncChunkPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.building.network.BuildingConfigSyncChunkPacket.handleClient(packet))
                .playToServer(
                        ShopMaxStockPacket.TYPE,
                        ShopMaxStockPacket.STREAM_CODEC,
                        (packet, ctx) -> ShopMaxStockPacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToClient(
                        TavernOpenPacket.TYPE,
                        TavernOpenPacket.STREAM_CODEC,
                        (packet, ctx) -> TavernOpenPacket.handleClient(packet))
                .playToClient(
                        HotelOpenPacket.TYPE,
                        HotelOpenPacket.STREAM_CODEC,
                        (packet, ctx) -> HotelOpenPacket.handleClient(packet))
                .playToClient(
                        BuildingInfoPacket.TYPE,
                        BuildingInfoPacket.STREAM_CODEC,
                        (packet, ctx) -> BuildingInfoPacket.handleClient(packet))
                .playToClient(
                        TownHallOpenPacket.TYPE,
                        TownHallOpenPacket.STREAM_CODEC,
                        (packet, ctx) -> TownHallOpenPacket.handleClient(packet))
                .playToServer(
                        TownHallWarehouseRequestPacket.TYPE,
                        TownHallWarehouseRequestPacket.STREAM_CODEC,
                        (packet, ctx) -> TownHallWarehouseRequestPacket.handleServer(packet, ctx))
                .playToClient(
                        AltarOpenPacket.TYPE,
                        AltarOpenPacket.STREAM_CODEC,
                        (packet, ctx) -> AltarOpenPacket.handleClient(packet))
                .playToServer(
                        AltarCastRequestPacket.TYPE,
                        AltarCastRequestPacket.STREAM_CODEC,
                        (packet, ctx) -> AltarCastRequestPacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToClient(
                        PotionStationPacket.TYPE,
                        PotionStationPacket.STREAM_CODEC,
                        (packet, ctx) -> PotionStationPacket.handleClient(packet))
                .playToClient(
                        TaskQueueDataPacket.TYPE,
                        TaskQueueDataPacket.STREAM_CODEC,
                        (packet, ctx) -> TaskQueueDataPacket.handleClient(packet))
                // ── Construction-site panel (under-construction building) ──
                .playToClient(
                        ConstructionSiteDataPacket.TYPE,
                        ConstructionSiteDataPacket.STREAM_CODEC,
                        (packet, ctx) -> ConstructionSiteDataPacket.handleClient(packet))
                .playToServer(
                        RoadPlacePacket.TYPE,
                        RoadPlacePacket.STREAM_CODEC,
                        (packet, ctx) -> RoadPlacePacket.handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        DestroyFillPacket.TYPE,
                        DestroyFillPacket.STREAM_CODEC,
                        (packet, ctx) -> DestroyFillPacket.handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        FillBoxPacket.TYPE,
                        FillBoxPacket.STREAM_CODEC,
                        (packet, ctx) -> FillBoxPacket.handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        RequestProductionTaskPacket.TYPE,
                        RequestProductionTaskPacket.STREAM_CODEC,
                        RequestProductionTaskPacket::handleServer)
                .playToServer(
                        TaskQueueModifyPacket.TYPE,
                        TaskQueueModifyPacket.STREAM_CODEC,
                        TaskQueueModifyPacket::handleServer)
                .playToClient(
                        NodeDataPacket.TYPE,
                        NodeDataPacket.STREAM_CODEC,
                        (packet, ctx) -> NodeDataPacket.handleClient(packet))
                .playToServer(
                        RequestGatherTaskPacket.TYPE,
                        RequestGatherTaskPacket.STREAM_CODEC,
                        RequestGatherTaskPacket::handleServer)
                .playToServer(
                        WarehouseActionPacket.TYPE,
                        WarehouseActionPacket.STREAM_CODEC,
                        WarehouseActionPacket::handleServer)
                .playToServer(
                        TavernRecruitPacket.TYPE,
                        TavernRecruitPacket.STREAM_CODEC,
                        TavernRecruitPacket::handleServer)
                // ── Soul Projection ──
                .playToServer(
                        ProjectionEnterPacket.TYPE,
                        ProjectionEnterPacket.STREAM_CODEC,
                        (packet, ctx) -> ProjectionEnterPacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToClient(
                        ProjectionEnterResponsePacket.TYPE,
                        ProjectionEnterResponsePacket.STREAM_CODEC,
                        (packet, ctx) -> ProjectionEnterResponsePacket.handleClient(packet))
                .playToServer(
                        ProjectionExitPacket.TYPE,
                        ProjectionExitPacket.STREAM_CODEC,
                        (packet, ctx) -> ProjectionExitPacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        ProjectionPlacePacket.TYPE,
                        ProjectionPlacePacket.STREAM_CODEC,
                        (packet, ctx) -> ProjectionPlacePacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToClient(
                        ProjectionSlotsRefreshPacket.TYPE,
                        ProjectionSlotsRefreshPacket.STREAM_CODEC,
                        (packet, ctx) -> ProjectionSlotsRefreshPacket.handleClient(packet))
                // ── Overview ──
                .playToServer(
                        OverviewInteractPacket.TYPE,
                        OverviewInteractPacket.STREAM_CODEC,
                        (packet, ctx) -> OverviewInteractPacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        OverviewEntityInteractPacket.TYPE,
                        OverviewEntityInteractPacket.STREAM_CODEC,
                        (packet, ctx) -> OverviewEntityInteractPacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        BuildingDebugRequestPacket.TYPE,
                        BuildingDebugRequestPacket.STREAM_CODEC,
                        (packet, ctx) -> BuildingDebugRequestPacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToClient(
                        BuildingDebugResponsePacket.TYPE,
                        BuildingDebugResponsePacket.STREAM_CODEC,
                        (packet, ctx) -> BuildingDebugResponsePacket.handleClient(packet))
                .playToServer(
                        BuildingActionPacket.TYPE,
                        BuildingActionPacket.STREAM_CODEC,
                        (packet, ctx) -> BuildingActionPacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                // ── Building Scanner ──
                .playToServer(
                        ScannerSyncPacket.TYPE,
                        ScannerSyncPacket.STREAM_CODEC,
                        (packet, ctx) -> ScannerSyncPacket.handleServer(packet,
                                (ServerPlayer) ctx.player()))
                .playToServer(
                        ScannerExportPacket.TYPE,
                        ScannerExportPacket.STREAM_CODEC,
                        (packet, ctx) -> ScannerExportPacket.handleServer(packet,
                                (ServerPlayer) ctx.player()))
                .playToServer(
                        com.wsteam.wandscape.building.scanner.network.ScannerValuePacket.TYPE,
                        com.wsteam.wandscape.building.scanner.network.ScannerValuePacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.building.scanner.network.ScannerValuePacket.handleServer(packet,
                                (ServerPlayer) ctx.player()))
                .playToServer(
                        com.wsteam.wandscape.road.network.SplineBuildPacket.TYPE,
                        com.wsteam.wandscape.road.network.SplineBuildPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.road.network.SplineBuildPacket.handleServer(packet, (ServerPlayer) ctx.player()))
                // ── Wandscape Panel ──
                .playToServer(
                        com.wsteam.wandscape.shared.network.PanelStateTogglePacket.TYPE,
                        com.wsteam.wandscape.shared.network.PanelStateTogglePacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.shared.network.PanelStateTogglePacket
                                .handleServer(packet, (ServerPlayer) ctx.player()))
                .playToClient(
                        com.wsteam.wandscape.shared.network.ColonyStatsSyncPacket.TYPE,
                        com.wsteam.wandscape.shared.network.ColonyStatsSyncPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.shared.network.ColonyStatsSyncPacket
                                .handleClient(packet))
                // ── Stats ──
                .playToClient(
                        com.wsteam.wandscape.stats.network.StatsSyncPacket.TYPE,
                        com.wsteam.wandscape.stats.network.StatsSyncPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.stats.network.StatsSyncPacket
                                .handleClient(packet))
                // ── Building interaction area overlay ──
                .playToClient(
                        com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket.TYPE,
                        com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket
                                .handleClient(packet))
                // ── Road construction ghost sync ──
                .playToClient(
                        com.wsteam.wandscape.shared.network.RoadAreaSyncPacket.TYPE,
                        com.wsteam.wandscape.shared.network.RoadAreaSyncPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.shared.network.RoadAreaSyncPacket
                                .handleClient(packet))
                // ── NPC info screen ──
                .playToClient(
                        NpcDataPacket.TYPE,
                        NpcDataPacket.STREAM_CODEC,
                        (packet, ctx) -> NpcDataPacket.handleClient(packet))
                .playToServer(
                        NpcEquipPacket.TYPE,
                        NpcEquipPacket.STREAM_CODEC,
                        (packet, ctx) -> NpcEquipPacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        NpcStrategyPacket.TYPE,
                        NpcStrategyPacket.STREAM_CODEC,
                        (packet, ctx) -> NpcStrategyPacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        NpcRenamePacket.TYPE,
                        NpcRenamePacket.STREAM_CODEC,
                        (packet, ctx) -> NpcRenamePacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        NpcTogglePacket.TYPE,
                        NpcTogglePacket.STREAM_CODEC,
                        (packet, ctx) -> NpcTogglePacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                // ── Tourist info screen ──
                .playToClient(
                        TouristDataPacket.TYPE,
                        TouristDataPacket.STREAM_CODEC,
                        (packet, ctx) -> TouristDataPacket.handleClient(packet))
                // ── Tourist purchase / service bubble ──
                .playToClient(
                        com.wsteam.wandscape.tourist.network.TouristBubblePacket.TYPE,
                        com.wsteam.wandscape.tourist.network.TouristBubblePacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.tourist.network.TouristBubblePacket.handleClient(packet))
                // ── Colony day/night ambient ──
                .playToClient(
                        com.wsteam.wandscape.shared.network.ColonyAmbientPacket.TYPE,
                        com.wsteam.wandscape.shared.network.ColonyAmbientPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.shared.network.ColonyAmbientPacket
                                .handleClient(packet))
                // ── Colony name update ──
                .playToServer(
                        com.wsteam.wandscape.shared.network.ColonyNameUpdatePacket.TYPE,
                        com.wsteam.wandscape.shared.network.ColonyNameUpdatePacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.shared.network.ColonyNameUpdatePacket
                                .handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                // ── Colony create (town hall naming flow) ──
                .playToServer(
                        com.wsteam.wandscape.shared.network.ColonyCreateRequestPacket.TYPE,
                        com.wsteam.wandscape.shared.network.ColonyCreateRequestPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.shared.network.ColonyCreateRequestPacket
                                .handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToClient(
                        com.wsteam.wandscape.shared.network.ColonyCreatePromptPacket.TYPE,
                        com.wsteam.wandscape.shared.network.ColonyCreatePromptPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.shared.network.ColonyCreatePromptPacket
                                .handleClient(packet))
                // ── Transport start ──
                .playToClient(
                        TransportStartPacket.TYPE,
                        TransportStartPacket.STREAM_CODEC,
                        (packet, ctx) -> TransportStartPacket.handleClient(packet))
                // ── Magic circle cast ──
                .playToClient(
                        MagicCircleCastPacket.TYPE,
                        MagicCircleCastPacket.STREAM_CODEC,
                        (packet, ctx) -> MagicCircleCastPacket.handleClient(packet))
                // ── Particle burst (colored FX) ──
                .playToClient(
                        com.wsteam.wandscape.shared.network.ParticleBurstPacket.TYPE,
                        com.wsteam.wandscape.shared.network.ParticleBurstPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.shared.network.ParticleBurstPacket.handleClient(packet))
                // ── Guide test ──
                .playToClient(
                        com.wsteam.wandscape.shared.network.GuideTestPacket.TYPE,
                        com.wsteam.wandscape.shared.network.GuideTestPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.shared.network.GuideTestPacket.handleClient(packet))
                // ── Guide book (right-click to open tutorial home) ──
                .playToClient(
                        GuideBookOpenPacket.TYPE,
                        GuideBookOpenPacket.STREAM_CODEC,
                        (packet, ctx) -> GuideBookOpenPacket.handleClient(packet))
                // ── Guide progress (onboarding persistence) ──
                .playToClient(
                        com.wsteam.wandscape.shared.network.GuideProgressSyncPacket.TYPE,
                        com.wsteam.wandscape.shared.network.GuideProgressSyncPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.shared.network.GuideProgressSyncPacket.handleClient(packet))
                .playToServer(
                        com.wsteam.wandscape.shared.network.GuideProgressUpdatePacket.TYPE,
                        com.wsteam.wandscape.shared.network.GuideProgressUpdatePacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.shared.network.GuideProgressUpdatePacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                // ── Spline Road Editor ──
                .playToClient(
                        com.wsteam.wandscape.road.network.SplineEditorEnterPacket.TYPE,
                        com.wsteam.wandscape.road.network.SplineEditorEnterPacket.STREAM_CODEC,
                        com.wsteam.wandscape.road.network.SplineEditorEnterPacket::handleClient);
    }

    private void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(WANDSCAPE_NPC.get(), WandscapeNpc.createAttributes().build());
        event.put(EVIL_MAGE.get(), WandscapeNpc.createAttributes().build());
        event.put(TOURIST.get(), TouristEntity.createAttributes().build());
    }

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(DATA_LOADER);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        Log.info(TAG, "Wandscape server starting — bootstrapping engine...");
        buildingApi.setLevel(event.getServer().overworld());
        EngineBootstrap.bootstrap();

        // Register unified metrics facade (after bootstrap, before any consumer queries it)
        var metricsService = ColonyMetricsService.create();
        com.wsteam.wandscape.shared.registry.WandscapeApis.setColonyMetricsApi(metricsService);
        Log.info(TAG, "ColonyMetricsService registered");

        // Register server-authoritative tutorial progress evaluator
        com.wsteam.wandscape.shared.registry.WandscapeApis.setGuideProgressApi(
                new com.wsteam.wandscape.engine.service.GuideProgressService());
        Log.info(TAG, "GuideProgressService registered");

        BuildCompleteListener.register();
        DemolishCompleteListener.register();
        // Rebuild colony spatial index from saved data
        var colonyApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
        if (colonyApi instanceof ColonyApiImpl impl) {
            impl.rebuildFromSavedData();
        }

        // Wire decoration bonus cache to contribution registry
        if (decorationBonusSystem != null) {
            var savedData = BuildingSavedData.get(event.getServer().overworld());
            if (savedData != null && savedData.getContributionRegistry() != null) {
                savedData.getContributionRegistry()
                        .setDecorationBonusCache(decorationBonusSystem.getCache());
            }
        }

        // Wire production loaders to block interact executor
        WandscapeBlockInteractExecutor.setElementMappingLoader(ELEMENT_MAPPING_LOADER);
        WandscapeBlockInteractExecutor.setProductionRecipeLoader(PRODUCTION_RECIPE_LOADER);

        // Load element seeds for Workstation decomposition
        try {
            var cl = Wandscape.class.getClassLoader();
            var is = cl.getResourceAsStream("data/wandscape/element_seeds.json");
            if (is != null) {
                String seedJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                is.close();
                ELEMENT_MAPPING_LOADER.loadSeedValues(seedJson);
                Log.info(TAG, "Loaded {} element seeds for decomposition",
                        ELEMENT_MAPPING_LOADER.getSeedCount());
            } else {
                Log.warn(TAG, "element_seeds.json not found on classpath");
            }
        } catch (Exception e) {
            Log.warn(TAG, "Failed to load element_seeds.json: {}", e.getMessage());
        }

        // ---- Road system ----
        RoadSegmentListener.register();

        // Load persisted tasks from previous session
        ServerLevel level = event.getServer().overworld();
        var world = WandscapeEngine.getWorld();
        if (world != null && world.taskPool != null) {
            var saved = TaskPoolSavedData.getOrCreate(level, world.taskPool);
            WandscapeEngine.setTaskPoolSavedData(saved);
            // Mark dirty when pool changes so SavedData writes to disk
            world.taskPool.onChanged = saved::setDirty;
            Log.info(TAG, "Task persistence wired — pool has {} active tasks", world.taskPool.size());
        }

        // Chunk load manager — force-loads active building footprints on demand so
        // colonies keep building/producing while their chunks are unloaded.
        ChunkLoadManager.get().init(level);

        // Road persistence + API
        var roadSaved = RoadSavedData.getOrCreate(level);
        WandscapeEngine.setRoadSavedData(roadSaved);
        WandscapeApis.setRoadApi(new RoadApiImpl());
        Log.info(TAG, "Road system wired — {} edges persisted", roadSaved.getNetwork().edgeCount());

        // Tavern recruit storage
        var recruitStorage = TavernRecruitStorage.getOrCreate(level);
        tavernApi.setStorage(recruitStorage);
        Log.info(TAG, "Tavern recruit storage wired");

        // Colony level data
        var colonyLevelData = ColonyLevelData.getOrCreate(level);
        var colonyLevelManager = new ColonyLevelManager(colonyLevelData);
        WandscapeEngine.setColonyLevelManager(colonyLevelManager);

        // Wire level-up event to engine EventBus
        if (world != null && world.eventBus != null) {
            colonyLevelManager.setLevelUpCallback(evt -> world.eventBus.emit(evt));
        }

        TouristSpawnSystem.setLevelManager(colonyLevelManager);
        Log.info(TAG, "Colony level system wired");

        // Tourist sim — drives unloaded tourists from data shadows.
        TouristSimSystem.register(level);
        Log.info(TAG, "Tourist sim system wired");

        // Wire manual task publishing for GUI (network layer reads PlayerManualSource from engine)
        if (world != null && world.taskPool != null) {
            PlayerManualSource playerSource = new PlayerManualSource(world.taskPool);
            WandscapeEngine.setPlayerManualSource(playerSource);
            Log.info(TAG, "PlayerManualSource wired — manual task publishing available");
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        Log.info(TAG, "Wandscape server stopped — resetting engine.");
        buildingApi.setLevel(null);
        ChunkLoadManager.get().reset();
        TouristSimSystem.reset();
        TouristSpotManager.getActive().clear(); // 静态单例跨世界存活，需清空幽灵占位/排队
        WandscapeEngine.reset();
        EntityComponentBridge.INSTANCE.clear();
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getState().is(Blocks.DIAMOND_BLOCK)) {
            debugDiamondTarget = event.getPos();
            Log.info(TAG, "[Debug] Diamond block placed at {}", debugDiamondTarget);
        }
    }

    private int engineTickCount = 0;
    private int mcTickCount = 0;

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        var root = Commands.literal("wandscape")
                .then(GenerateElementMappingsCommand.node())
                .then(AuditElementsCommand.node())
                .then(LogFilterCommand.node())
                .then(FillBuildingCommand.fillNode())
                .then(NavTestCommand.node())
                .then(ColonyCommand.node())
                .then(PublishBlueprintCommand.buildNode())
                .then(RecoveryCommand.node())
                .then(SeedWarehouseCommand.node())
                .then(ConsumeWarehouseCommand.node())
                .then(StressTestCommand.buildNode())
                .then(TouristCommand.node())
                .then(com.wsteam.wandscape.command.TavernCommand.node())
                .then(TransportCommand.node())
                .then(com.wsteam.wandscape.guard.GuardCommand.node())
                .then(com.wsteam.wandscape.command.GuideCommand.node())
                .then(com.wsteam.wandscape.command.SplineEditorCommand.node())
                .then(com.wsteam.wandscape.command.MagicCommand.node());
        dispatcher.register(root);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            ProjectionNetwork.removeByUuid(sp.getUUID());
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        // Colony ambient: 建筑包围盒+20格内玩家昼夜环境音门控（服务端判断+发包）
        com.wsteam.wandscape.building.internal.ColonyAmbientTracker.tick(event.getServer());

        // Magic cast: 法阵动画结束后生成信标光束（不依赖 ECS）
        MagicCastManager.tick();

        // Altar: 每 tick 推进所有祭坛的魔法冷却（SavedData，按祭坛独立）
        com.wsteam.wandscape.building.internal.AltarCastHandler.tick(event.getServer().overworld());

        var world = WandscapeEngine.getWorld();
        if (world == null) return;

        mcTickCount++;

        // ① Tick async executor countdowns
        var asyncExec = WandscapeEngine.getAsyncExecutor();
        if (asyncExec != null) asyncExec.tickAll();

        // ①b Tick async block interaction countdowns (gather/decompose/synthesize)
        var blockInteractExec = WandscapeEngine.getBlockInteractExec();
        if (blockInteractExec != null) blockInteractExec.tickAll();

        // ①c Tick async ritual channeling countdowns (self_teleport, etc.)
        var ritualOps = WandscapeEngine.getRitualOps();
        if (ritualOps != null) ritualOps.tickAll();

        // ①d Drive item transport animations (visual item flight warehouse→NPC)
        var transporter = WandscapeEngine.getTransporter();
        if (transporter != null) transporter.tickAll();

        // ①e Drive resource request staggered launches (1 item/tick from warehouse)
        var resourceReqExec = WandscapeEngine.getResourceRequestExec();
        if (resourceReqExec != null) resourceReqExec.tickAll();

        // ①f Tick guard combat sustained loops (cast → wait beam → retarget → complete)
        var guardExec = WandscapeEngine.getGuardExecutor();
        if (guardExec != null) guardExec.tickAll();

        // ①g Tick NPC self-defense (proactive aggro scan + retaliation loop; preempts current task)
        var selfDefenseExec = WandscapeEngine.getSelfDefenseExecutor();
        if (selfDefenseExec != null) selfDefenseExec.tick(world);

        // ①g1 Tick projectile dodge (walk away from incoming hostile arrows/skulls; throttled)
        com.wsteam.wandscape.guard.ProjectileDodge.tick(world);

        // ①g2 Tick altar cast channeling countdowns (altar-only magic channel → effect fire)
        var altarCastExec = WandscapeEngine.getAltarCastExecutor();
        if (altarCastExec != null) altarCastExec.tickAll();

        // ①h Tick raid trigger scanner + victory tracker (colonies live in the overworld)
        var raidLevel = event.getServer().overworld();
        if (raidLevel != null) {
            com.wsteam.wandscape.raid.RaidTriggerScanner.INSTANCE.tick(raidLevel);
            com.wsteam.wandscape.raid.ColonyRaidTracker.INSTANCE.tick(raidLevel);
        }

        // ② Sync MC entity positions → ECS
        EntityComponentBridge.INSTANCE.syncPositions(world);

        // ②b Flush any NPCs that loaded before the engine was ready
        EntityComponentBridge.INSTANCE.flushDeferredJoins(world);

        // ③ Engine logic tick (incl. NavigationSystem which drives movement)
        engineTickCount++;
        world.tick(1.0f);

        // Heartbeat every ~5 seconds (100 MC ticks)
        if (mcTickCount % 100 == 0) {
            var colonyApi = WandscapeApis.getColonyApiSilently();
            if (colonyApi != null) {
                var overworld = event.getServer().overworld();
                if (overworld != null) {
                    for (var colonyId : colonyApi.getAllColonyIds()) {
                        com.wsteam.wandscape.npc.internal.ReviveHandler.checkAndAutoReviveColony(overworld, colonyId);
                    }
                }
            }
            Log.info(TAG, "[Engine] engineTick=#{} mcTick=#{} — entities={} tasks_in_pool={} pendingAsync={}",
                    engineTickCount, mcTickCount,
                    world.getNextEntityId() - 1,
                    world.taskPool != null ? world.taskPool.size() : 0,
                    world.hasPendingAsyncOps() ? 1 : 0);
        }
    }

    @SubscribeEvent
    public void onDatapackSync(net.neoforged.neoforge.event.OnDatapackSyncEvent event) {
        var rawJsons = configLoader.getRawJsons();
        java.util.List<String> jsonList = new java.util.ArrayList<>();
        for (var json : rawJsons.values()) {
            jsonList.add(json.toString());
        }
        int totalConfigs = jsonList.size();
        int totalChunksSent = 0;
        int totalCompressedBytes = 0;
        int configIndex = 0;
        for (String json : jsonList) {
            byte[] compressed = com.wsteam.wandscape.building.network.BuildingConfigCompressor.compress(json);
            totalCompressedBytes += compressed.length;
            int totalChunks = Math.max(1, (compressed.length
                    + com.wsteam.wandscape.building.network.BuildingConfigSyncChunkPacket.CHUNK_BYTES - 1)
                    / com.wsteam.wandscape.building.network.BuildingConfigSyncChunkPacket.CHUNK_BYTES);
            int chunkIndex = 0;
            for (int off = 0; off < compressed.length; off += com.wsteam.wandscape.building.network.BuildingConfigSyncChunkPacket.CHUNK_BYTES) {
                int len = Math.min(com.wsteam.wandscape.building.network.BuildingConfigSyncChunkPacket.CHUNK_BYTES,
                        compressed.length - off);
                var pkt = new com.wsteam.wandscape.building.network.BuildingConfigSyncChunkPacket(
                        configIndex, chunkIndex, totalChunks, totalConfigs,
                        java.util.Arrays.copyOfRange(compressed, off, off + len));
                if (event.getPlayer() != null) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(event.getPlayer(), pkt);
                } else {
                    net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(pkt);
                }
                chunkIndex++;
                totalChunksSent++;
            }
            configIndex++;
        }
        Log.info(TAG, "Synced {} building configs ({} chunks, {} compressed bytes) on DatapackSync",
                totalConfigs, totalChunksSent, totalCompressedBytes);
    }
}
