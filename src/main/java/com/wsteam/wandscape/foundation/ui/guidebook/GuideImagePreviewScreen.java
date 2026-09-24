package com.wsteam.wandscape.foundation.ui.guidebook;

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
 * 供指南书（帕秋莉手册或内置 Markdown 阅读器）在点击图片时放大查看面板细节，按 ESC 或点击任意位置返回。
 */
public class GuideImagePreviewScreen extends Screen {

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
    }

    private static ResourceLocation resolveFullLocation(ResourceLocation loc) {
        if (loc == null) {
            return null;
        }
        String path = loc.getPath();
        if (path.startsWith("textures/guidebook/") && !path.startsWith("textures/guidebook/full/")) {
            String fullPath = path.replace("textures/guidebook/", "textures/guidebook/full/");
            ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(loc.getNamespace(), fullPath);
            try {
                if (Minecraft.getInstance().getResourceManager().getResource(candidate).isPresent()) {
                    return candidate;
                }
            } catch (Exception ignored) {}
        }
        return loc;
    }

    private static int[] resolveDimensions(ResourceLocation loc) {
        if (loc == null) {
            return new int[]{256, 256};
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
            // 2. 计算保持原始宽高比的适应屏幕尺寸
            int maxW = Math.max(100, this.width - 32);
            int maxH = Math.max(100, this.height - 48);
            float aspect = (float) this.textureWidth / (float) this.textureHeight;

            int drawW = maxW;
            int drawH = Math.round(drawW / aspect);
            if (drawH > maxH) {
                drawH = maxH;
                drawW = Math.round(drawH * aspect);
            }
            int drawX = (this.width - drawW) / 2;
            int drawY = (this.height - drawH - 14) / 2 + 4;

            // 3. 装饰边框与阴影
            graphics.fill(drawX - 3, drawY - 3, drawX + drawW + 3, drawY + drawH + 3, 0x88000000);
            graphics.renderOutline(drawX - 1, drawY - 1, drawW + 2, drawH + 2, 0xFFC89B3C);

            // 4. 绘制全分辨率图像
            graphics.blit(fullLocation, drawX, drawY, drawW, drawH, 0.0f, 0.0f, textureWidth, textureHeight, textureWidth, textureHeight);
        }

        // 5. 底部提示文本
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
