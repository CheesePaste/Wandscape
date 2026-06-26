package com.wsteam.wandscape;

import java.util.Set;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.level.BlockEvent;
import com.wsteam.wandscape.building.internal.BuildCompleteListener;
import com.wsteam.wandscape.building.internal.BuildingApiImpl;
import com.wsteam.wandscape.building.internal.BuildingBreakHandler;
import com.wsteam.wandscape.building.internal.BuildingInteractHandler;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.command.ColonyCommand;
import com.wsteam.wandscape.command.FillBuildingCommand;
import com.wsteam.wandscape.command.GenerateElementMappingsCommand;
import com.wsteam.wandscape.command.ManaCommand;
import com.wsteam.wandscape.command.NavTestCommand;
import com.wsteam.wandscape.command.PublishBlueprintCommand;
import com.wsteam.wandscape.command.RecoveryCommand;
import com.wsteam.wandscape.command.RoadCommand;
import com.wsteam.wandscape.command.RoadTestCommand;
import com.wsteam.wandscape.command.SeedWarehouseCommand;
import com.wsteam.wandscape.command.SpiralTestCommand;
import com.wsteam.wandscape.command.StressTestCommand;
import com.wsteam.wandscape.command.TransportCommand;
import com.wsteam.wandscape.command.CitizenCommand;
import com.wsteam.wandscape.engine.road.RoadApiImpl;
import com.wsteam.wandscape.engine.road.RoadEventListener;
import com.wsteam.wandscape.engine.road.RoadSavedData;
import com.wsteam.wandscape.engine.boundary.WandscapeBlockInteractExecutor;
import com.wsteam.wandscape.production.ProductionRecipeLoader;
import com.wsteam.wandscape.production.network.CraftingStationPacket;
import com.wsteam.wandscape.production.network.PotionStationPacket;
import com.wsteam.wandscape.production.network.RequestProductionTaskPacket;
import com.wsteam.wandscape.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.building.network.TavernOpenPacket;
import com.wsteam.wandscape.building.network.TavernRecruitPacket;
import com.wsteam.wandscape.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.building.network.TaskQueueModifyPacket;
import com.wsteam.wandscape.warehouse.WarehouseManager;
import com.wsteam.wandscape.warehouse.WarehouseNotificationHandler;
import com.wsteam.wandscape.warehouse.network.WarehouseActionPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;
import com.wsteam.wandscape.road.network.RoadNetworkSyncPacket;
import com.wsteam.wandscape.road.network.RoadEdgeRemovePacket;
import com.wsteam.wandscape.road.network.RoadEdgePlanPacket;
import com.wsteam.wandscape.road.network.RoadEditorNetwork;
import com.wsteam.wandscape.road.network.RoadBatchPublishPacket;
import com.wsteam.wandscape.road.network.RoadEditorTogglePacket;
import com.wsteam.wandscape.projection.network.ProjectionEnterPacket;
import com.wsteam.wandscape.projection.network.ProjectionEnterResponsePacket;
import com.wsteam.wandscape.projection.network.ProjectionExitPacket;
import com.wsteam.wandscape.projection.network.ProjectionPlacePacket;
import com.wsteam.wandscape.projection.network.ProjectionNetwork;
import com.wsteam.wandscape.projection.network.BuildingDebugRequestPacket;
import com.wsteam.wandscape.projection.network.BuildingDebugResponsePacket;
import com.wsteam.wandscape.task.network.BlueprintListResponsePacket;
import com.wsteam.wandscape.task.network.TaskCreatePacket;
import com.wsteam.wandscape.task.network.TaskEditorOpenPacket;
import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.wsteam.wandscape.engine.source.blueprint.BlueprintConfigLoader;
import com.wsteam.wandscape.core.system.PlayerManualSource;
import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.element.internal.ElementApiImpl;
import com.wsteam.wandscape.element.internal.ElementMappingLoader;
import com.wsteam.wandscape.core.component.ManaPool;
import com.wsteam.wandscape.engine.TaskPoolSavedData;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.bootstrap.EngineBootstrap;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.npc.internal.NpcApiImpl;
import com.wsteam.wandscape.citizen.CitizenEntity;
import com.wsteam.wandscape.citizen.CitizenManager;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.wand.internal.WandApiImpl;
import com.wsteam.wandscape.wand.internal.WandPresetLoader;
import com.wsteam.wandscape.wand.item.WandItem;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Wandscape.MODID)
public class Wandscape {
    public static final String MODID = "wandscape";
    public static final Logger LOGGER = LogUtils.getLogger();

    // ---- DeferredRegisters ----
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, MODID);

    // ---- Debug target ----
    public static BlockPos debugDiamondTarget = null;

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

    // ---- 10 production-stations: loader ----
    public static ProductionRecipeLoader PRODUCTION_RECIPE_LOADER;

    // ---- 07 npc-system: entity ----
    public static final DeferredHolder<EntityType<?>, EntityType<WandscapeNpc>> WANDSCAPE_NPC =
            ENTITIES.register("wandscape_npc", () ->
                    EntityType.Builder.of(WandscapeNpc::new, MobCategory.CREATURE)
                            .sized(0.6f, 1.8f)
                            .clientTrackingRange(10)
                            .build("wandscape_npc"));

    // ---- 07 npc-system: particles ----
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> CAST_BOLT =
            PARTICLE_TYPES.register("cast_bolt", () -> new SimpleParticleType(false));

    // ---- 07 npc-system: spawn egg ----
    public static final DeferredItem<Item> WANDSCAPE_NPC_EGG =
            ITEMS.register("wandscape_npc_spawn_egg", () ->
                    new DeferredSpawnEggItem(
                            () -> (EntityType<? extends Mob>) (EntityType<?>) WANDSCAPE_NPC.get(),
                            0x4B0082,  // dark purple background
                            0xFFD700,  // gold highlight
                            new Item.Properties()));

    // ---- citizen-system: entity ----
    public static final DeferredHolder<EntityType<?>, EntityType<CitizenEntity>> CITIZEN =
            ENTITIES.register("citizen", () ->
                    EntityType.Builder.of(CitizenEntity::new, MobCategory.CREATURE)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(10)
                            .build("citizen"));

    // ---- citizen-system: spawn egg ----
    public static final DeferredItem<Item> CITIZEN_SPAWN_EGG =
            ITEMS.register("citizen_spawn_egg", () ->
                    new DeferredSpawnEggItem(
                            () -> (EntityType<? extends Mob>) (EntityType<?>) CITIZEN.get(),
                            0xFFAA00,  // orange background
                            0xFFFFFF,  // white highlight
                            new Item.Properties()));

    // ---- Creative tab ----
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> WANDSCAPE_TAB =
            CREATIVE_MODE_TABS.register("wandscape_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.wandscape"))
                    .icon(() -> new ItemStack(WAND.get()))
                    .displayItems((params, output) -> {
                        output.accept(WAND.get());
                        output.accept(WANDSCAPE_NPC_EGG.get());
                        output.accept(CITIZEN_SPAWN_EGG.get());
                    })
                    .build());

    // ---- API instances ----
    private final BuildingApiImpl buildingApi = new BuildingApiImpl();
    private final BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();
    public static final BlueprintConfigLoader BLUEPRINT_CONFIG_LOADER = new BlueprintConfigLoader();

    public Wandscape(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onEntityAttributeCreation);
        modEventBus.addListener(this::onRegisterPayloads);

        ITEMS.register(modEventBus);
        ENTITIES.register(modEventBus);
        PARTICLE_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(BuildingInteractHandler.class);
        NeoForge.EVENT_BUS.register(BuildingBreakHandler.class);
        CitizenManager.getInstance().register();
        WarehouseNotificationHandler.register();

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Register API implementations
        WandscapeApis.setBuildingApi(buildingApi);
        WandscapeApis.setNpcApi(new NpcApiImpl());
        WandscapeApis.setWarehouseApi(new WarehouseManager());
        WandscapeApis.setColonyApi(com.wsteam.wandscape.engine.colony.ColonyApiImpl.get());

        // Register config loaders with data loader
        configLoader.registerWith(DATA_LOADER);
        BLUEPRINT_CONFIG_LOADER.registerWith(DATA_LOADER);
        WandscapeEngine.setBlueprintConfigLoader(BLUEPRINT_CONFIG_LOADER);

        // Production recipe loader
        PRODUCTION_RECIPE_LOADER = new ProductionRecipeLoader(DATA_LOADER, ELEMENT_MAPPING_LOADER);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        WandscapeApis.setWandApi(WAND_API);
        WandscapeApis.setElementApi(ELEMENT_API);
        LOGGER.info("Wandscape common setup — wand, element, buildings, npc ready");
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(MODID)
                .versioned("1.0")
                .playToClient(
                        WarehouseDataPacket.TYPE,
                        WarehouseDataPacket.STREAM_CODEC,
                        (packet, ctx) -> WarehouseDataPacket.handleClient(packet))
                .playToClient(
                        RoadNetworkSyncPacket.TYPE,
                        RoadNetworkSyncPacket.STREAM_CODEC,
                        (packet, ctx) -> RoadNetworkSyncPacket.handleClient(packet))
                .playToClient(
                        BlueprintListResponsePacket.TYPE,
                        BlueprintListResponsePacket.STREAM_CODEC,
                        (packet, ctx) -> BlueprintListResponsePacket.handleClient(packet))
                .playToClient(
                        WorkstationDataPacket.TYPE,
                        WorkstationDataPacket.STREAM_CODEC,
                        (packet, ctx) -> WorkstationDataPacket.handleClient(packet))
                .playToClient(
                        CraftingStationPacket.TYPE,
                        CraftingStationPacket.STREAM_CODEC,
                        (packet, ctx) -> CraftingStationPacket.handleClient(packet))
                .playToClient(
                        TavernOpenPacket.TYPE,
                        TavernOpenPacket.STREAM_CODEC,
                        (packet, ctx) -> TavernOpenPacket.handleClient(packet))
                .playToClient(
                        PotionStationPacket.TYPE,
                        PotionStationPacket.STREAM_CODEC,
                        (packet, ctx) -> PotionStationPacket.handleClient(packet))
                .playToClient(
                        TaskQueueDataPacket.TYPE,
                        TaskQueueDataPacket.STREAM_CODEC,
                        (packet, ctx) -> TaskQueueDataPacket.handleClient(packet))
                .playToServer(
                        RoadEdgeRemovePacket.TYPE,
                        RoadEdgeRemovePacket.STREAM_CODEC,
                        (packet, ctx) -> RoadEdgeRemovePacket.handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        RoadEdgePlanPacket.TYPE,
                        RoadEdgePlanPacket.STREAM_CODEC,
                        (packet, ctx) -> RoadEdgePlanPacket.handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        RoadEditorTogglePacket.TYPE,
                        RoadEditorTogglePacket.STREAM_CODEC,
                        (packet, ctx) -> RoadEditorTogglePacket.handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        RoadBatchPublishPacket.TYPE,
                        RoadBatchPublishPacket.STREAM_CODEC,
                        (packet, ctx) -> RoadBatchPublishPacket.handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        TaskEditorOpenPacket.TYPE,
                        TaskEditorOpenPacket.STREAM_CODEC,
                        (packet, ctx) -> TaskEditorOpenPacket.handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        TaskCreatePacket.TYPE,
                        TaskCreatePacket.STREAM_CODEC,
                        (packet, ctx) -> TaskCreatePacket.handleServer(packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToServer(
                        RequestProductionTaskPacket.TYPE,
                        RequestProductionTaskPacket.STREAM_CODEC,
                        RequestProductionTaskPacket::handleServer)
                .playToServer(
                        TaskQueueModifyPacket.TYPE,
                        TaskQueueModifyPacket.STREAM_CODEC,
                        TaskQueueModifyPacket::handleServer)
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
                .playToServer(
                        BuildingDebugRequestPacket.TYPE,
                        BuildingDebugRequestPacket.STREAM_CODEC,
                        (packet, ctx) -> BuildingDebugRequestPacket.handleServer(packet,
                                (net.minecraft.server.level.ServerPlayer) ctx.player()))
                .playToClient(
                        BuildingDebugResponsePacket.TYPE,
                        BuildingDebugResponsePacket.STREAM_CODEC,
                        (packet, ctx) -> BuildingDebugResponsePacket.handleClient(packet));
    }

    private void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(WANDSCAPE_NPC.get(), WandscapeNpc.createAttributes().build());
        event.put(CITIZEN.get(), CitizenEntity.createAttributes().build());
    }

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(DATA_LOADER);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Wandscape server starting — bootstrapping engine...");
        buildingApi.setLevel(event.getServer().overworld());
        EngineBootstrap.bootstrap();
        // Safety sweep: kill any citizen entities that survived a dirty shutdown
        // (requiresCustomPersistence should prevent save, but belt-and-suspenders)
        CitizenManager.killAllStrayCitizens(event.getServer().overworld());
        // Spawn initial citizen NPCs
        CitizenManager.getInstance().spawnInitial(event.getServer().overworld());
        BuildCompleteListener.register();
        // Rebuild colony spatial index from saved data
        var colonyApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
        if (colonyApi instanceof com.wsteam.wandscape.engine.colony.ColonyApiImpl impl) {
            impl.rebuildFromSavedData();
        }

        // Wire production loaders to block interact executor
        WandscapeBlockInteractExecutor.setElementMappingLoader(ELEMENT_MAPPING_LOADER);
        WandscapeBlockInteractExecutor.setProductionRecipeLoader(PRODUCTION_RECIPE_LOADER);

        // ---- Road system ----
        RoadEventListener.register();

        // Load persisted tasks from previous session
        ServerLevel level = event.getServer().overworld();
        var world = WandscapeEngine.getWorld();
        if (world != null && world.taskPool != null) {
            var saved = TaskPoolSavedData.getOrCreate(level, world.taskPool);
            WandscapeEngine.setTaskPoolSavedData(saved);
            // Mark dirty when pool changes so SavedData writes to disk
            world.taskPool.onChanged = saved::setDirty;
            LOGGER.info("Task persistence wired — pool has {} active tasks", world.taskPool.size());
        }

        // Road persistence + API
        var roadSaved = RoadSavedData.getOrCreate(level);
        WandscapeEngine.setRoadSavedData(roadSaved);
        WandscapeApis.setRoadApi(new RoadApiImpl());
        LOGGER.info("Road system wired — {} edges persisted", roadSaved.getNetwork().edgeCount());

        // Wire manual task publishing for GUI (network layer reads PlayerManualSource from engine)
        if (world != null && world.taskPool != null) {
            PlayerManualSource playerSource = new PlayerManualSource(world.taskPool);
            WandscapeEngine.setPlayerManualSource(playerSource);
            LOGGER.info("PlayerManualSource wired — manual task publishing available");
        }
    }

    /** Discard all citizen entities BEFORE world saves to disk. */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        CitizenManager.getInstance().onServerStopping();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        LOGGER.info("Wandscape server stopped — resetting engine.");
        CitizenManager.getInstance().onServerStopped();
        buildingApi.setLevel(null);
        WandscapeEngine.reset();
        EntityComponentBridge.INSTANCE.clear();
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getState().is(Blocks.DIAMOND_BLOCK)) {
            debugDiamondTarget = event.getPos();
            LOGGER.info("[Debug] Diamond block placed at {}", debugDiamondTarget);
        }
    }

    private int engineTickCount = 0;
    private int mcTickCount = 0;

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        var root = Commands.literal("wandscape")
                .requires(src -> src.hasPermission(2))
                .then(GenerateElementMappingsCommand.node())
                .then(FillBuildingCommand.fillNode())
                .then(ManaCommand.node())
                .then(NavTestCommand.node())
                .then(ColonyCommand.node())
                .then(PublishBlueprintCommand.buildNode())
                .then(RecoveryCommand.node())
                .then(RoadCommand.node())
                .then(RoadTestCommand.node())
                .then(SeedWarehouseCommand.node())
                .then(SpiralTestCommand.node())
                .then(StressTestCommand.buildNode())
                .then(TransportCommand.node())
                .then(CitizenCommand.node());
        dispatcher.register(root);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            RoadEditorNetwork.removeByUuid(sp.getUUID());
            ProjectionNetwork.removeByUuid(sp.getUUID());
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        var world = WandscapeEngine.getWorld();
        if (world == null) return;

        mcTickCount++;

        // ⓪ Citizen NPC tick
        CitizenManager.getInstance().tick(event.getServer().overworld());

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

        // ② Sync MC entity positions → ECS
        EntityComponentBridge.INSTANCE.syncPositions(world);

        // ②b Flush any NPCs that loaded before the engine was ready
        EntityComponentBridge.INSTANCE.flushDeferredJoins(world);

        // ③ Engine logic tick (incl. NavigationSystem which drives movement)
        engineTickCount++;
        world.tick(1.0f);

        // Heartbeat every ~5 seconds (100 MC ticks)
        if (mcTickCount % 100 == 0) {
            LOGGER.info("[Engine] engineTick=#{} mcTick=#{} — entities={} tasks_in_pool={} pendingAsync={}",
                    engineTickCount, mcTickCount,
                    world.getNextEntityId() - 1,
                    world.taskPool != null ? world.taskPool.size() : 0,
                    world.hasPendingAsyncOps() ? 1 : 0);

            // Mana debug: send NPC mana values to the player who enabled debug
            if (WandscapeEngine.isManaDebug()) {
                ServerPlayer target = WandscapeEngine.getManaDebugTarget();
                if (target != null && !target.isRemoved()) {
                    var manaEntities = world.query(ManaPool.class);
                    StringBuilder sb = new StringBuilder("[Wandscape Mana] ");
                    if (manaEntities.isEmpty()) {
                        sb.append("no entities");
                    } else {
                        for (int i = 0; i < manaEntities.size(); i++) {
                            long id = manaEntities.get(i);
                            ManaPool pool = world.get(id, ManaPool.class);
                            if (pool != null) {
                                if (i > 0) sb.append(" | ");
                                sb.append(String.format("NPC-%d: %.0f/%d",
                                        id, pool.current(), pool.max()));
                            }
                        }
                    }
                    target.sendSystemMessage(Component.literal(sb.toString()));
                } else {
                    WandscapeEngine.setManaDebug(false);
                    WandscapeEngine.setManaDebugTarget(null);
                }
            }
        }
    }
}
