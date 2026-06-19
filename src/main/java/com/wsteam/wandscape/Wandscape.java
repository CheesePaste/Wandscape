package com.wsteam.wandscape;

import java.util.Set;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.be.EarthNodeBE;
import com.wsteam.wandscape.building.be.ForestNodeBE;
import com.wsteam.wandscape.building.be.TownHallBE;
import com.wsteam.wandscape.building.block.WandscapeBuildingBlock;
import com.wsteam.wandscape.building.internal.BuildingApiImpl;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.element.internal.ElementApiImpl;
import com.wsteam.wandscape.element.internal.ElementMappingLoader;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.bootstrap.EngineBootstrap;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.npc.internal.NpcApiImpl;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.wand.internal.WandApiImpl;
import com.wsteam.wandscape.wand.internal.WandPresetLoader;
import com.wsteam.wandscape.wand.item.WandItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Wandscape.MODID)
public class Wandscape {
    public static final String MODID = "wandscape";
    public static final Logger LOGGER = LogUtils.getLogger();

    // ---- DeferredRegisters ----
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    // ---- Data loader ----
    public static final WandscapeDataLoader DATA_LOADER = new WandscapeDataLoader();

    // ---- 02 wand-system ----
    public static final DeferredItem<Item> WAND = ITEMS.register("wand",
            () -> new WandItem(new Item.Properties()));
    public static final WandPresetLoader WAND_PRESET_LOADER = new WandPresetLoader(DATA_LOADER);
    public static final WandApiImpl WAND_API = new WandApiImpl();

    // ---- 03 element-system ----
    public static final ElementMappingLoader ELEMENT_MAPPING_LOADER = new ElementMappingLoader(DATA_LOADER);
    public static final ElementApiImpl ELEMENT_API = new ElementApiImpl(ELEMENT_MAPPING_LOADER);

    // ---- 08 building-core: block properties ----
    private static final BlockBehaviour.Properties BUILDING_PROPS =
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.0f, 6.0f)
                    .noOcclusion();

    // ---- 08 building-core: blocks ----
    public static final DeferredBlock<Block> TOWN_HALL_BLOCK = BLOCKS.register("town_hall",
            () -> new WandscapeBuildingBlock(BUILDING_PROPS, TownHallBE.TYPE_ID, TownHallBE::new));
    public static final DeferredBlock<Block> FOREST_NODE_BLOCK = BLOCKS.register("forest_node",
            () -> new WandscapeBuildingBlock(BUILDING_PROPS, ForestNodeBE.TYPE_ID, ForestNodeBE::new));
    public static final DeferredBlock<Block> EARTH_NODE_BLOCK = BLOCKS.register("earth_node",
            () -> new WandscapeBuildingBlock(BUILDING_PROPS, EarthNodeBE.TYPE_ID, EarthNodeBE::new));

    // ---- 08 building-core: block items ----
    public static final DeferredItem<BlockItem> TOWN_HALL_ITEM =
            ITEMS.registerSimpleBlockItem(TOWN_HALL_BLOCK);
    public static final DeferredItem<BlockItem> FOREST_NODE_ITEM =
            ITEMS.registerSimpleBlockItem(FOREST_NODE_BLOCK);
    public static final DeferredItem<BlockItem> EARTH_NODE_ITEM =
            ITEMS.registerSimpleBlockItem(EARTH_NODE_BLOCK);

    // ---- 08 building-core: block entities ----
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TownHallBE>> TOWN_HALL_BE =
            BLOCK_ENTITIES.register("town_hall", () ->
                    new BlockEntityType<>(TownHallBE::new, Set.of(TOWN_HALL_BLOCK.get()), null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ForestNodeBE>> FOREST_NODE_BE =
            BLOCK_ENTITIES.register("forest_node", () ->
                    new BlockEntityType<>(ForestNodeBE::new, Set.of(FOREST_NODE_BLOCK.get()), null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EarthNodeBE>> EARTH_NODE_BE =
            BLOCK_ENTITIES.register("earth_node", () ->
                    new BlockEntityType<>(EarthNodeBE::new, Set.of(EARTH_NODE_BLOCK.get()), null));

    // ---- 07 npc-system: entity ----
    public static final DeferredHolder<EntityType<?>, EntityType<WandscapeNpc>> WANDSCAPE_NPC =
            ENTITIES.register("wandscape_npc", () ->
                    EntityType.Builder.of(WandscapeNpc::new, MobCategory.CREATURE)
                            .sized(0.6f, 1.8f)
                            .clientTrackingRange(10)
                            .build("wandscape_npc"));

    // ---- 07 npc-system: spawn egg ----
    public static final DeferredItem<Item> WANDSCAPE_NPC_EGG =
            ITEMS.register("wandscape_npc_spawn_egg", () ->
                    new DeferredSpawnEggItem(
                            () -> (EntityType<? extends Mob>) (EntityType<?>) WANDSCAPE_NPC.get(),
                            0x4B0082,  // dark purple background
                            0xFFD700,  // gold highlight
                            new Item.Properties()));

    // ---- Creative tab ----
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> WANDSCAPE_TAB =
            CREATIVE_MODE_TABS.register("wandscape_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.wandscape"))
                    .icon(() -> new ItemStack(WAND.get()))
                    .displayItems((params, output) -> {
                        output.accept(WAND.get());
                        output.accept(TOWN_HALL_ITEM.get());
                        output.accept(FOREST_NODE_ITEM.get());
                        output.accept(EARTH_NODE_ITEM.get());
                        output.accept(WANDSCAPE_NPC_EGG.get());
                    })
                    .build());

    // ---- API instances ----
    private final BuildingApiImpl buildingApi = new BuildingApiImpl();
    private final BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();

    public Wandscape(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onEntityAttributeCreation);

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        ENTITIES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        NeoForge.EVENT_BUS.register(this);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Register API implementations
        WandscapeApis.setBuildingApi(buildingApi);
        WandscapeApis.setNpcApi(new NpcApiImpl());

        // Register config loaders with data loader
        configLoader.registerWith(DATA_LOADER);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        WandscapeApis.setWandApi(WAND_API);
        WandscapeApis.setElementApi(ELEMENT_API);
        LOGGER.info("Wandscape common setup — wand, element, buildings, npc ready");
    }

    private void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(WANDSCAPE_NPC.get(), WandscapeNpc.createAttributes().build());
    }

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(DATA_LOADER);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Wandscape server starting — bootstrapping engine...");
        EngineBootstrap.bootstrap();
    }

    private int engineTickCount = 0;
    private int mcTickCount = 0;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        var world = WandscapeEngine.getWorld();
        if (world == null) return;

        mcTickCount++;

        // ① Sync MC entity positions → ECS (always, even when gate is closed)
        EntityComponentBridge.INSTANCE.syncPositions(world);

        // ② Gate: skip engine tick when async ops (e.g. MoveOp pathfinding) are in flight
        if (world.hasPendingAsyncOps()) {
            return;
        }

        // ③ Engine logic tick
        engineTickCount++;
        world.tick(1.0f);

        // Heartbeat every ~5 seconds (100 MC ticks)
        if (mcTickCount % 100 == 0) {
            LOGGER.info("[Engine] engineTick=#{} mcTick=#{} — entities={} tasks_in_pool={} pendingAsync={}",
                    engineTickCount, mcTickCount,
                    world.getNextEntityId() - 1,
                    world.taskPool != null ? world.taskPool.size() : 0,
                    world.hasPendingAsyncOps() ? 1 : 0);
        }
    }
}
