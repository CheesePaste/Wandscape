package com.wsteam.wandscape;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.wsteam.wandscape.npc.client.CastBoltParticle;
import com.wsteam.wandscape.npc.client.WandscapeNpcRenderer;
import com.wsteam.wandscape.npc.client.WizardHatModel;
import com.wsteam.wandscape.road.client.RoadEditorRenderer;
import com.wsteam.wandscape.road.client.RoadEditorClientState;
import com.wsteam.wandscape.road.network.RoadEditorTogglePacket;
import com.wsteam.wandscape.projection.client.ProjectionRenderer;
import com.wsteam.wandscape.projection.client.ProjectionFlightController;
import com.wsteam.wandscape.projection.client.ProjectionClientState;
import com.wsteam.wandscape.projection.client.BuildingDebugClientState;
import com.wsteam.wandscape.projection.client.BuildingDebugController;
import com.wsteam.wandscape.projection.network.ProjectionEnterPacket;
import com.wsteam.wandscape.projection.network.ProjectionExitPacket;
import com.wsteam.wandscape.shared.ui.component.DemoScreen;
import com.wsteam.wandscape.shared.ui.editor.UIEditorScreen;
import com.wsteam.wandscape.production.client.CraftingStationScreen;
import com.wsteam.wandscape.production.client.WorkstationScreen;
import com.wsteam.wandscape.production.network.CraftingStationPacket;
import com.wsteam.wandscape.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.warehouse.client.WarehouseScreen;
import com.wsteam.wandscape.shared.ui.task.TaskEditorScreen;
import com.wsteam.wandscape.task.network.TaskEditorOpenPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Wandscape.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Wandscape.MODID, value = Dist.CLIENT)
public class WandscapeClient {

    public static final KeyMapping OPEN_DEMO_SCREEN = new KeyMapping(
            "key.wandscape.demo",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.wandscape"
    );

    public static final KeyMapping OPEN_UI_EDITOR = new KeyMapping(
            "key.wandscape.ui_editor",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U,
            "key.categories.wandscape"
    );

    public static final KeyMapping OPEN_TASK_EDITOR = new KeyMapping(
            "key.wandscape.task_editor",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_T,
            "key.categories.wandscape"
    );

    public static final KeyMapping PROJECTION_TOGGLE = new KeyMapping(
            "key.wandscape.projection",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.wandscape"
    );

    public static final KeyMapping DEBUG_TOGGLE = new KeyMapping(
            "key.wandscape.debug",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.wandscape"
    );

    public WandscapeClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, this::onClientTick);
        RoadEditorRenderer.register();
        ProjectionRenderer.register();
        ProjectionFlightController.register();
        BuildingDebugController.register();
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
        TaskQueueDataPacket.setClientHandler(packet -> {
            var screen = Minecraft.getInstance().screen;
            if (screen instanceof WorkstationScreen ws) {
                ws.updateQueueData(packet);
            } else if (screen instanceof CraftingStationScreen cs) {
                cs.updateQueueData(packet);
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
        Wandscape.LOGGER.info("Wandscape client setup complete");
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_DEMO_SCREEN);
        event.register(OPEN_UI_EDITOR);
        event.register(OPEN_TASK_EDITOR);
        event.register(PROJECTION_TOGGLE);
        event.register(DEBUG_TOGGLE);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        while (OPEN_DEMO_SCREEN.consumeClick()) {
            Minecraft.getInstance().setScreen(new DemoScreen());
        }
        while (OPEN_UI_EDITOR.consumeClick()) {
            Minecraft.getInstance().setScreen(new UIEditorScreen());
        }
        while (OPEN_TASK_EDITOR.consumeClick()) {
            PacketDistributor.sendToServer(new TaskEditorOpenPacket());
            Minecraft.getInstance().setScreen(new TaskEditorScreen());
        }
        while (PROJECTION_TOGGLE.consumeClick()) {
            // Cycle: Normal → Projection → Road Editor → Normal
            if (RoadEditorClientState.isEditing()) {
                // Road Editor → Normal
                PacketDistributor.sendToServer(new RoadEditorTogglePacket());
            } else if (ProjectionClientState.isProjecting()) {
                // Projection → Road Editor
                ProjectionClientState.exitProjection();
                PacketDistributor.sendToServer(new ProjectionExitPacket());
                PacketDistributor.sendToServer(new RoadEditorTogglePacket());
            } else {
                // Normal → Projection
                PacketDistributor.sendToServer(new ProjectionEnterPacket());
            }
        }
        while (DEBUG_TOGGLE.consumeClick()) {
            BuildingDebugController.toggle();
        }
    }


    @SubscribeEvent
    static void onEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Wandscape.WANDSCAPE_NPC.get(), WandscapeNpcRenderer::new);
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
    }
}
