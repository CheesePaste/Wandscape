package com.wsteam.wandscape;
import com.wsteam.wandscape.content.colony.sound.ColonyAmbientTracker;
import com.wsteam.wandscape.content.command.*;
import com.wsteam.wandscape.content.npc.HostileTargetingHandler;
import com.wsteam.wandscape.content.task.TaskPoolSavedData;
import com.wsteam.wandscape.content.building.BuildingNoSpawnZoneHandler;
import com.wsteam.wandscape.content.colony.ColonyApiImpl;

import com.wsteam.wandscape.content.building.internal.*;
import com.wsteam.wandscape.content.building.network.*;
import com.wsteam.wandscape.foundation.registry.dataconfig.internal.DatapackDataSyncChunkPacket;
import com.wsteam.wandscape.content.building.scanner.CreativeScannerBlock;
import com.wsteam.wandscape.content.building.scanner.CreativeScannerBlockEntity;
import com.wsteam.wandscape.content.building.scanner.ScannerBlock;
import com.wsteam.wandscape.content.building.scanner.ScannerBlockEntity;
import com.wsteam.wandscape.content.building.scanner.InteractSpotMarkerBlock;
import com.wsteam.wandscape.content.building.scanner.network.ScannerExportPacket;
import com.wsteam.wandscape.content.building.scanner.network.ScannerSyncPacket;
import com.wsteam.wandscape.content.building.projection.network.*;
import com.wsteam.wandscape.content.building.scanner.network.ScannerValuePacket;
import com.wsteam.wandscape.content.colony.raid.ColonyRaidTracker;
import com.wsteam.wandscape.content.colony.raid.RaidTriggerScanner;
import com.wsteam.wandscape.content.colony.stats.network.StatsSyncPacket;
import com.wsteam.wandscape.content.items.compass.CompassSyncHandler;
import com.wsteam.wandscape.content.items.compass.CompassTier;
import com.wsteam.wandscape.content.items.compass.MagicCompassItem;
import com.wsteam.wandscape.content.items.compass.network.CompassTargetPacket;
import com.wsteam.wandscape.content.items.oathring.OathRingItem;
import com.wsteam.wandscape.content.items.oathring.RingTier;
import com.wsteam.wandscape.content.items.oathring.internal.OathRingSyncHandler;
import com.wsteam.wandscape.content.items.oathring.network.OathRingDataPacket;
import com.wsteam.wandscape.content.items.scepter.OmniScepterItem;
import com.wsteam.wandscape.content.items.scepter.ScepterItem;
import com.wsteam.wandscape.content.items.scepter.ScepterKind;
import com.wsteam.wandscape.content.items.scepter.internal.ScepterApiImpl;
import com.wsteam.wandscape.content.items.scepter.internal.ScepterDeathHandler;
import com.wsteam.wandscape.content.items.scepter.internal.ScepterInteractHandler;
import com.wsteam.wandscape.content.items.scepter.internal.ScepterMarksSavedData;
import com.wsteam.wandscape.content.magic.internal.WandscapeEffects;
import com.wsteam.wandscape.content.npc.guard.*;
import com.wsteam.wandscape.content.road.data.RoadPresetLoader;
import com.wsteam.wandscape.content.road.network.*;
import com.wsteam.wandscape.content.tourist.internal.*;
import com.wsteam.wandscape.content.tourist.network.TouristBubblePacket;
import com.wsteam.wandscape.foundation.registry.dataconfig.internal.WandscapeBalanceLoader;
import com.wsteam.wandscape.foundation.registry.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.content.element.internal.ElementApiImpl;
import com.wsteam.wandscape.content.element.internal.ElementMappingLoader;
import com.wsteam.wandscape.content.items.element.ElementItem;
// engine wildcard replaced
import com.wsteam.wandscape.impl.EngineBootstrap;
import com.wsteam.wandscape.content.production.ProductionEligibility;
import com.wsteam.wandscape.content.task.boundary.WandscapeBlockInteractExecutor;
import com.wsteam.wandscape.content.colony.ColonyLevelData;
import com.wsteam.wandscape.content.colony.ColonyLevelManager;
import com.wsteam.wandscape.content.building.ChunkLoadManager;
import com.wsteam.wandscape.content.colony.service.ColonyStatusService;
import com.wsteam.wandscape.foundation.registry.WandscapeSounds;
import com.wsteam.wandscape.content.warehouse.transport.TransportItemEntity;
import com.wsteam.wandscape.content.warehouse.transport.TransportStartPacket;
import com.wsteam.wandscape.content.items.guidebook.item.GuideBookItem;
import com.wsteam.wandscape.content.items.guidebook.network.GuideBookOpenPacket;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.magic.entity.MagicBeamEntity;
import com.wsteam.wandscape.content.magic.internal.MagicCastManager;
import com.wsteam.wandscape.content.magic.internal.MagicCircleLoader;
import com.wsteam.wandscape.content.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.content.magic.internal.SpellcastingApiImpl;
import com.wsteam.wandscape.content.items.magic.SpellItem;
import com.wsteam.wandscape.content.npc.NpcMenu;
import com.wsteam.wandscape.content.npc.NpcStrategyMenu;
import com.wsteam.wandscape.content.npc.NpcInventoryMenu;
import com.wsteam.wandscape.content.npc.entity.EvilMage;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.content.npc.internal.NpcApiImpl;
import com.wsteam.wandscape.content.npc.internal.NpcAttributesApiImpl;
import com.wsteam.wandscape.content.npc.network.*;
import com.wsteam.wandscape.content.colony.overview.network.OverviewEntityInteractPacket;
import com.wsteam.wandscape.content.colony.overview.network.OverviewInteractPacket;
import com.wsteam.wandscape.content.production.ProductionRecipeLoader;
import com.wsteam.wandscape.content.production.network.CraftingStationPacket;
import com.wsteam.wandscape.content.production.network.MagicStationPacket;
import com.wsteam.wandscape.content.production.network.RequestProductionTaskPacket;
import com.wsteam.wandscape.content.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.content.items.oathring.internal.OathRingSavedData;
import com.wsteam.wandscape.content.road.engine.RoadApiImpl;
import com.wsteam.wandscape.content.road.engine.RoadSavedData;
import com.wsteam.wandscape.content.road.engine.RoadSegmentListener;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.magic.network.MagicCircleCastPacket;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.colony.stats.internal.StatisticsCollector;
import com.wsteam.wandscape.content.tourist.entity.TouristEntity;
import com.wsteam.wandscape.content.tourist.network.TouristDataPacket;
import com.wsteam.wandscape.content.items.magic.wand.internal.WandApiImpl;
import com.wsteam.wandscape.content.items.magic.wand.internal.WandPresetLoader;
import com.wsteam.wandscape.content.items.magic.wand.internal.WandPresetLoader.WandPreset;
import com.wsteam.wandscape.content.items.magic.wand.item.WandItem;
import com.wsteam.wandscape.content.warehouse.WarehouseManager;
import com.wsteam.wandscape.content.warehouse.WarehouseMenu;
import com.wsteam.wandscape.content.warehouse.WarehouseTerminalItem;
import com.wsteam.wandscape.content.warehouse.network.WarehouseActionPacket;
import com.wsteam.wandscape.content.warehouse.network.WarehouseDataPacket;
import com.wsteam.wandscape.content.warehouse.network.WarehouseTerminalKeyPacket;
import com.wsteam.wandscape.content.npc.internal.FriendlyForceApiImpl;
import com.wsteam.wandscape.api.NpcMainHandApi;
import com.wsteam.wandscape.content.npc.internal.NpcMainHandApiImpl;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

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
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MODID);
    /** 仓库容器菜单（玩家槽 vanilla + 仓库只读槽，见 warehouse/WarehouseMenu）。 */
    public static final DeferredHolder<MenuType<?>, MenuType<WarehouseMenu>> WAREHOUSE_MENU =
            MENUS.register("warehouse", () ->
                    new MenuType<>(WarehouseMenu::new, FeatureFlags.VANILLA_SET));
    /** NPC 装备容器菜单（4 盔甲 + 1 法杖 + 玩家槽，见 npc/NpcMenu）。 */
    public static final DeferredHolder<MenuType<?>, MenuType<NpcMenu>> NPC_MENU =
            MENUS.register("npc", () ->
                    new MenuType<>(NpcMenu::new, FeatureFlags.VANILLA_SET));
    /** NPC 施法策略容器菜单（12 卷轴槽 + 玩家槽，见 npc/NpcStrategyMenu）。 */
    public static final DeferredHolder<MenuType<?>, MenuType<NpcStrategyMenu>> NPC_STRATEGY_MENU =
            MENUS.register("npc_strategy", () ->
                    new MenuType<>(NpcStrategyMenu::new, FeatureFlags.VANILLA_SET));
    /** NPC 背包容器菜单（27 格法师背包 + 玩家槽，见 npc/NpcInventoryMenu）。 */
    public static final DeferredHolder<MenuType<?>, MenuType<NpcInventoryMenu>> NPC_INVENTORY_MENU =
            MENUS.register("npc_inventory", () ->
                    IMenuTypeExtension.create(NpcInventoryMenu::new));

    // ---- Debug target ----
    public static BlockPos debugDiamondTarget = null;

    // ---- Data loader ----
    public static final WandscapeDataLoader DATA_LOADER = new WandscapeDataLoader();
    /** 加载 data/wandscape/wandscape_balance.json，把可调平衡值灌进 BalanceValues（reload 时确定性重载）。 */
    public static final WandscapeBalanceLoader BALANCE_LOADER = new WandscapeBalanceLoader();

    // ---- 02 wand-system ----
    public static final DeferredItem<Item> WAND = ITEMS.register("wand",
            () -> new WandItem(new Item.Properties().stacksTo(1)));
    public static final WandPresetLoader WAND_PRESET_LOADER = new WandPresetLoader(DATA_LOADER);
    public static final WandApiImpl WAND_API = new WandApiImpl();

    // ---- 指南书（右键打开教程首页） ----
    public static final DeferredItem<Item> GUIDE_BOOK = ITEMS.register("guide_book",
            () -> new GuideBookItem(new Item.Properties()));
    /** 魔法物品（通用件，CUSTOM_DATA 存 magicId，见 magic/item/SpellItem.java）。 */
    public static final DeferredItem<Item> SPELL_SCROLL = ITEMS.register("spell_scroll",
            () -> new SpellItem(new Item.Properties().stacksTo(1)));

    // ---- 03 element-system ----
    public static final ElementMappingLoader ELEMENT_MAPPING_LOADER = new ElementMappingLoader(DATA_LOADER);
    public static final ElementApiImpl ELEMENT_API = new ElementApiImpl(ELEMENT_MAPPING_LOADER);

    // 元素物品（代表一种元素，供 JEI/配方展示；获得后自动存入所在小镇仓库）
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
        WandscapeApis.setMagicApi(SPELLCASTING_API);
        WandscapeApis.setProductionApi(new com.wsteam.wandscape.content.production.internal.ProductionApiImpl());
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
                            () -> WANDSCAPE_NPC.get(),
                            0x4B0082,  // dark purple background
                            0xFFD700,  // gold highlight
                            new Item.Properties()));

    // ---- 敌对测试法师 spawn egg（深红/黑） ----
    public static final DeferredItem<Item> EVIL_MAGE_SPAWN_EGG =
            ITEMS.register("evil_mage_spawn_egg", () ->
                    new DeferredSpawnEggItem(
                            () -> EVIL_MAGE.get(),
                            0x8B0000,  // dark red background
                            0x1A1A1A,  // black highlight
                            new Item.Properties()));

    // ---- tourist-system: entity ----
    public static final DeferredHolder<EntityType<?>, EntityType<TouristEntity>> TOURIST =
            ENTITIES.register("tourist", () ->
                    EntityType.Builder.of(TouristEntity::new, MobCategory.CREATURE)
                            // 1.95 比玩家(1.8)高，2 格高通道净空不足时游客会卡住；降到玩家身高即与玩家同通行能力
                            .sized(0.6f, 1.8f)
                            .clientTrackingRange(10)
                            .build("tourist"));

    // ---- tourist-system: spawn egg ----
    public static final DeferredItem<Item> TOURIST_SPAWN_EGG =
            ITEMS.register("tourist_spawn_egg", () ->
                    new DeferredSpawnEggItem(
                            () -> TOURIST.get(),
                            0xFFAA00,  // orange background
                            0xFFFFFF,  // white highlight
                            new Item.Properties()));

    // ---- ring: 盟誓戒指（同玩家共享固定槽存储，见 ring/internal/）----
    public static final DeferredItem<Item> OATH_RING =
            ITEMS.register("oath_ring", () ->
                    new OathRingItem(
                            new Item.Properties().stacksTo(1),
                            RingTier.LOW));
    public static final DeferredItem<Item> OATH_RING_MID =
            ITEMS.register("oath_ring_mid", () ->
                    new OathRingItem(
                            new Item.Properties().stacksTo(1),
                            RingTier.MID));
    public static final DeferredItem<Item> OATH_RING_HIGH =
            ITEMS.register("oath_ring_high", () ->
                    new OathRingItem(
                            new Item.Properties().stacksTo(1),
                            RingTier.HIGH));

    // ---- scepter: 玩家权杖（和平/跟随/庇护/敌对，合成站 1 级配方产出，见 scepter/）----
    public static final DeferredItem<Item> PEACE_WAND =
            ITEMS.register("peace_wand", () ->
                    new ScepterItem(
                            new Item.Properties().stacksTo(1),
                            ScepterKind.PEACE));
    public static final DeferredItem<Item> FOLLOW_WAND =
            ITEMS.register("follow_wand", () ->
                    new ScepterItem(
                            new Item.Properties().stacksTo(1),
                            ScepterKind.FOLLOW));
    public static final DeferredItem<Item> SHELTER_WAND =
            ITEMS.register("shelter_wand", () ->
                    new ScepterItem(
                            new Item.Properties().stacksTo(1),
                            ScepterKind.SHELTER));
    public static final DeferredItem<Item> HOSTILE_WAND =
            ITEMS.register("hostile_wand", () ->
                    new ScepterItem(
                            new Item.Properties().stacksTo(1),
                            ScepterKind.HOSTILE));
    public static final DeferredItem<Item> OMNI_SCEPTER =
            ITEMS.register("omni_scepter", () ->
                    new OmniScepterItem(new Item.Properties().stacksTo(1)));
    public static final ScepterApiImpl SCEPTER_API =
            new ScepterApiImpl();

    // ---- compass: 魔法指南针（三档，指针指向玩家自己殖民地的市政厅，合成站 1/10/20 级配方产出）----
    public static final DeferredItem<Item> MAGIC_COMPASS =
            ITEMS.register("magic_compass", () ->
                    new MagicCompassItem(new Item.Properties().stacksTo(1), CompassTier.BASIC));
    public static final DeferredItem<Item> ADVANCED_MAGIC_COMPASS =
            ITEMS.register("advanced_magic_compass", () ->
                    new MagicCompassItem(new Item.Properties().stacksTo(1), CompassTier.ADVANCED));
    public static final DeferredItem<Item> ULTIMATE_MAGIC_COMPASS =
            ITEMS.register("ultimate_magic_compass", () ->
                    new MagicCompassItem(new Item.Properties().stacksTo(1), CompassTier.ULTIMATE));

    // ---- warehouse terminal: 仓库终端（右键打开本殖民地仓库面板，合成站 20 级配方产出；Curios 手饰槽待办）----
    public static final DeferredItem<Item> WAREHOUSE_TERMINAL =
            ITEMS.register("warehouse_terminal", () ->
                    new WarehouseTerminalItem(new Item.Properties().stacksTo(1)));

    // ---- building-scanner blocks ----
    // Creative Building Scanner (full-featured, for creators) — renamed from building_scanner to
    // creative_building_scanner; the plain id "building_scanner" now belongs to the Survival scanner.
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    public static final DeferredHolder<Block, Block> CREATIVE_BUILDING_SCANNER = BLOCKS.register("creative_building_scanner",
            () -> new CreativeScannerBlock(BlockBehaviour.Properties.of().strength(2.0f).noOcclusion(),
                    Wandscape.CREATIVE_BUILDING_SCANNER_BE::get));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CreativeScannerBlockEntity>> CREATIVE_BUILDING_SCANNER_BE =
            BLOCK_ENTITY_TYPES.register("creative_building_scanner",
                    () -> BlockEntityType.Builder.of(
                            CreativeScannerBlockEntity::new,
                            CREATIVE_BUILDING_SCANNER.get()).build(null));

    public static final DeferredItem<Item> CREATIVE_BUILDING_SCANNER_ITEM =
            ITEMS.register("creative_building_scanner", () -> new BlockItem(CREATIVE_BUILDING_SCANNER.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> BUILDING_SCANNER = BLOCKS.register("building_scanner",
            () -> new ScannerBlock(BlockBehaviour.Properties.of().strength(2.0f).noOcclusion(),
                    Wandscape.BUILDING_SCANNER_BE::get));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ScannerBlockEntity>> BUILDING_SCANNER_BE =
            BLOCK_ENTITY_TYPES.register("building_scanner",
                    () -> BlockEntityType.Builder.of(
                            ScannerBlockEntity::new,
                            BUILDING_SCANNER.get()).build(null));

    public static final DeferredItem<Item> BUILDING_SCANNER_ITEM =
            ITEMS.register("building_scanner", () -> new BlockItem(BUILDING_SCANNER.get(), new Item.Properties()));

    public static final DeferredHolder<Block, Block> INTERACT_SPOT_MARKER = BLOCKS.register("interact_spot_marker",
            () -> new InteractSpotMarkerBlock(
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
                        acceptWandPresets(output);
                        output.accept(WANDSCAPE_NPC_EGG.get());
                        output.accept(EVIL_MAGE_SPAWN_EGG.get());
                        output.accept(TOURIST_SPAWN_EGG.get());
                        output.accept(OATH_RING.get());
                        output.accept(OATH_RING_MID.get());
                        output.accept(OATH_RING_HIGH.get());
                        output.accept(PEACE_WAND.get());
                        output.accept(FOLLOW_WAND.get());
                        output.accept(SHELTER_WAND.get());
                        output.accept(HOSTILE_WAND.get());
                        output.accept(OMNI_SCEPTER.get());
                        output.accept(MAGIC_COMPASS.get());
                        output.accept(ADVANCED_MAGIC_COMPASS.get());
                        output.accept(ULTIMATE_MAGIC_COMPASS.get());
                        output.accept(WAREHOUSE_TERMINAL.get());
                        output.accept(CREATIVE_BUILDING_SCANNER_ITEM.get());
                        output.accept(BUILDING_SCANNER_ITEM.get());
                        output.accept(INTERACT_SPOT_MARKER_ITEM.get());
                        output.accept(GUIDE_BOOK.get());
                        output.accept(SPELL_SCROLL.get());
                        acceptBoundSpellScrolls(output);
                        ELEMENT_ITEMS.values().forEach(item -> output.accept(item.get()));
                    })
                    .build());

    /** 创造栏补发各法杖预设变体（数据驱动：新法杖配方自动出现）。 */
    private static void acceptWandPresets(CreativeModeTab.Output output) {
        for (WandPreset preset : WAND_PRESET_LOADER.getAllPresets().values()) {
            ItemStack stack = new ItemStack(WAND.get());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(preset.nbt().copy()));
            output.accept(stack);
        }
    }

    /** 创造栏补发各战斗/特殊魔法的已绑定卷轴（数据驱动：新魔法自动出现；ALTAR 祭坛专属不物品化）。 */
    private static void acceptBoundSpellScrolls(CreativeModeTab.Output output) {
        for (MagicDef def : SpellbookLoader.getAllSpecs().values()) {
            if (def.category() == MagicDef.Category.ALTAR) continue;
            ItemStack stack = new ItemStack(SPELL_SCROLL.get());
            SpellItem.setMagicId(stack, def.id());
            output.accept(stack);
        }
    }

    // ---- API instances ----
    private final BuildingApiImpl buildingApi = new BuildingApiImpl();
    private final BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();
    public static final RoadPresetLoader ROAD_PRESET_LOADER =
            RoadPresetLoader.getInstance();
    private final DecorationBonusSystem decorationBonusSystem;
    private final ShopStockManager shopStockManager;
    private final TavernApiImpl tavernApi;

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
        MENUS.register(modEventBus);
        WandscapeSounds.SOUNDS.register(modEventBus);
        com.wsteam.wandscape.content.npc.WandscapeAttributes.ATTRIBUTES.register(modEventBus);
        WandscapeEffects.PETRIFICATION.getId();

        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(HostileTargetingHandler.class);
        NeoForge.EVENT_BUS.register(BuildingNoSpawnZoneHandler.class);
        NeoForge.EVENT_BUS.register(OathRingSyncHandler.class);
        NeoForge.EVENT_BUS.register(CompassSyncHandler.class);
        NeoForge.EVENT_BUS.register(ScepterInteractHandler.class);
        NeoForge.EVENT_BUS.register(ScepterDeathHandler.class);
        NeoForge.EVENT_BUS.register(SelfDefenseHandler.class);
        NeoForge.EVENT_BUS.register(FollowAttackHandler.class);
        NeoForge.EVENT_BUS.register(NpcSpellPowerHandler.class);
        NeoForge.EVENT_BUS.register(com.wsteam.wandscape.content.npc.guard.NpcFriendlyFireHandler.class);
        NeoForge.EVENT_BUS.register(com.wsteam.wandscape.content.npc.guard.FriendlyTargetingHandler.class);
        NeoForge.EVENT_BUS.register(com.wsteam.wandscape.content.npc.internal.NpcDeathHandler.class);
        NeoForge.EVENT_BUS.register(BuildingInteractHandler.class);
        NeoForge.EVENT_BUS.register(com.wsteam.wandscape.content.colony.guard.ColonyLandProtectionHandler.class);
        NeoForge.EVENT_BUS.register(com.wsteam.wandscape.foundation.ui.panel.PanelStateTracker.class);
        NeoForge.EVENT_BUS.register(com.wsteam.wandscape.content.task.network.TaskPanelSyncTracker.class);
        DailySettlementSystem.register();
        StatisticsCollector.register();
        decorationBonusSystem = DecorationBonusSystem.register();
        shopStockManager = ShopStockManager.register();
        WonderEffectApplier.register();
        BuildingInteractHandler.setShopStockManager(shopStockManager);
        TouristSpawnSystem.register();
        HotelStayHandler.register();
        MarkerPreviewManager.register();
        com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.init(modEventBus);
        com.wsteam.wandscape.compat.goety.GoetyCompat.init(modEventBus);
        com.wsteam.wandscape.compat.curios.CuriosCompat.init(modEventBus);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

        // Keep log verbosity in sync with the config (applies on load and reload)
        modEventBus.addListener(this::onModConfig);

        // Register API implementations
        WandscapeApis.setBuildingApi(buildingApi);
        WandscapeApis.setNpcApi(new NpcApiImpl());
        WandscapeApis.setNpcAttributesApi(new NpcAttributesApiImpl());
        WandscapeApis.setScepterApi(SCEPTER_API);
        WandscapeApis.setWarehouseApi(new WarehouseManager());
        WandscapeApis.setMageHutApi(new com.wsteam.wandscape.content.building.internal.MageHutApiImpl());
        WandscapeApis.setColonyApi(ColonyApiImpl.get());
        WandscapeApis.setFriendlyForceApi(new FriendlyForceApiImpl());
        // 法师主手（法杖）槽准入：先装 API，再让 compat 层按模组加载态预注册铁魔法/诡厄法杖标签判定
        NpcMainHandApi mainHandApi = new NpcMainHandApiImpl();
        WandscapeApis.setNpcMainHandApi(mainHandApi);
        com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.registerAllowedMainHandItems(mainHandApi);
        com.wsteam.wandscape.compat.goety.GoetyCompat.registerAllowedMainHandItems(mainHandApi);
        WandscapeApis.setTouristApi(new TouristApiImpl());
        tavernApi = new TavernApiImpl();
        WandscapeApis.setTavernApi(tavernApi);

        // Register config loaders with data loader
        configLoader.registerWith(DATA_LOADER);
        ROAD_PRESET_LOADER.registerWith(DATA_LOADER);

        // Production recipe loader
        PRODUCTION_RECIPE_LOADER = new ProductionRecipeLoader(DATA_LOADER, ELEMENT_MAPPING_LOADER);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            WandscapeClient.init(modEventBus, modContainer);
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        com.wsteam.wandscape.foundation.log.LogConfig.load();
        WandscapeApis.setWandApi(WAND_API);
        WandscapeApis.setElementApi(ELEMENT_API);
        Log.info(com.wsteam.wandscape.foundation.log.LogCategory.BOOTSTRAP, "Wandscape common setup — wand, element, buildings, npc ready");
    }

    private void onModConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == Config.SPEC && Config.SPEC.isLoaded()) {
            if (Config.DEBUG.get()) {
                com.wsteam.wandscape.foundation.log.LogConfig.setRootLevel(com.wsteam.wandscape.foundation.log.LogLevel.DEBUG);
            }
        }
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(MODID).versioned("1.0");
        registrar
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
                        MagicStationPacket.TYPE,
                        MagicStationPacket.STREAM_CODEC,
                        (packet, ctx) -> MagicStationPacket.handleClient(packet))
                .playToClient(
                        ShopOpenPacket.TYPE,
                        ShopOpenPacket.STREAM_CODEC,
                        (packet, ctx) -> ShopOpenPacket.handleClient(packet))
                .playToClient(
                        BuildingConfigSyncChunkPacket.TYPE,
                        BuildingConfigSyncChunkPacket.STREAM_CODEC,
                        (packet, ctx) -> BuildingConfigSyncChunkPacket.handleClient(packet))
                .playToClient(
                        DatapackDataSyncChunkPacket.TYPE,
                        DatapackDataSyncChunkPacket.STREAM_CODEC,
                        (packet, ctx) -> DatapackDataSyncChunkPacket.handleClient(packet))
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
                .playToClient(
                        OathRingDataPacket.TYPE,
                        OathRingDataPacket.STREAM_CODEC,
                        (packet, ctx) -> OathRingDataPacket.handleClient(packet))
                .playToClient(
                        CompassTargetPacket.TYPE,
                        CompassTargetPacket.STREAM_CODEC,
                        (packet, ctx) -> CompassTargetPacket.handleClient(packet))
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
                        WarehouseTerminalKeyPacket.TYPE,
                        WarehouseTerminalKeyPacket.STREAM_CODEC,
                        (packet, ctx) -> WarehouseTerminalKeyPacket.handleServer(
                                packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        TavernRecruitPacket.TYPE,
                        TavernRecruitPacket.STREAM_CODEC,
                        TavernRecruitPacket::handleServer)
                // ── Mage Hut ──
                .playToClient(
                        MageHutDataPacket.TYPE,
                        MageHutDataPacket.STREAM_CODEC,
                        (packet, ctx) -> MageHutDataPacket.handleClient(packet))
                .playToServer(
                        MageHutActionPacket.TYPE,
                        MageHutActionPacket.STREAM_CODEC,
                        (packet, ctx) -> MageHutActionPacket.handleServer(packet, ctx))
                .playToServer(
                        OpenWarehousePacket.TYPE,
                        OpenWarehousePacket.STREAM_CODEC,
                        (packet, ctx) -> OpenWarehousePacket.handleServer(packet, ctx))
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
                        ScannerValuePacket.TYPE,
                        ScannerValuePacket.STREAM_CODEC,
                        (packet, ctx) -> ScannerValuePacket.handleServer(packet,
                                (ServerPlayer) ctx.player()))
                .playToServer(
                        SplineBuildPacket.TYPE,
                        SplineBuildPacket.STREAM_CODEC,
                        (packet, ctx) -> SplineBuildPacket.handleServer(packet, (ServerPlayer) ctx.player()))
                .playToServer(
                        RoadInteractPacket.TYPE,
                        RoadInteractPacket.STREAM_CODEC,
                        (packet, ctx) -> RoadInteractPacket.handleServer(packet, (ServerPlayer) ctx.player()))
                .playToServer(
                        RoadWithdrawPacket.TYPE,
                        RoadWithdrawPacket.STREAM_CODEC,
                        (packet, ctx) -> RoadWithdrawPacket.handleServer(packet, (ServerPlayer) ctx.player()))
                // ── Wandscape Panel ──
                .playToServer(
                        com.wsteam.wandscape.foundation.ui.panel.PanelStateTogglePacket.TYPE,
                        com.wsteam.wandscape.foundation.ui.panel.PanelStateTogglePacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.foundation.ui.panel.PanelStateTogglePacket
                                .handleServer(packet, (ServerPlayer) ctx.player()))
                .playToClient(
                        com.wsteam.wandscape.content.colony.network.ColonyStatsSyncPacket.TYPE,
                        com.wsteam.wandscape.content.colony.network.ColonyStatsSyncPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.content.colony.network.ColonyStatsSyncPacket
                                .handleClient(packet))
                // ── Stats ──
                .playToClient(
                        StatsSyncPacket.TYPE,
                        StatsSyncPacket.STREAM_CODEC,
                        (packet, ctx) -> StatsSyncPacket
                                .handleClient(packet))
                // ── Building interaction area overlay ──
                .playToClient(
                        com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket.TYPE,
                        com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket
                                .handleClient(packet))
                // ── Road construction ghost sync ──
                .playToClient(
                        com.wsteam.wandscape.content.road.network.RoadAreaSyncPacket.TYPE,
                        com.wsteam.wandscape.content.road.network.RoadAreaSyncPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.content.road.network.RoadAreaSyncPacket
                                .handleClient(packet))
                // ── Transient action feedback (screen toast or action bar) ──
                .playToClient(
                        com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket.TYPE,
                        com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket
                                .handleClient(packet))
                // ── NPC info screen ──
                .playToClient(
                        NpcDataPacket.TYPE,
                        NpcDataPacket.STREAM_CODEC,
                        (packet, ctx) -> NpcDataPacket.handleClient(packet))
                .playToServer(
                        NpcOpenStrategyPacket.TYPE,
                        NpcOpenStrategyPacket.STREAM_CODEC,
                        (packet, ctx) -> NpcOpenStrategyPacket.handleServer(packet, ctx))
                .playToServer(
                        NpcOpenInventoryPacket.TYPE,
                        NpcOpenInventoryPacket.STREAM_CODEC,
                        (packet, ctx) -> NpcOpenInventoryPacket.handleServer(packet, ctx))
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
                .playToServer(
                        NpcDismissPacket.TYPE,
                        NpcDismissPacket.STREAM_CODEC,
                        (packet, ctx) -> NpcDismissPacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                // ── Tourist info screen ──
                .playToClient(
                        TouristDataPacket.TYPE,
                        TouristDataPacket.STREAM_CODEC,
                        (packet, ctx) -> TouristDataPacket.handleClient(packet))
                // ── Tourist purchase / service bubble ──
                .playToClient(
                        TouristBubblePacket.TYPE,
                        TouristBubblePacket.STREAM_CODEC,
                        (packet, ctx) -> TouristBubblePacket.handleClient(packet))
                // ── Colony day/night ambient ──
                .playToClient(
                        com.wsteam.wandscape.content.colony.network.ColonyAmbientPacket.TYPE,
                        com.wsteam.wandscape.content.colony.network.ColonyAmbientPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.content.colony.network.ColonyAmbientPacket
                                .handleClient(packet))
                // ── Colony name update ──
                .playToServer(
                        com.wsteam.wandscape.content.colony.network.ColonyNameUpdatePacket.TYPE,
                        com.wsteam.wandscape.content.colony.network.ColonyNameUpdatePacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.content.colony.network.ColonyNameUpdatePacket
                                .handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                // ── Town hall naming rule switch ──
                .playToServer(
                        TownHallNameStylePacket.TYPE,
                        TownHallNameStylePacket.STREAM_CODEC,
                        (packet, ctx) -> TownHallNameStylePacket
                                .handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                // ── Town hall tourist-spawn toggle ──
                .playToServer(
                        TownHallTouristSpawnPacket.TYPE,
                        TownHallTouristSpawnPacket.STREAM_CODEC,
                        (packet, ctx) -> TownHallTouristSpawnPacket
                                .handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                // ── Colony create (town hall naming flow) ──
                .playToServer(
                        com.wsteam.wandscape.content.colony.network.ColonyCreateRequestPacket.TYPE,
                        com.wsteam.wandscape.content.colony.network.ColonyCreateRequestPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.content.colony.network.ColonyCreateRequestPacket
                                .handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToClient(
                        com.wsteam.wandscape.content.colony.network.ColonyCreatePromptPacket.TYPE,
                        com.wsteam.wandscape.content.colony.network.ColonyCreatePromptPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.content.colony.network.ColonyCreatePromptPacket
                                .handleClient(packet))
                // ── Transport start ──
                .playToClient(
                        TransportStartPacket.TYPE,
                        TransportStartPacket.STREAM_CODEC,
                        (packet, ctx) -> ctx.enqueueWork(() -> TransportStartPacket.handleClient(packet)))
                // ── Magic circle cast ──
                .playToClient(
                        MagicCircleCastPacket.TYPE,
                        MagicCircleCastPacket.STREAM_CODEC,
                        (packet, ctx) -> MagicCircleCastPacket.handleClient(packet))
                // ── Particle burst (colored FX) ──
                .playToClient(
                        com.wsteam.wandscape.foundation.networking.ParticleBurstPacket.TYPE,
                        com.wsteam.wandscape.foundation.networking.ParticleBurstPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.foundation.networking.ParticleBurstPacket.handleClient(packet))
                // ── Guide book (right-click to open tutorial home) ──
                .playToClient(
                        GuideBookOpenPacket.TYPE,
                        GuideBookOpenPacket.STREAM_CODEC,
                        (packet, ctx) -> GuideBookOpenPacket.handleClient(packet))
                // ── Guide progress (onboarding persistence) ──
                .playToClient(
                        com.wsteam.wandscape.content.tutorial.network.TutorialProgressSyncPacket.TYPE,
                        com.wsteam.wandscape.content.tutorial.network.TutorialProgressSyncPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.content.tutorial.network.TutorialProgressSyncPacket.handleClient(packet))
                .playToServer(
                        com.wsteam.wandscape.content.tutorial.network.TutorialProgressUpdatePacket.TYPE,
                        com.wsteam.wandscape.content.tutorial.network.TutorialProgressUpdatePacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.content.tutorial.network.TutorialProgressUpdatePacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                // ── Spline Road Editor ──
                .playToClient(
                        RoadStudioEnterPacket.TYPE,
                        RoadStudioEnterPacket.STREAM_CODEC,
                        RoadStudioEnterPacket::handleClient)
                // ── Task & Mage Management Panel ──
                .playToServer(
                        com.wsteam.wandscape.content.task.network.TaskPanelSubscribePacket.TYPE,
                        com.wsteam.wandscape.content.task.network.TaskPanelSubscribePacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.content.task.network.TaskPanelSubscribePacket
                                .handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToClient(
                        com.wsteam.wandscape.content.task.network.TaskManagementSyncPacket.TYPE,
                        com.wsteam.wandscape.content.task.network.TaskManagementSyncPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.content.task.network.TaskManagementSyncPacket
                                .handleClient(packet))
                .playToServer(
                        com.wsteam.wandscape.content.task.network.TaskManagementActionPacket.TYPE,
                        com.wsteam.wandscape.content.task.network.TaskManagementActionPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.content.task.network.TaskManagementActionPacket
                                .handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        com.wsteam.wandscape.content.task.network.MageModeActionPacket.TYPE,
                        com.wsteam.wandscape.content.task.network.MageModeActionPacket.STREAM_CODEC,
                        (packet, ctx) -> com.wsteam.wandscape.content.task.network.MageModeActionPacket
                                .handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                // ── NPC 装备界面重开（饰品屏返回按钮） ──
                .playToServer(
                        NpcOpenEquipPacket.TYPE,
                        NpcOpenEquipPacket.STREAM_CODEC,
                        (packet, ctx) -> NpcOpenEquipPacket.handleServer(packet, ctx));
        // Curios 兼容：法师饰品栏打开请求（仅 Curios 加载时在实现类内注册；无 Curios 时此处不引用任何 Curios 类）
        com.wsteam.wandscape.compat.curios.CuriosCompat.registerPayloads(registrar);
    }

    private void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(WANDSCAPE_NPC.get(), WandscapeNpc.createAttributes().build());
        event.put(EVIL_MAGE.get(), WandscapeNpc.createAttributes().build());
        event.put(TOURIST.get(), TouristEntity.createAttributes().build());
    }

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(DATA_LOADER);
        event.addListener(BALANCE_LOADER);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        Log.info(TAG, "Wandscape server starting — bootstrapping engine...");
        buildingApi.setLevel(event.getServer().overworld());
        var runtime = EngineBootstrap.bootstrap();

        // Register unified metrics facade (after bootstrap, before any consumer queries it)
        var metricsService = ColonyStatusService.create();
        com.wsteam.wandscape.api.WandscapeApis.setColonyStatusApi(metricsService);
        Log.info(TAG, "ColonyStatusService registered");

        // Register server-authoritative tutorial progress evaluator
        com.wsteam.wandscape.api.WandscapeApis.setTutorialApi(
                new com.wsteam.wandscape.content.tutorial.service.TutorialProgressService());
        Log.info(TAG, "TutorialProgressService registered");

        BuildCompleteListener.register();
        DemolishCompleteListener.register();
        // Rebuild colony spatial index from saved data
        var colonyApi = com.wsteam.wandscape.api.WandscapeApis.getColonyApiSilently();
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
        ProductionEligibility.setProductionRecipeLoader(PRODUCTION_RECIPE_LOADER);

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
        var world = runtime.getWorld();
        if (world != null && world.taskPool != null) {
            var saved = TaskPoolSavedData.getOrCreate(level, world.taskPool);
            // Mark dirty when pool changes so SavedData writes to disk
            world.taskPool.onChanged = saved::setDirty;
            Log.info(TAG, "Task persistence wired — pool has {} active tasks", world.taskPool.size());
        }

        // Chunk load manager — force-loads active building footprints on demand so
        // colonies keep building/producing while their chunks are unloaded.
        ChunkLoadManager.get().init(level);

        // Road persistence + API
        var roadSaved = RoadSavedData.getOrCreate(level);
        WandscapeApis.setRoadApi(new RoadApiImpl());
        Log.info(TAG, "Road system wired — {} edges persisted", roadSaved.getNetwork().edgeCount());

        // Tavern recruit storage
        var recruitStorage = TavernRecruitStorage.getOrCreate(level);
        tavernApi.setStorage(recruitStorage);
        Log.info(TAG, "Tavern recruit storage wired");

        // Oath ring per-player storage (eagerly load so ring interactions never hit a null instance)
        OathRingSavedData.get(event.getServer());
        // Scepter marks per-colony storage (庇护/敌对 标记长期持久化；预载避免首次使用读档延迟)
        ScepterMarksSavedData.get(event.getServer());
        Log.info(TAG, "Oath ring storage wired");

        // Colony level data
        var colonyLevelData = ColonyLevelData.getOrCreate(level);
        var colonyLevelManager = new ColonyLevelManager(colonyLevelData);
        ColonyLevelManager.setActive(colonyLevelManager);
        com.wsteam.wandscape.content.colony.ColonyApiImpl.get().setColonyLevelManager(colonyLevelManager);

        // Wire level-up event to engine EventBus
        if (world != null && world.eventBus != null) {
            colonyLevelManager.setLevelUpCallback(evt -> world.eventBus.emit(evt));
        }

        TouristSpawnSystem.setLevelManager(colonyLevelManager);
        Log.info(TAG, "Colony level system wired");

        // Tourist sim — drives unloaded tourists from data shadows.
        TouristSimSystem.register(level);
        Log.info(TAG, "Tourist sim system wired");
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        Log.info(TAG, "Wandscape server stopped — resetting engine.");
        buildingApi.setLevel(null);
        ChunkLoadManager.get().reset();
        TouristSimSystem.reset();
        TouristSpotManager.getActive().clear(); // 静态单例跨世界存活，需清空幽灵占位/排队
        com.wsteam.wandscape.content.task.runtime.TaskRuntime.reset();
        ColonyLevelManager.reset();
        com.wsteam.wandscape.content.warehouse.transport.ItemTransportManager.reset();
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

        // ── 面向玩家：玩法指令（只读免 op；变更类在各自 node 内以 hasPermission(2) 门控） ──
        var root = Commands.literal("wandscape")
                .then(ColonyCommand.node())
                .then(ElementCommand.node())
                .then(WarehouseCommand.node())
                .then(BuildingCommand.node())
                .then(RoadCommand.node())
                .then(NpcCommand.node())
                .then(TouristCommand.node())
                .then(TavernCommand.node())
                .then(RecoveryCommand.node())
                .then(GuardCommand.node())
                .then(GuideCommand.node());

        // ── 开发者/调试：一律藏到 /wandscape test（整棵 op-2，普通玩家补全里不可见） ──
        root.then(Commands.literal("test")
                .requires(src -> src.hasPermission(2))
                .then(LogCommand.node())
                .then(ProfileCommand.node())
                .then(AuditElementsCommand.node())
                .then(GenerateElementMappingsCommand.node())
                .then(FillBuildingCommand.fillNode())
                .then(PublishBlueprintCommand.buildNode())
                .then(MagicCommand.node())
                .then(TransportCommand.node())
                .then(TouristCommand.devNode())
                .then(TavernCommand.devNode())
                .then(RoadStudioCommand.node())
                .then(SplineEditorCommand.node()));

        // ── Curios 兼容：法师饰品槽位管理（仅 Curios 加载时注册，避免无 Curios 时缺类崩溃） ──
        if (com.wsteam.wandscape.compat.curios.CuriosCompat.isLoaded()) {
            root.then(com.wsteam.wandscape.compat.curios.CuriosCommand.node());
        }
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
        com.wsteam.wandscape.foundation.util.TickProfiler.Span spanTotal =
                com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.start("tick.server_post");
        try {
            // Colony ambient: 建筑包围盒+20格内玩家昼夜环境音门控（服务端判断+发包）
            try (var s = com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.start("tick.ambient")) {
                ColonyAmbientTracker.tick(event.getServer());
            }

            // Magic cast: 法阵动画结束后生成信标光束（不依赖 ECS）
            try (var s = com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.start("tick.magic_cast")) {
                MagicCastManager.tick();
            }

            // Altar: 每 tick 推进所有祭坛的魔法冷却（SavedData，按祭坛独立）
            try (var s = com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.start("tick.altar_cast")) {
                AltarCastHandler.tick(event.getServer().overworld());
            }

            // Iron's Spells compat: 推进持续施法与长蓄力法术
            if (com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.isLoaded()) {
                com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCaster.tickAll();
            }

            // Goety compat: 推进持续引导与长蓄力法术
            if (com.wsteam.wandscape.compat.goety.GoetyCompat.isLoaded()) {
                com.wsteam.wandscape.compat.goety.GoetyCaster.tickAll();
            }

            var rt = com.wsteam.wandscape.content.task.runtime.TaskRuntime.getActive();
            if (rt == null) return;
            var world = rt.getWorld();

            mcTickCount++;

            // ①g1 Tick projectile dodge (walk away from incoming hostile arrows/skulls; throttled)
            try (var s = com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.start("tick.projectile_dodge")) {
                ProjectileDodge.tick(world);
            }

            // ①h Tick raid trigger scanner + victory tracker (colonies live in the overworld)
            var raidLevel = event.getServer().overworld();
            if (raidLevel != null) {
                try (var s = com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.start("tick.raid")) {
                    RaidTriggerScanner.INSTANCE.tick(raidLevel);
                    ColonyRaidTracker.INSTANCE.tick(raidLevel);
                }
            }

            // ② Sync MC entity positions → ECS
            try (var s = com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.start("tick.bridge_sync_pos")) {
                EntityComponentBridge.INSTANCE.syncPositions(world);
            }

            // ②b Flush any NPCs that loaded before the engine was ready
            try (var s = com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.start("tick.bridge_flush_joins")) {
                EntityComponentBridge.INSTANCE.flushDeferredJoins(world);
            }

            // ③ Task runtime tick (executors + ECS world)
            engineTickCount++;
            rt.tick(event.getServer().overworld());

            // Heartbeat every ~5 seconds (100 MC ticks)
            if (mcTickCount % 100 == 0) {
                var colonyApi = WandscapeApis.getColonyApiSilently();
                if (colonyApi != null) {
                    var overworld = event.getServer().overworld();
                    if (overworld != null) {
                        for (var colonyId : colonyApi.getAllColonyIds()) {
                            com.wsteam.wandscape.content.npc.internal.ReviveHandler.checkAndAutoReviveColony(overworld, colonyId);
                        }
                    }
                }
                Log.debug(com.wsteam.wandscape.foundation.log.LogCategory.BOOTSTRAP, "engine", "engineTick=#{} mcTick=#{} — entities={} tasks_in_pool={} pendingAsync={}",
                        engineTickCount, mcTickCount,
                        world.getNextEntityId() - 1,
                        world.taskPool != null ? world.taskPool.size() : 0,
                        world.hasPendingAsyncOps() ? 1 : 0);
            }
        } finally {
            com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.end(spanTotal);
            var ow = event.getServer().overworld();
            long time = ow != null ? ow.getGameTime() : 0;
            com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.flushTick(time);
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
            byte[] compressed = BuildingConfigCompressor.compress(json);
            totalCompressedBytes += compressed.length;
            int totalChunks = Math.max(1, (compressed.length
                    + BuildingConfigSyncChunkPacket.CHUNK_BYTES - 1)
                    / BuildingConfigSyncChunkPacket.CHUNK_BYTES);
            int chunkIndex = 0;
            for (int off = 0; off < compressed.length; off += BuildingConfigSyncChunkPacket.CHUNK_BYTES) {
                int len = Math.min(BuildingConfigSyncChunkPacket.CHUNK_BYTES,
                        compressed.length - off);
                var pkt = new BuildingConfigSyncChunkPacket(
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

        // 其余 datapack 类目（魔法定/法杖预设/生产配方/元素映射等）同步给客户端：这些只在服务端
        // reload 读到 data/，专用服务器客户端进程拿不到（JEI 无配方、创造栏 NBT 变体缺失），
        // 故每类目原始 JSON 压成一个载荷分块下发。建筑配置已走上面的独立同步，这里排除。
        var rawByCategory = DATA_LOADER.getRawByCategory();
        if (rawByCategory != null && !rawByCategory.isEmpty()) {
            java.util.List<String> filesToSync = new java.util.ArrayList<>();
            for (var cat : rawByCategory.keySet()) {
                if ("buildings".equals(cat)) continue;
                if (rawByCategory.get(cat) != null && !rawByCategory.get(cat).isEmpty()) {
                    filesToSync.add(cat);
                }
            }
            filesToSync.sort(String::compareTo);
            int fileIndex = 0;
            int syncChunks = 0;
            long syncBytes = 0;
            for (String category : filesToSync) {
                var files = rawByCategory.get(category);
                com.google.gson.JsonObject root = new com.google.gson.JsonObject();
                root.addProperty("category", category);
                com.google.gson.JsonObject data = new com.google.gson.JsonObject();
                java.util.List<String> ids = new java.util.ArrayList<>(files.keySet());
                ids.sort(String::compareTo);
                for (String id : ids) {
                    data.add(id, files.get(id));
                }
                root.add("data", data);
                byte[] compressed = BuildingConfigCompressor.compress(root.toString());
                syncBytes += compressed.length;
                int totalChunks = Math.max(1, (compressed.length
                        + DatapackDataSyncChunkPacket.CHUNK_BYTES - 1)
                        / DatapackDataSyncChunkPacket.CHUNK_BYTES);
                int chunkIndex = 0;
                for (int off = 0; off < compressed.length; off += DatapackDataSyncChunkPacket.CHUNK_BYTES) {
                    int len = Math.min(DatapackDataSyncChunkPacket.CHUNK_BYTES, compressed.length - off);
                    var pkt = new DatapackDataSyncChunkPacket(
                            fileIndex, chunkIndex, totalChunks, filesToSync.size(),
                            java.util.Arrays.copyOfRange(compressed, off, off + len));
                    if (event.getPlayer() != null) {
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(event.getPlayer(), pkt);
                    } else {
                        net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(pkt);
                    }
                    chunkIndex++;
                    syncChunks++;
                }
                fileIndex++;
            }
            Log.info(TAG, "Synced {} datapack data categories ({} chunks, {} compressed bytes) on DatapackSync",
                    fileIndex, syncChunks, syncBytes);
        }
    }
}
