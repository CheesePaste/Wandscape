package com.wsteam.wandscape.foundation.ui.guidebook;

import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全屏高清图片预览弹窗（Lightbox 预览）。
 * 供指南书（帕秋莉手册或内置 Markdown 阅读器）在点击图片时以 1:1 物理像素原图查看，按 ESC 或点击任意位置返回。
 */
public class GuideImagePreviewScreen extends Screen {

    private static final Map<String, int[]> KNOWN_DIMENSIONS = Map.ofEntries(
            Map.entry("altar_panel.png", new int[]{634, 459}),
            Map.entry("bld_repair.png", new int[]{591, 459}),
            Map.entry("build_adjust.png", new int[]{808, 353}),
            Map.entry("casting_slots.png", new int[]{599, 439}),
            Map.entry("crafting_panel.png", new int[]{791, 421}),
            Map.entry("elem_overview_tab.png", new int[]{848, 56}),
            Map.entry("intro_03_placing.png", new int[]{921, 649}),
            Map.entry("intro_05_naming.png", new int[]{1093, 545}),
            Map.entry("intro_06_exchange_tab.png", new int[]{605, 339}),
            Map.entry("intro_07_craft_tab.png", new int[]{796, 431}),
            Map.entry("mage_hut_roster.png", new int[]{747, 483}),
            Map.entry("mage_panel.png", new int[]{601, 465}),
            Map.entry("node_panel.png", new int[]{789, 442}),
            Map.entry("road_tools.png", new int[]{759, 582}),
            Map.entry("settings_tabs.png", new int[]{809, 329}),
            Map.entry("shop_panel.png", new int[]{596, 452}),
            Map.entry("tasks_tabs.png", new int[]{821, 444}),
            Map.entry("tavern_panel.png", new int[]{668, 460}),
            Map.entry("tourist_detail.png", new int[]{600, 384}),
            Map.entry("tourist_three_values.png", new int[]{184, 42}),
            Map.entry("townhall_panel.png", new int[]{591, 459}),
            Map.entry("workstation_panel.png", new int[]{796, 431})
    );

    private static final Map<ResourceLocation, int[]> DIMENSION_CACHE = new ConcurrentHashMap<>();

    private final Screen parentScreen;
    private final ResourceLocation thumbnailLocation;
    private final ResourceLocation fullLocation;
    private final int textureWidth;
    private final int textureHeight;

    public GuideImagePreviewScreen(Screen parentScreen, ResourceLocation thumbnailLocation) {
        super(Component.translatable("gui.wandscape.guidebook.title"));
        this.parentScreen = parentScreen;
        this.thumbnailLocation = thumbnailLocation;
        this.fullLocation = resolveFullLocation(thumbnailLocation);
        int[] dims = resolveDimensions(this.fullLocation);
        this.textureWidth = dims[0];
        this.textureHeight = dims[1];

        Log.info(LogCategory.UI, "[指南预览] 开启大图预览: 原路径=%s -> 高清路径=%s, 尺寸=%dx%d",
                thumbnailLocation, this.fullLocation, this.textureWidth, this.textureHeight);
    }

    private static ResourceLocation resolveFullLocation(ResourceLocation loc) {
        if (loc == null) {
            return null;
        }
        String path = loc.getPath();
        if (path.startsWith("textures/guidebook/") && !path.startsWith("textures/guidebook/full/")) {
            String fullPath = path.replace("textures/guidebook/", "textures/guidebook/full/");
            return ResourceLocation.fromNamespaceAndPath(loc.getNamespace(), fullPath);
        }
        return loc;
    }

    private static int[] resolveDimensions(ResourceLocation loc) {
        if (loc == null) {
            return new int[]{256, 256};
        }
        String path = loc.getPath();
        String filename = path.substring(path.lastIndexOf('/') + 1);
        int[] known = KNOWN_DIMENSIONS.get(filename);
        if (known != null) {
            return known;
        }
        return DIMENSION_CACHE.computeIfAbsent(loc, key -> {
            try {
                var resOpt = Minecraft.getInstance().getResourceManager().getResource(key);
                if (resOpt.isPresent()) {
                    try (var is = resOpt.get().open()) {
                        byte[] header = is.readNBytes(24);
                        if (header.length >= 24
                                && (header[0] & 0xFF) == 0x89
                                && header[1] == 'P'
                                && header[2] == 'N'
                                && header[3] == 'G') {
                            int w = ((header[16] & 0xFF) << 24)
                                    | ((header[17] & 0xFF) << 16)
                                    | ((header[18] & 0xFF) << 8)
                                    | (header[19] & 0xFF);
                            int h = ((header[20] & 0xFF) << 24)
                                    | ((header[21] & 0xFF) << 16)
                                    | ((header[22] & 0xFF) << 8)
                                    | (header[23] & 0xFF);
                            if (w > 0 && h > 0) {
                                return new int[]{w, h};
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            return new int[]{256, 256};
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // 1. 半透明暗色背景遮罩
        graphics.fill(0, 0, this.width, this.height, 0xD8080808);

        if (fullLocation != null) {
            var window = Minecraft.getInstance().getWindow();
            int winW = window.getWidth();
            int winH = window.getHeight();
            double guiScale = window.getGuiScale();
            float invScale = (float) (1.0 / guiScale);

            // 物理像素居中位置
            int drawX = (winW - this.textureWidth) / 2;
            int drawY = (winH - this.textureHeight) / 2;

            // 切换到物理像素 1:1 空间渲染：完全不缩放、不拉伸、1 纹理像素 = 1 屏幕物理像素
            graphics.pose().pushPose();
            graphics.pose().scale(invScale, invScale, 1.0f);

            // 装饰边框与阴影（物理像素单位）
            graphics.fill(drawX - 4, drawY - 4, drawX + textureWidth + 4, drawY + textureHeight + 4, 0xAA000000);
            graphics.renderOutline(drawX - 1, drawY - 1, textureWidth + 2, textureHeight + 2, 0xFFC89B3C);

            // 1:1 绘制图像（无二次采样与插值失真）
            graphics.blit(fullLocation, drawX, drawY, 0.0f, 0.0f, textureWidth, textureHeight, textureWidth, textureHeight);

            graphics.pose().popPose();
        }

        // 5. 底部提示文本（普通 GUI 缩放坐标系）
        Component hint = Component.translatable("gui.wandscape.guidebook.preview_close_hint");
        graphics.drawCenteredString(font, hint, this.width / 2, this.height - 18, 0xFFCCCCCC);

        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        onClose();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parentScreen);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
