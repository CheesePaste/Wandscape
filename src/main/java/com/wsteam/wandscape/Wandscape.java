package com.wsteam.wandscape;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.element.internal.ElementApiImpl;
import com.wsteam.wandscape.element.internal.ElementMappingLoader;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.wand.internal.WandApiImpl;
import com.wsteam.wandscape.wand.internal.WandPresetLoader;
import com.wsteam.wandscape.wand.item.WandItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Wandscape.MODID)
public class Wandscape {
    public static final String MODID = "wandscape";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredItem<Item> WAND = ITEMS.register("wand",
        () -> new WandItem(new Item.Properties()));

    public static final WandscapeDataLoader DATA_LOADER = new WandscapeDataLoader();

    public static final WandPresetLoader WAND_PRESET_LOADER = new WandPresetLoader(DATA_LOADER);
    public static final ElementMappingLoader ELEMENT_MAPPING_LOADER = new ElementMappingLoader(DATA_LOADER);

    public static final WandApiImpl WAND_API = new WandApiImpl();
    public static final ElementApiImpl ELEMENT_API = new ElementApiImpl(ELEMENT_MAPPING_LOADER);

    @SuppressWarnings("unused")
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> WANDSCAPE_TAB = CREATIVE_MODE_TABS.register("wandscape_tab",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.wandscape"))
            .icon(() -> new ItemStack(WAND.get()))
            .displayItems((params, output) -> {
                output.accept(WAND.get());
            })
            .build()
    );

    public Wandscape(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        NeoForge.EVENT_BUS.register(this);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        WandscapeApis.setWandApi(WAND_API);
        WandscapeApis.setElementApi(ELEMENT_API);
        LOGGER.info("Wandscape common setup complete");
    }

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(DATA_LOADER);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Wandscape server starting");
    }
}
