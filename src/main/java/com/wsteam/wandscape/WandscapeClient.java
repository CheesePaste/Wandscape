package com.wsteam.wandscape;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.wsteam.wandscape.npc.client.CastBoltParticle;
import com.wsteam.wandscape.npc.client.WandscapeNpcRenderer;
import com.wsteam.wandscape.npc.client.WizardHatModel;
import com.wsteam.wandscape.magic.client.MagicBeamEntityRenderer;
import com.wsteam.wandscape.magic.client.MagicCircleDotParticle;
import com.wsteam.wandscape.magic.client.MagicCircleEmitter;
import com.wsteam.wandscape.road.client.RoadPlacementController;
import com.wsteam.wandscape.road.client.RoadPlacementRenderer;
import com.wsteam.wandscape.projection.client.ProjectionRenderer;
import com.wsteam.wandscape.projection.client.ProjectionFlightController;
import com.wsteam.wandscape.projection.client.BuildingDebugController;
import com.wsteam.wandscape.projection.client.BuildingDebugOverlay;
import com.wsteam.wandscape.overview.client.OverviewFlightController;
import com.wsteam.wandscape.overview.client.OverviewRenderer;
import com.wsteam.wandscape.production.client.CraftingStationScreen;
import com.wsteam.wandscape.production.client.WorkstationScreen;
import com.wsteam.wandscape.production.network.CraftingStationPacket;
import com.wsteam.wandscape.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.building.client.HotelScreen;
import com.wsteam.wandscape.building.client.NodeScreen;
import com.wsteam.wandscape.building.client.ShopScreen;
import com.wsteam.wandscape.building.client.TavernScreen;
import com.wsteam.wandscape.building.client.AltarScreen;
import com.wsteam.wandscape.building.client.BuildingAreaRenderer;
import com.wsteam.wandscape.building.client.ConstructionGhostRenderer;
import com.wsteam.wandscape.building.scanner.client.ScannerRenderer;
import com.wsteam.wandscape.building.network.HotelOpenPacket;
import com.wsteam.wandscape.building.network.AltarOpenPacket;
import com.wsteam.wandscape.building.network.NodeDataPacket;
import com.wsteam.wandscape.building.network.ShopOpenPacket;
import com.wsteam.wandscape.building.network.TavernOpenPacket;
import com.wsteam.wandscape.building.network.TownHallOpenPacket;
import com.wsteam.wandscape.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.engine.sound.ColonyAmbientSystem;
import com.wsteam.wandscape.npc.client.NpcScreen;
import com.wsteam.wandscape.npc.client.NpcStrategyScreen;
import com.wsteam.wandscape.npc.network.NpcDataPacket;
import com.wsteam.wandscape.tourist.client.TouristScreen;
import com.wsteam.wandscape.tourist.network.TouristDataPacket;
import com.wsteam.wandscape.warehouse.client.WarehouseScreen;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;

import com.wsteam.wandscape.shared.ui.panel.WandscapePanelController;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelOverlay;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;
import com.wsteam.wandscape.tourist.client.TouristDebugRenderer;
import com.wsteam.wandscape.tourist.client.TouristRenderer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import com.wsteam.wandscape.shared.log.Log;

@Mod(value = Wandscape.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Wandscape.MODID, value = Dist.CLIENT)
public class WandscapeClient {

    public static final KeyMapping PROJECTION_TOGGLE = new KeyMapping(
            "key.wandscape.projection",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.wandscape"
    );

    public static final KeyMapping PANEL_CURSOR_TOGGLE = new KeyMapping(
            "key.wandscape.panel_cursor",
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

    public static final KeyMapping OVERVIEW_TOGGLE = new KeyMapping(
            "key.wandscape.overview",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.wandscape"
    );

    public WandscapeClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, this::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, WandscapeClient::onPlayerLoggingIn);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, WandscapeClient::onPlayerLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> MagicCircleEmitter.tick());
        RoadPlacementController.register();
        RoadPlacementRenderer.register();
        ProjectionRenderer.register();
        ProjectionFlightController.register();
        BuildingDebugController.register();
        BuildingDebugOverlay.register();
        TouristDebugRenderer.register();
        BuildingAreaRenderer.register();
        ConstructionGhostRenderer.register();

        // Wandscape Panel
        WandscapePanelController.register();
        WandscapePanelOverlay.register();
        com.wsteam.wandscape.shared.ui.util.WandscapeHighlightRenderer.register();

        // Replay mod compat: don't open UI screens during ReplayMod/ReforgedPlay playback
        com.wsteam.wandscape.shared.ui.ReplayScreenGuard.register();

        // Overview mode
        OverviewFlightController.register();
        OverviewRenderer.register();

        // Spline Road Editor (native overlay + world interaction)
        com.wsteam.wandscape.road.client.SplineEditorController.register();
        com.wsteam.wandscape.road.client.SplineEditorRenderer.register();
        com.wsteam.wandscape.road.client.SplineEditorOverlay.register();
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Wire server→client packet handlers — open MedievalScreen directly.
        WarehouseDataPacket.setClientHandler(packet -> {
            var screen = Minecraft.getInstance().screen;
            if (screen instanceof WarehouseScreen ws) {
                ws.updateItems(packet);
            } else {
                var ws = new WarehouseScreen();
                ws.updateItems(packet);
                Minecraft.getInstance().setScreen(ws);
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
        TaskQueueDataPacket.setClientHandler(packet -> {
            var screen = Minecraft.getInstance().screen;
            if (screen instanceof WorkstationScreen ws) {
                ws.updateQueueData(packet);
            } else if (screen instanceof CraftingStationScreen cs) {
                cs.updateQueueData(packet);
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
        TavernOpenPacket.setClientHandler(packet -> {
            Minecraft.getInstance().setScreen(
                    new TavernScreen(packet.buildingPos(), packet.colonyId(),
                            packet.recruitCount(), packet.mageResumes()));
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
        NpcDataPacket.setClientHandler(packet -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof NpcStrategyScreen strategyScreen) {
                strategyScreen.apply(packet);
            } else if (mc.screen instanceof NpcScreen existing) {
                existing.apply(packet);
            } else {
                mc.setScreen(new NpcScreen(packet));
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
        com.wsteam.wandscape.shared.network.ColonyAmbientPacket.setClientHandler(packet ->
                ColonyAmbientSystem.setState(packet.playing(), packet.day()));
        ShopOpenPacket.setClientHandler(packet -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof ShopScreen existing) {
                existing.updateFrom(packet.stock(), packet.maxStocks());
            } else {
                mc.setScreen(new ShopScreen(packet.buildingPos(), packet.colonyId(),
                        packet.buildingId(), packet.creator(), packet.stock(), packet.maxStocks()));
            }
        });

        // Town hall screen
        TownHallOpenPacket.setClientHandler(packet -> {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.wsteam.wandscape.building.client.TownHallScreen(
                            packet.buildingPos(), packet.colonyId(),
                            packet.colonyName(), packet.level(), packet.experience(),
                            packet.expToNext(), packet.founderName()));
        });

        // Colony create prompt: town hall right-clicked but no colony exists
        com.wsteam.wandscape.shared.network.ColonyCreatePromptPacket.setClientHandler(packet -> {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.wsteam.wandscape.building.client.TownHallCreateScreen(packet.townHallAnchor()));
        });

        // Guide test screen
        com.wsteam.wandscape.shared.network.GuideTestPacket.setClientHandler(packet -> {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(packet.markdownContent()));
        });

        // Guide book: right-click opens the tutorial home (index_guide), locale-resolved
        com.wsteam.wandscape.guidebook.network.GuideBookOpenPacket.setClientHandler(packet -> {
            String docPath = packet.docPath();
            String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader.loadMarkdown(docPath);
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(null, content, docPath));
        });

        // Guide progress seed — apply saved tutorial step/dismissal on panel open
        com.wsteam.wandscape.shared.network.GuideProgressSyncPacket.setClientHandler(packet ->
                com.wsteam.wandscape.shared.ui.guidance.GuideSession.applySync(
                        packet.stepIndex(), packet.dismissed()));

        Log.info("Wandscape", "Wandscape client setup complete");
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PROJECTION_TOGGLE);
        event.register(PANEL_CURSOR_TOGGLE);
        event.register(GUIDE_TOGGLE);
        event.register(PANEL_AREAS_TOGGLE);
        event.register(OVERVIEW_TOGGLE);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        ColonyAmbientSystem.tick();
        // When the building search box is focused, letter keys must type into it,
        // not trigger panel hotkeys — so swallow V/C/H clicks while it's focused.
        boolean searchFocused = WandscapePanelState.isBuildingBarSearchFocused();
        while (PROJECTION_TOGGLE.consumeClick()) {
            // V key: toggle Wandscape panel open/close
            if (searchFocused) continue;
            if (WandscapePanelState.isPanelOpen()) {
                WandscapePanelState.closePanel();
            } else {
                WandscapePanelState.openPanel();
            }
        }
        while (PANEL_CURSOR_TOGGLE.consumeClick()) {
            // C key: lift/release cursor within the panel
            if (searchFocused) continue;
            if (WandscapePanelState.isPanelOpen()) {
                WandscapePanelState.toggleCursor();
            }
        }
        while (GUIDE_TOGGLE.consumeClick()) {
            // H key: open guide — panel has its own handler; this covers non-panel contexts
            if (searchFocused) continue;
            if (!WandscapePanelState.isPanelOpen()) {
                openGuideIndex();
            }
        }
    }

    /** Opens the guide index page (callable from anywhere). */
    public static void openGuideIndex() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader.loadMarkdown("index_guide");
            mc.setScreen(new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(null, content, "index_guide"));
        }
    }

    /** Welcome message on world join — points new players at the V-key building panel. */
    private static void onPlayerLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        var player = event.getPlayer();
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.wandscape.town.welcome"), false);
        }
    }

    /** Reset client panel/UI state on disconnect so it doesn't leak into the next world. */
    private static void onPlayerLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WandscapePanelState.reset();
    }

    @SubscribeEvent
    static void onEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Wandscape.WANDSCAPE_NPC.get(), WandscapeNpcRenderer::new);
        // 敌对测试法师复用同款渲染器（模型/纹理/帽子/名牌），外观与 NPC 法师一致
        event.registerEntityRenderer(Wandscape.EVIL_MAGE.get(), WandscapeNpcRenderer::new);
        event.registerEntityRenderer(Wandscape.TOURIST.get(), TouristRenderer::new);
        event.registerEntityRenderer(Wandscape.TRANSPORT_ITEM.get(), com.wsteam.wandscape.client.renderer.TransportItemEntityRenderer::new);
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
    }

    @SubscribeEvent
    static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(Wandscape.CAST_BOLT.get(), CastBoltParticle.Provider::new);
        event.registerSpriteSet(Wandscape.MAGIC_GLOW.get(), MagicCircleDotParticle.Provider::new);
    }

    @SubscribeEvent
    static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(Wandscape.DATA_LOADER);
    }
}
