package com.wsteam.wandscape;
import com.wsteam.wandscape.content.warehouse.transport.TransportStartPacket;
import com.wsteam.wandscape.content.warehouse.transport.TransportItemEntity;
import com.wsteam.wandscape.content.task.types.EffectId;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.EntityId;
import com.wsteam.wandscape.content.magic.network.MagicCircleCastPacket;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.content.items.network.GuideDocOpenPacket;
import com.wsteam.wandscape.foundation.networking.ParticleBurstPacket;
import com.wsteam.wandscape.content.items.network.GuideProgressSyncPacket;
import com.wsteam.wandscape.content.colony.network.ColonyAmbientPacket;
import com.wsteam.wandscape.content.colony.network.ColonyCreatePromptPacket;
import com.wsteam.wandscape.foundation.util.ItemKey;

import com.mojang.blaze3d.platform.InputConstants;
import com.wsteam.wandscape.content.building.client.*;
import com.wsteam.wandscape.content.building.network.*;
import com.wsteam.wandscape.content.building.scanner.client.ScannerRenderer;
import com.wsteam.wandscape.content.building.projection.client.*;
import com.wsteam.wandscape.content.building.projection.network.BuildingDebugResponsePacket;
import com.wsteam.wandscape.content.building.projection.network.ProjectionEnterResponsePacket;
import com.wsteam.wandscape.content.building.scanner.client.gizmo.ScannerGizmoController;
import com.wsteam.wandscape.content.building.scanner.client.gizmo.ScannerGizmoOverlay;
import com.wsteam.wandscape.content.building.scanner.client.gizmo.ScannerGizmoRenderer;
import com.wsteam.wandscape.content.items.compass.client.CompassTargetClientCache;
import com.wsteam.wandscape.content.items.guidebook.network.GuideBookOpenPacket;
import com.wsteam.wandscape.content.items.scepter.OmniScepterItem;
import com.wsteam.wandscape.content.items.scepter.ScepterKind;
import com.wsteam.wandscape.content.road.client.SplineEditorController;
import com.wsteam.wandscape.content.road.client.SplineEditorRenderer;
import com.wsteam.wandscape.content.road.client.studio.RoadStudioOverlay;
import com.wsteam.wandscape.content.tourist.network.TouristBubblePacket;
import com.wsteam.wandscape.content.colony.sound.ColonyAmbientSystem;
import com.wsteam.wandscape.content.magic.client.MagicBeamEntityRenderer;
import com.wsteam.wandscape.content.magic.client.MagicCircleDotParticle;
import com.wsteam.wandscape.content.magic.client.MagicCircleEmitter;
import com.wsteam.wandscape.content.npc.client.*;
import com.wsteam.wandscape.content.npc.network.NpcDataPacket;
import com.wsteam.wandscape.content.colony.overview.client.OverviewFlightController;
import com.wsteam.wandscape.content.colony.overview.client.OverviewRenderer;
import com.wsteam.wandscape.content.production.client.CraftingStationScreen;
import com.wsteam.wandscape.content.production.client.MagicStationScreen;
import com.wsteam.wandscape.content.production.client.WorkstationScreen;
import com.wsteam.wandscape.content.production.network.CraftingStationPacket;
import com.wsteam.wandscape.content.production.network.MagicStationPacket;
import com.wsteam.wandscape.content.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.content.building.projection.client.ProjectionFlightController;
import com.wsteam.wandscape.content.road.client.RoadConstructionGhost;
import com.wsteam.wandscape.content.road.client.RoadPlacementController;
import com.wsteam.wandscape.content.road.client.RoadPlacementRenderer;
import com.wsteam.wandscape.content.building.render.BuildingGhostVboCache;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelController;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelOverlay;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;
import com.wsteam.wandscape.content.building.preview.BuildingPreviewGifCache;
import com.wsteam.wandscape.content.tourist.client.TouristDebugRenderer;
import com.wsteam.wandscape.content.tourist.client.TouristRenderer;
import com.wsteam.wandscape.content.tourist.client.TouristScreen;
import com.wsteam.wandscape.content.tourist.network.TouristDataPacket;
import com.wsteam.wandscape.content.warehouse.client.WarehouseScreen;
import com.wsteam.wandscape.content.warehouse.network.WarehouseDataPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.CompassItemPropertyFunction;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class WandscapeClient {

    public static final KeyMapping PROJECTION_TOGGLE = new KeyMapping(
            "key.wandscape.projection",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.wandscape"
    );


    public static final KeyMapping GUIDE_FOLD_TOGGLE = new KeyMapping(
            "key.wandscape.guide_fold",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_TAB,
            "key.categories.wandscape"
    );


    public static final KeyMapping RAISE_CURSOR = new KeyMapping(
            "key.wandscape.raise_cursor",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            "key.categories.wandscape"
    );


    public static final KeyMapping GUIDE_TOGGLE = new KeyMapping(
            "key.wandscape.guide",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            "key.categories.wandscape"
    );

    public static final KeyMapping PANEL_AREAS_TOGGLE = new KeyMapping(
            "key.wandscape.building_areas",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "key.categories.wandscape"
    );

    public static final KeyMapping PANEL_HIDE_TOGGLE = new KeyMapping(
            "key.wandscape.panel_hide",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F4,
            "key.categories.wandscape"
    );

    public static final KeyMapping WAREHOUSE_TERMINAL_KEY = new KeyMapping(
            "key.wandscape.warehouse_terminal",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            "key.categories.wandscape"
    );

    public static void init(net.neoforged.bus.api.IEventBus modEventBus, ModContainer container) {
        modEventBus.register(WandscapeClient.class);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, WandscapeClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, WandscapeClient::onPlayerLoggingIn);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, WandscapeClient::onPlayerLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> MagicCircleEmitter.tick());
        RoadPlacementController.register();
        RoadPlacementRenderer.register();
        RoadConstructionGhost.register();
        ProjectionRenderer.register();
        ProjectionFlightController.register();
        BuildGizmoController.register();
        BuildGizmoRenderer.register();
        BuildingDebugController.register();
        BuildingDebugOverlay.register();
        TouristDebugRenderer.register();
        BuildingAreaRenderer.register();
        ConstructionGhostRenderer.register();

        // Wandscape Panel
        WandscapePanelController.register();
        // Register the preview bake pump BEFORE the panel overlay so blits see fresh frames
        com.wsteam.wandscape.content.building.preview.BuildingPreviewGifCache.register();
        WandscapePanelOverlay.register();
        com.wsteam.wandscape.foundation.ui.util.WandscapeHighlightRenderer.register();

        // Replay mod compat: don't open UI screens during ReplayMod/ReforgedPlay playback
        com.wsteam.wandscape.foundation.ui.ReplayScreenGuard.register();

        // Overview mode
        OverviewFlightController.register();
        OverviewRenderer.register();

        // Spline Road Editor (Native visual editor + world interaction)
        SplineEditorController.register();
        SplineEditorRenderer.register();
        RoadStudioOverlay.register();

        // Building Scanner 3D Gizmo Visual Adjuster
        ScannerGizmoController.register();
        ScannerGizmoRenderer.register();
        ScannerGizmoOverlay.register();
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(Wandscape.WAREHOUSE_MENU.get(), WarehouseScreen::new);
        event.register(Wandscape.NPC_MENU.get(), NpcScreen::new);
        event.register(Wandscape.NPC_STRATEGY_MENU.get(), NpcStrategyScreen::new);
        // Curios 兼容：法师饰品栏（仅 Curios 加载时在实现类内注册，避免无 Curios 时缺类崩溃）
        com.wsteam.wandscape.compat.curios.CuriosCompat.registerNpcMenuScreens(event);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Building preview GIF bake params (clarity + framerate) from config
        BuildingPreviewGifCache.configure(com.wsteam.wandscape.ClientConfig.PREVIEW_RESOLUTION.get(), com.wsteam.wandscape.ClientConfig.PREVIEW_FPS.get());
        // Wire server→client packet handlers — open MedievalScreen directly.
        WarehouseDataPacket.setClientHandler(packet -> {
            // The warehouse screen opens through the vanilla menu flow (openMenu +
            // RegisterMenuScreensEvent); the data packet only refreshes an open screen.
            if (Minecraft.getInstance().screen instanceof WarehouseScreen ws) {
                ws.updateItems(packet);
            }
        });
        WorkstationDataPacket.setClientHandler(packet -> {
            var screen = Minecraft.getInstance().screen;
            if (screen instanceof WorkstationScreen ws) {
                ws.updateData(packet);
            } else {
                var ws = new WorkstationScreen();
                ws.updateData(packet);
                Minecraft.getInstance().setScreen(ws);
            }
        });
        NodeDataPacket.setClientHandler(packet -> {
            var screen = Minecraft.getInstance().screen;
            if (screen instanceof NodeScreen ns) {
                ns.updateData(packet);
            } else {
                var ns = new NodeScreen();
                ns.updateData(packet);
                Minecraft.getInstance().setScreen(ns);
            }
        });
        ConstructionSiteDataPacket.setClientHandler(packet -> {
            var screen = Minecraft.getInstance().screen;
            if (screen instanceof ConstructionSiteScreen cs && cs.matches(packet.buildingId())) {
                cs.updateData(packet);
            } else {
                Minecraft.getInstance().setScreen(new ConstructionSiteScreen(packet));
            }
        });
        TaskQueueDataPacket.setClientHandler(packet -> {
            var screen = Minecraft.getInstance().screen;
            if (screen instanceof WorkstationScreen ws) {
                ws.updateQueueData(packet);
            } else if (screen instanceof CraftingStationScreen cs) {
                cs.updateQueueData(packet);
            } else if (screen instanceof MagicStationScreen ms) {
                ms.updateQueueData(packet);
            } else if (screen instanceof NodeScreen ns) {
                ns.updateQueueData(packet);
            }
        });
        CraftingStationPacket.setClientHandler(packet -> {
            var screen = Minecraft.getInstance().screen;
            if (screen instanceof CraftingStationScreen cs) {
                cs.updateData(packet);
            } else {
                var cs = new CraftingStationScreen();
                cs.updateData(packet);
                Minecraft.getInstance().setScreen(cs);
            }
        });
        MagicStationPacket.setClientHandler(packet -> {
            var screen = Minecraft.getInstance().screen;
            if (screen instanceof MagicStationScreen ms) {
                ms.updateData(packet);
            } else {
                var ms = new MagicStationScreen();
                ms.updateData(packet);
                Minecraft.getInstance().setScreen(ms);
            }
        });
        TavernOpenPacket.setClientHandler(packet -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof TavernScreen existing) {
                existing.updateData(packet.recruitCount(), packet.mageResumes());
            } else {
                mc.setScreen(new TavernScreen(packet.buildingPos(), packet.colonyId(),
                        packet.recruitCount(), packet.mageResumes(), packet.creator()));
            }
        });
        MageHutDataPacket.setClientHandler(packet -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof MageHutScreen existing) {
                existing.apply(packet);
            } else {
                mc.setScreen(new MageHutScreen(packet));
            }
        });
        HotelOpenPacket.setClientHandler(packet -> {
            Minecraft.getInstance().setScreen(new HotelScreen(
                    packet.buildingPos(), packet.colonyId(), packet.buildingId(), packet.creator(),
                    packet.maxOccupancy(), packet.currentOccupancy(),
                    packet.guestNames()));
        });
        AltarOpenPacket.setClientHandler(packet -> {
            Minecraft.getInstance().setScreen(new AltarScreen(
                    packet.buildingPos(), packet.colonyId(), packet.buildingId(), packet.creator(),
                    packet.spells()));
        });
        BuildingInfoPacket.setClientHandler(packet ->
                Minecraft.getInstance().setScreen(new BuildingInfoScreen(packet)));
        NpcDataPacket.setClientHandler(packet -> {
            var mc = Minecraft.getInstance();
            // 装备/策略屏通过 openMenu 打开（RegisterMenuScreensEvent）；数据包仅刷新已开屏幕。
            if (mc.screen instanceof NpcStrategyScreen strategyScreen) {
                strategyScreen.apply(packet);
            } else if (mc.screen instanceof NpcScreen existing) {
                existing.apply(packet);
            }
        });
        TouristDataPacket.setClientHandler(packet -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof TouristScreen existing) {
                existing.apply(packet);
            } else {
                mc.setScreen(new TouristScreen(packet));
            }
        });
        com.wsteam.wandscape.content.colony.network.ColonyAmbientPacket.setClientHandler(packet ->
                ColonyAmbientSystem.setState(packet.playing(), packet.day()));
        ShopOpenPacket.setClientHandler(packet -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof ShopScreen existing) {
                existing.updateFrom(packet.stock(), packet.maxStocks());
            } else if (mc.screen == null) {
                mc.setScreen(new ShopScreen(packet.buildingPos(), packet.colonyId(),
                        packet.buildingId(), packet.creator(), packet.stock(), packet.maxStocks()));
            }
        });

        // Town hall screen
        TownHallOpenPacket.setClientHandler(packet -> {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new TownHallScreen(
                            packet.buildingPos(), packet.colonyId(),
                            packet.colonyName(), packet.level(), packet.experience(),
                            packet.expToNext(), packet.founderName(), packet.canUseWarehouse(),
                            packet.namingStyle(), packet.creator()));
        });

        // Colony create prompt: town hall right-clicked but no colony exists
        com.wsteam.wandscape.content.colony.network.ColonyCreatePromptPacket.setClientHandler(packet -> {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new TownHallCreateScreen(packet.townHallAnchor(), packet.creator()));
        });

        // Guide test screen
        com.wsteam.wandscape.content.items.network.GuideDocOpenPacket.setClientHandler(packet -> {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.wsteam.wandscape.foundation.ui.guide.GuideScreen(packet.markdownContent()));
        });

        // Guide book: right-click opens the tutorial home (index_guide), locale-resolved
        GuideBookOpenPacket.setClientHandler(packet -> {
            String docPath = packet.docPath();
            String content = com.wsteam.wandscape.foundation.ui.markdown.navigation.DocumentLoader.loadMarkdown(docPath);
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.wsteam.wandscape.foundation.ui.guide.GuideScreen(null, content, docPath));
        });

        // Guide progress seed — apply saved tutorial step/dismissal on panel open
        com.wsteam.wandscape.content.items.network.GuideProgressSyncPacket.setClientHandler(packet ->
                com.wsteam.wandscape.foundation.ui.guidance.GuideSession.applySync(
                        packet.stepIndex(), packet.dismissed()));

        com.wsteam.wandscape.content.warehouse.transport.TransportStartPacket.setClientHandler(packet -> {
            var mc = Minecraft.getInstance();
            var level = mc.level;
            if (level == null) return;
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.tryParse(packet.itemKey().itemId()));
            if (item == null) return;
            var stack = new net.minecraft.world.item.ItemStack(item, packet.count());
            if (packet.itemKey().nbt() != null && !packet.itemKey().nbt().isEmpty()) {
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.of(packet.itemKey().nbt().copy()));
            }
            var center = net.minecraft.world.phys.Vec3.atCenterOf(packet.from());
            var entity = new com.wsteam.wandscape.content.warehouse.transport.TransportItemEntity(level, center.x, center.y + 0.5, center.z, stack);
            entity.setRoute(packet.route());
            entity.setId(-level.random.nextInt(Integer.MAX_VALUE));
            level.addEntity(entity);
        });

        BuildingDebugResponsePacket.setClientHandler(packet -> {
            Minecraft.getInstance().execute(() -> {
                boolean active = BuildingDebugClientState.isActive();
                boolean hasPos = BuildingDebugClientState.getLastRequestedPos() != null;
                if (active && hasPos) {
                    BuildingDebugClientState.setCachedData(packet);
                }
            });
        });

        ProjectionEnterResponsePacket.setClientHandler(packet -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (packet.granted()) {
                ProjectionClientState.enterProjection(packet.bodyAnchor(), packet.buildingSlots());
                com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState.openBuildingBar();
            } else {
                ProjectionClientState.exitProjection();
                com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState.closeBuildingBar();
                if (com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState.isPanelOpen()) {
                    com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState.setSubMode(com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState.SubMode.NONE);
                    com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState.closeBuildingBar();
                }
                mc.player.displayClientMessage(Component.translatable("message.wandscape.projection.rejected"), false);
            }
        });

        com.wsteam.wandscape.content.magic.network.MagicCircleCastPacket.setClientHandler(packet -> {
            var mc = Minecraft.getInstance();
            if (mc.level instanceof net.minecraft.client.multiplayer.ClientLevel cl) {
                MagicCircleEmitter.add(cl, packet.effectId(), packet.pos(), packet.axis(), packet.circleId(), packet.casterUuid());
            }
        });

        com.wsteam.wandscape.foundation.networking.ParticleBurstPacket.setClientHandler(packet -> {
            var mc = Minecraft.getInstance();
            if (!(mc.level instanceof net.minecraft.client.multiplayer.ClientLevel cl)) return;
            var rand = cl.random;
            for (int i = 0; i < packet.count(); i++) {
                double ox, oy, oz, vx, vy, vz;
                if (packet.vertical()) {
                    ox = (rand.nextDouble() - 0.5) * 0.6;
                    oy = rand.nextDouble() * 3.5;
                    oz = (rand.nextDouble() - 0.5) * 0.6;
                    vx = 0;
                    vy = 0.03 + rand.nextDouble() * 0.05;
                    vz = 0;
                } else {
                    double theta = rand.nextDouble() * Math.PI * 2;
                    double phi = Math.acos(2 * rand.nextDouble() - 1);
                    double sp = 0.1 + rand.nextDouble() * 0.3;
                    ox = (rand.nextDouble() - 0.5) * 0.4;
                    oy = (rand.nextDouble() - 0.5) * 0.4;
                    oz = (rand.nextDouble() - 0.5) * 0.4;
                    vx = Math.sin(phi) * Math.cos(theta) * sp;
                    vy = Math.cos(phi) * sp * 0.5 + 0.03;
                    vz = Math.sin(phi) * Math.sin(theta) * sp;
                }
                MagicCircleDotParticle.spawnMoving(cl, packet.pos().x + ox, packet.pos().y + oy, packet.pos().z + oz,
                        vx, vy, vz, packet.r(), packet.g(), packet.b(),
                        packet.size(), 0.9f, packet.lifetime());
            }
        });

        TouristBubblePacket.setClientHandler(packet -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null) return;
            var e = mc.level.getEntity(packet.entityId());
            if (e == null) return;
            com.wsteam.wandscape.foundation.ui.bubble.TransientBubbleStore.trigger(
                    e.getUUID(), packet.iconId(), packet.count(), e.tickCount);
        });

        BuildingConfigSyncChunkPacket.setClientHandler(
                BuildingConfigSyncReceiver::onChunk);

        // Transient feedback: show on the open MedievalScreen if any, else the action bar.
        com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket.setClientHandler(packet -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof com.wsteam.wandscape.foundation.ui.component.MedievalScreen ms) {
                ms.showFeedback(packet.message(),
                        packet.isError()
                                ? com.wsteam.wandscape.foundation.ui.theme.MedievalColors.DANGER_RED
                                : com.wsteam.wandscape.foundation.ui.theme.MedievalColors.ACCENT_GOLD);
            } else if (mc.player != null) {
                mc.player.displayClientMessage(packet.message(), true);
            }
        });

        // 魔法指南针：注册 angle item property，使指针指向自己殖民地的市政厅（vanilla 指南针帧模型）。
        // 无市政厅时 CompassTargetClientCache 为 null，指针乱转（与 vanilla 无 lodestone 同义）。
        CompassItemPropertyFunction.CompassTarget compassTarget =
                (level, stack, entity) -> CompassTargetClientCache.get();
        for (var compass : java.util.List.of(
                Wandscape.MAGIC_COMPASS, Wandscape.ADVANCED_MAGIC_COMPASS, Wandscape.ULTIMATE_MAGIC_COMPASS)) {
            ItemProperties.register(compass.get(), ResourceLocation.withDefaultNamespace("angle"),
                    new CompassItemPropertyFunction(compassTarget));
        }

        Log.info("Wandscape", "Wandscape client setup complete");
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PROJECTION_TOGGLE);
        event.register(GUIDE_FOLD_TOGGLE);
        event.register(RAISE_CURSOR);
        event.register(GUIDE_TOGGLE);
        event.register(PANEL_AREAS_TOGGLE);
        event.register(PANEL_HIDE_TOGGLE);
        event.register(WAREHOUSE_TERMINAL_KEY);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        ColonyAmbientSystem.tick();

        Minecraft mc = Minecraft.getInstance();
        // 保险：管理面板仅支持主世界。若面板在主世界打开后进入下界/末地等维度，
        // 立即回收面板，避免俯瞰相机等 UI 状态在非主世界继续运行。
        if (WandscapePanelState.isPanelOpen() && !WandscapePanelState.isInSupportedWorld()) {
            WandscapePanelState.closePanel();
        }

        // F4: 专用「隐藏/显示面板」键——切换面板可见性。可见→隐藏；隐藏（含被 F1 隐藏）→显示。
        // 隐藏后面板不渲染、输入穿透；F1 隐藏全部 GUI 时面板同样隐藏（见 isPanelHidden）。
        while (PANEL_HIDE_TOGGLE.consumeClick()) {
            if (WandscapePanelState.isPanelOpen()) {
                WandscapePanelState.setPanelHidden(!WandscapePanelState.isPanelHidden());
            }
        }

        // When the building search box is focused, letter keys must type into it,
        // not trigger panel hotkeys — so swallow V/C/H clicks while it's focused.
        boolean searchFocused = WandscapePanelState.isBuildingBarSearchFocused();
        while (PROJECTION_TOGGLE.consumeClick()) {
            // V key: toggle Wandscape panel open/close
            if (searchFocused) continue;
            if (WandscapePanelState.isPanelOpen()) {
                // 面板开着但被隐藏 → V 恢复显示（保留子模式状态），而非关闭
                if (WandscapePanelState.isPanelHidden()) {
                    WandscapePanelState.setPanelHidden(false);
                } else {
                    WandscapePanelState.closePanel();
                }
            } else {
                // 非主世界维度拒绝打开管理面板（打开会触发俯瞰相机等 UI 状态，导致面板/UI 异常）
                if (!WandscapePanelState.isInSupportedWorld()) {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                Component.translatable("message.wandscape.panel.overworld_only"), false);
                    }
                    continue;
                }
                WandscapePanelState.openPanel();
            }
        }
        while (GUIDE_TOGGLE.consumeClick()) {
            // H key: open guide — panel has its own handler; this covers non-panel contexts
            if (searchFocused) continue;
            if (!WandscapePanelState.isPanelOpen()) {
                openGuideIndex();
            }
        }
        while (WAREHOUSE_TERMINAL_KEY.consumeClick()) {
            if (searchFocused) continue;
            if (mc != null && mc.screen == null) {
                PacketDistributor.sendToServer(new com.wsteam.wandscape.content.warehouse.network.WarehouseTerminalKeyPacket());
            }
        }
    }

    /** Opens the guide index page (callable from anywhere). */
    public static void openGuideIndex() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            String content = com.wsteam.wandscape.foundation.ui.markdown.navigation.DocumentLoader.loadMarkdown("index_guide");
            mc.setScreen(new com.wsteam.wandscape.foundation.ui.guide.GuideScreen(null, content, "index_guide"));
        }
    }

    /** Welcome message on world join — points new players at the V-key building panel. */
    private static void onPlayerLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        var player = event.getPlayer();
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.wandscape.town.welcome"), false);
        }
        // Pre-warm building preview GIFs in the background so the panel is ready early
        BuildingPreviewGifCache.warmAll();
    }

    /** Reset client panel/UI state on disconnect so it doesn't leak into the next world. */
    private static void onPlayerLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WandscapePanelState.reset();
        CompassTargetClientCache.clear(); // 跨世界/存档不残留上一市政厅目标
        // 建筑区域缓存在服务器重新同步前不清空，会带着上一世界（存档）的建筑边界框进入
        // 下一世界，导致新存档首次建建筑时误报“与旧存档建筑重叠”。
        BuildingAreaSyncPacket.clear();
    }

    @SubscribeEvent
    static void onEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Wandscape.WANDSCAPE_NPC.get(), WandscapeNpcRenderer::new);
        // 敌对测试法师复用同款渲染器（模型/纹理/帽子/名牌），外观与 NPC 法师一致
        event.registerEntityRenderer(Wandscape.EVIL_MAGE.get(), WandscapeNpcRenderer::new);
        event.registerEntityRenderer(Wandscape.TOURIST.get(), TouristRenderer::new);
        event.registerEntityRenderer(Wandscape.TRANSPORT_ITEM.get(), com.wsteam.wandscape.content.warehouse.transport.client.TransportItemEntityRenderer::new);
        event.registerEntityRenderer(Wandscape.MAGIC_BEAM.get(), MagicBeamEntityRenderer::new);
        event.registerBlockEntityRenderer(Wandscape.CREATIVE_BUILDING_SCANNER_BE.get(), ScannerRenderer::new);
        event.registerBlockEntityRenderer(Wandscape.BUILDING_SCANNER_BE.get(), ScannerRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(WandscapeNpcRenderer.WIZARD_HAT_LAYER, WizardHatModel::createLayer);
    }

    @SubscribeEvent
    static void onItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> {
            if (tintIndex == 0) {
                CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
                if (customData != null && customData.contains("wand_color")) {
                    String color = customData.copyTag().getString("wand_color");
                    if (!color.isEmpty() && color.length() == 7 && color.charAt(0) == '#') {
                        try {
                            return 0xFF000000 | Integer.parseInt(color.substring(1), 16);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            return 0xFFFFFFFF;
        }, Wandscape.WAND.get());
        // 玩家权杖：4 件共用模板 + 固定主题色（ItemColors tintindex 0 染头部）
        event.register((stack, tintIndex) -> tintIndex == 0
                        ? ScepterKind.PEACE.themeColor() : 0xFFFFFFFF,
                Wandscape.PEACE_WAND.get());
        event.register((stack, tintIndex) -> tintIndex == 0
                        ? ScepterKind.FOLLOW.themeColor() : 0xFFFFFFFF,
                Wandscape.FOLLOW_WAND.get());
        event.register((stack, tintIndex) -> tintIndex == 0
                        ? ScepterKind.SHELTER.themeColor() : 0xFFFFFFFF,
                Wandscape.SHELTER_WAND.get());
        event.register((stack, tintIndex) -> tintIndex == 0
                        ? ScepterKind.HOSTILE.themeColor() : 0xFFFFFFFF,
                Wandscape.HOSTILE_WAND.get());
        // 万能权杖：模式染头（tintindex 0 读物品 NBT 模式的 themeColor，色随模式变）
        event.register((stack, tintIndex) -> tintIndex == 0
                        ? OmniScepterItem.getMode(stack).themeColor() : 0xFFFFFFFF,
                Wandscape.OMNI_SCEPTER.get());
    }

    @SubscribeEvent
    static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(Wandscape.CAST_BOLT.get(), CastBoltParticle.Provider::new);
        event.registerSpriteSet(Wandscape.MAGIC_GLOW.get(), MagicCircleDotParticle.Provider::new);
    }

    @SubscribeEvent
    static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(Wandscape.DATA_LOADER);
        // A datapack reload recreates every BuildingConfig instance, so the GPU
        // buffers keyed by the old instances are stale — close them so they get
        // re-baked (not leaked). Runs after DATA_LOADER so new configs are ready.
        event.registerReloadListener(new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resourceManager,
                                                  ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler,
                                                  Executor backgroundExecutor, Executor gameExecutor) {
                return barrier.wait(CompletableFuture.completedFuture(null))
                        .thenRun(BuildingGhostVboCache::closeAll)
                        .thenRun(BuildingPreviewGifCache::closeAll)
                        .thenRun(BuildingPreviewGifCache::warmAll);
            }
        });
    }
}
