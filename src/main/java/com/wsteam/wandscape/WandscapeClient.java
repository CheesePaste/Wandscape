package com.wsteam.wandscape;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.wsteam.wandscape.npc.client.WandscapeNpcRenderer;
import com.wsteam.wandscape.shared.ui.component.DemoScreen;
import com.wsteam.wandscape.shared.ui.editor.UIEditorScreen;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
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

    public WandscapeClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // Register game-bus listeners on the NeoForge EVENT_BUS (not mod bus)
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, this::onClientTick);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        Wandscape.LOGGER.info("Wandscape client setup complete");
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_DEMO_SCREEN);
        event.register(OPEN_UI_EDITOR);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        while (OPEN_DEMO_SCREEN.consumeClick()) {
            Minecraft.getInstance().setScreen(new DemoScreen());
        }
        while (OPEN_UI_EDITOR.consumeClick()) {
            Minecraft.getInstance().setScreen(new UIEditorScreen());
        }
    }

    @SubscribeEvent
    static void onEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Wandscape.WANDSCAPE_NPC.get(), WandscapeNpcRenderer::new);
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
}
