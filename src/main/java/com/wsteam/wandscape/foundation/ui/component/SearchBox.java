package com.wsteam.wandscape.foundation.ui.component;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Medieval-styled search box shared by all Wandscape screens.
 * Draws its own inset field; matching uses the localized name + raw id so
 * both Chinese and English queries work (the Workstation approach).
 */
public class SearchBox extends EditBox {

    public SearchBox(Font font, int x, int y, int width, Component hint) {
        super(font, x, y, width, font.lineHeight, hint);
        setBordered(false);
        setTextColor(MedievalColors.TEXT_WARM_WHITE);
        setTextColorUneditable(MedievalColors.TEXT_MUTED);
        setHint(hint);
        setCanLoseFocus(true);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        MedievalScreen.drawInsetField(g, getX() - 1, getY() - 2, getWidth() + 2, getHeight() + 4);
        super.renderWidget(g, mouseX, mouseY, partialTick);
    }

    // ── 搜索匹配工具（统一写法：忽略大小写，匹配本地化名 + raw id）──

    /** Normalize a query: null-safe, trimmed, lowercase. */
    public static String normalize(String query) {
        return (query == null ? "" : query.trim()).toLowerCase();
    }

    /** True if the query is empty or the searchable text contains it (case-insensitive). */
    public static boolean matches(String searchableText, String query) {
        String lower = normalize(query);
        return lower.isEmpty() || searchableText.toLowerCase().contains(lower);
    }

    /** Localized display name + raw id — makes both Chinese and English search work. */
    public static String itemSearchText(String itemId) {
        var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        String name = (registryItem != null && registryItem != Items.AIR)
                ? new ItemStack(registryItem).getHoverName().getString()
                : itemId;
        return name + " " + itemId;
    }

    /** Filter a list by the query using the given searchable-text extractor. */
    public static <T> List<T> filter(List<T> all, String query, Function<T, String> searchText) {
        String lower = normalize(query);
        if (lower.isEmpty()) return new ArrayList<>(all);
        return all.stream().filter(t -> searchText.apply(t).toLowerCase().contains(lower)).toList();
    }
}
