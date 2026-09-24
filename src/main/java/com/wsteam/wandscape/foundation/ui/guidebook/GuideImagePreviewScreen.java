package com.wsteam.wandscape.foundation.ui.guidebook;

import com.mojang.blaze3d.platform.NativeImage;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全屏高清图片预览弹窗（Lightbox 预览）。
 * 供指南书（帕秋莉手册或内置 Markdown 阅读器）在点击图片时放大查看。
 * 依据窗口尺寸保持宽高比自适应铺展至最大舒适视野，按 ESC 或点击任意位置返回。
 */
public class GuideImagePreviewScreen extends Screen {

    private static final Map<ResourceLocation, int[]> DIMENSION_CACHE = new ConcurrentHashMap<>();

    private final Screen parentScreen;
    private final ResourceLocation fullLocation;
    private final int textureWidth;
    private final int textureHeight;

    public GuideImagePreviewScreen(Screen parentScreen, ResourceLocation thumbnailLocation) {
        super(Component.translatable("gui.wandscape.guidebook.title"));
        this.parentScreen = parentScreen;
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
            return ResourceLocation.fromNamespaceAndPath(loc.getNamespace(), fullPath);
        }
        return loc;
    }

    private static int[] resolveDimensions(ResourceLocation loc) {
        if (loc == null) {
            return new int[]{256, 256};
        }
        return DIMENSION_CACHE.computeIfAbsent(loc, key -> {
            // 1. 通过 ResourceManager + NativeImage 动态解析 PNG 原图物理宽高（无硬编码）
            try {
                var resOpt = Minecraft.getInstance().getResourceManager().getResource(key);
                if (resOpt.isPresent()) {
                    try (var is = resOpt.get().open();
                         NativeImage img = NativeImage.read(is)) {
                        int w = img.getWidth();
                        int h = img.getHeight();
                        if (w > 0 && h > 0) {
                            return new int[]{w, h};
                        }
                    }
                }
            } catch (Exception e) {
                Log.warn(LogCategory.UI, "高清预览尺寸探测失败(ResourceManager): {} - {}", key, e.getMessage());
            }

            // 2. 兜底：直接向 GPU 绑定的纹理对象查询其实际尺寸
            try {
                var tex = Minecraft.getInstance().getTextureManager().getTexture(key);
                tex.bind();
                int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (w > 0 && h > 0) {
                    return new int[]{w, h};
                }
            } catch (Exception e) {
                Log.warn(LogCategory.UI, "高清预览尺寸探测失败(OpenGL): {} - {}", key, e.getMessage());
            }

            Log.warn(LogCategory.UI, "高清预览尺寸探测均失败，按 256x256 显示: {}", key);
            return new int[]{256, 256};
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // 1. 半透明暗色背景遮罩
        // 注意：切勿在此调用 super.render()！因为 Screen.render() 默认会触发 renderBackground() -> processBlurEffect()，
        // 会在所有内容绘制完成后对当前 Framebuffer 执行一次全屏高斯模糊后处理滤镜，导致已绘制的图片彻底糊化！
        graphics.fill(0, 0, this.width, this.height, 0xD8080808);

        if (fullLocation != null) {
            // 确保纹理为 NEAREST 像素级采样，严禁双线性模糊
            try {
                var tex = Minecraft.getInstance().getTextureManager().getTexture(fullLocation);
                tex.setFilter(false, false);
            } catch (Exception ignored) {}

            // 依据当前窗口尺寸保持宽高比自适应缩放（预留舒适边距）
            int maxW = Math.max(64, this.width - 32);
            int maxH = Math.max(48, this.height - 44);
            float aspect = (float) this.textureWidth / (float) this.textureHeight;

            int drawW = maxW;
            int drawH = Math.round(drawW / aspect);
            if (drawH > maxH) {
                drawH = maxH;
                drawW = Math.round(drawH * aspect);
            }

            int drawX = (this.width - drawW) / 2;
            int drawY = (this.height - drawH - 12) / 2 + 2;

            // 2. 装饰边框与半透明阴影
            graphics.fill(drawX - 3, drawY - 3, drawX + drawW + 3, drawY + drawH + 3, 0x88000000);
            graphics.renderOutline(drawX - 1, drawY - 1, drawW + 2, drawH + 2, 0xFFC89B3C);

            // 3. 在标准 GUI 坐标系绘制图像，贴合窗口尺寸，无矩阵变换冲突
            graphics.blit(fullLocation, drawX, drawY, drawW, drawH, 0.0f, 0.0f, textureWidth, textureHeight, textureWidth, textureHeight);
        }

        // 4. 底部居中操作提示文本
        Component hint = Component.translatable("gui.wandscape.guidebook.preview_close_hint");
        graphics.drawCenteredString(font, hint, this.width / 2, this.height - 16, 0xFFCCCCCC);
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
