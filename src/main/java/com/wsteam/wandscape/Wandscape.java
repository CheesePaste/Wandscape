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
import com.wsteam.wandscape.command.FillBuildingCommand;
import com.wsteam.wandscape.command.NavTestCommand;
import com.wsteam.wandscape.command.PublishBlueprintCommand;
import com.wsteam.wandscape.command.StressTestCommand;
import com.wsteam.wandscape.warehouse.WarehouseManager;
import com.wsteam.wandscape.warehouse.WarehouseMenu;
import com.wsteam.wandscape.warehouse.WarehouseNotificationHandler;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;
import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.wsteam.wandscape.engine.source.blueprint.BlueprintConfigLoader;
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

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
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
import net.neoforged.neoforge.event.server.ServerStartingEvent;
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
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, MODID);

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

    // ---- 04 warehouse-system: menu ----
    public static final DeferredHolder<MenuType<?>, MenuType<WarehouseMenu>> WAREHOUSE_MENU =
            MENU_TYPES.register("warehouse", () ->
                    new MenuType<>(WarehouseMenu::new, FeatureFlags.VANILLA_SET));

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

    // ---- Creative tab ----
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> WANDSCAPE_TAB =
            CREATIVE_MODE_TABS.register("wandscape_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.wandscape"))
                    .icon(() -> new ItemStack(WAND.get()))
                    .displayItems((params, output) -> {
                        output.accept(WAND.get());
                        output.accept(WANDSCAPE_NPC_EGG.get());
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
        MENU_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(BuildingInteractHandler.class);
        NeoForge.EVENT_BUS.register(BuildingBreakHandler.class);
        WarehouseNotificationHandler.register();

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Register API implementations
        WandscapeApis.setBuildingApi(buildingApi);
        WandscapeApis.setNpcApi(new NpcApiImpl());
        WandscapeApis.setWarehouseApi(new WarehouseManager());

        // Register config loaders with data loader
        configLoader.registerWith(DATA_LOADER);
        BLUEPRINT_CONFIG_LOADER.registerWith(DATA_LOADER);
        WandscapeEngine.setBlueprintConfigLoader(BLUEPRINT_CONFIG_LOADER);
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
                        (packet, ctx) -> WarehouseDataPacket.handleClient(packet));
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
        buildingApi.setLevel(event.getServer().overworld());
        EngineBootstrap.bootstrap();
        BuildCompleteListener.register();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        LOGGER.info("Wandscape server stopped — resetting engine.");
        buildingApi.setLevel(null);
        WandscapeEngine.reset();
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
                .then(FillBuildingCommand.fillNode())
                .then(NavTestCommand.node())
                .then(PublishBlueprintCommand.buildNode())
                .then(StressTestCommand.buildNode());
        dispatcher.register(root);
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
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

        // ② Sync MC entity positions → ECS
        EntityComponentBridge.INSTANCE.syncPositions(world);

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
        }
    }
}
