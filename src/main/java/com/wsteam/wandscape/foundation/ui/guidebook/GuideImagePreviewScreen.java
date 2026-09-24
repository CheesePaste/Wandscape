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
 * 遵循「1:1 原图直出为主、超出窗口自适应缩放、绝不盲目拉伸模糊、绝不溢出裁切」原则。
 */
public class GuideImagePreviewScreen extends Screen {

    private static final Map<ResourceLocation, int[]> DIMENSION_CACHE = new ConcurrentHashMap<>();

    private final Screen parentScreen;
    private final ResourceLocation thumbnailLocation;
    private final ResourceLocation fullLocation;
    private final int textureWidth;
    private final int textureHeight;
    private boolean loggedRender = false;

    public GuideImagePreviewScreen(Screen parentScreen, ResourceLocation thumbnailLocation) {
        super(Component.translatable("gui.wandscape.guidebook.title"));
        this.parentScreen = parentScreen;
        this.thumbnailLocation = thumbnailLocation;
        this.fullLocation = resolveFullLocation(thumbnailLocation);
        int[] dims = resolveDimensions(this.fullLocation);
        this.textureWidth = dims[0];
        this.textureHeight = dims[1];

        String msg = String.format("[GUIDE_DEBUG] [ScreenInit] 原图=%s -> 高清图=%s, 解析尺寸=%dx%d",
                thumbnailLocation, this.fullLocation, this.textureWidth, this.textureHeight);
        System.out.println(msg);
        Log.info(LogCategory.UI, msg);
    }

    private static ResourceLocation resolveFullLocation(ResourceLocation loc) {
        if (loc == null) {
            return null;
        }
        String path = loc.getPath();
        if (path.startsWith("textures/guidebook/") && !path.startsWith("textures/guidebook/full/")) {
            String fullPath = path.replace("textures/guidebook/", "textures/guidebook/full/");
            ResourceLocation full = ResourceLocation.fromNamespaceAndPath(loc.getNamespace(), fullPath);
            System.out.println("[GUIDE_DEBUG] [PathResolve] " + loc + " -> " + full);
            return full;
        }
        System.out.println("[GUIDE_DEBUG] [PathResolve] 保持原路径: " + loc);
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
                            String msg = String.format("[GUIDE_DEBUG] [DimProbe:NativeImage] %s -> 成功读取尺寸: %dx%d", key, w, h);
                            System.out.println(msg);
                            Log.info(LogCategory.UI, msg);
                            return new int[]{w, h};
                        }
                    }
                } else {
                    System.out.println("[GUIDE_DEBUG] [DimProbe:NativeImage] ResourceManager 中未找到资源: " + key);
                }
            } catch (Exception e) {
                System.out.println("[GUIDE_DEBUG] [DimProbe:NativeImage] 读取异常: " + key + " - " + e.getMessage());
            }

            // 2. 兜底：直接向 GPU 绑定的纹理对象查询其实际尺寸
            try {
                var tex = Minecraft.getInstance().getTextureManager().getTexture(key);
                tex.bind();
                int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (w > 0 && h > 0) {
                    String msg = String.format("[GUIDE_DEBUG] [DimProbe:OpenGL] %s (TextureId=%d) -> GPU尺寸: %dx%d", key, tex.getId(), w, h);
                    System.out.println(msg);
                    Log.info(LogCategory.UI, msg);
                    return new int[]{w, h};
                }
            } catch (Exception e) {
                System.out.println("[GUIDE_DEBUG] [DimProbe:OpenGL] 查询异常: " + key + " - " + e.getMessage());
            }

            System.out.println("[GUIDE_DEBUG] [DimProbe:Fallback] 探测失败，回退默认 256x256: " + key);
            return new int[]{256, 256};
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // 1. 半透明暗色背景遮罩
        graphics.fill(0, 0, this.width, this.height, 0xD8080808);

        if (fullLocation != null) {
            var window = Minecraft.getInstance().getWindow();
            double guiScale = window.getGuiScale();
            if (guiScale <= 0) {
                guiScale = 1.0;
            }

            // 图像在当前 GUI Scale 下的 1:1 逻辑尺寸（以此尺寸绘制时，屏幕物理像素刚好等于原图物理像素）
            float naturalW = (float) this.textureWidth / (float) guiScale;
            float naturalH = (float) this.textureHeight / (float) guiScale;

            // 窗口可用最大安全区域（预留边距，绝不溢出窗口裁切）
            float maxW = Math.max(32, this.width - 32);
            float maxH = Math.max(32, this.height - 40);

            // 缩放比例计算：默认 1.0（1:1 物理像素原图直出，不拉伸模糊）；仅在原图超出窗口时才等比缩紧适应
            float scale = 1.0f;
            if (naturalW > maxW || naturalH > maxH) {
                scale = Math.min(maxW / naturalW, maxH / naturalH);
            }

            int drawW = Math.max(1, Math.round(naturalW * scale));
            int drawH = Math.max(1, Math.round(naturalH * scale));
            int drawX = (this.width - drawW) / 2;
            int drawY = (this.height - drawH - 12) / 2 + 2;

            if (!loggedRender) {
                loggedRender = true;
                try {
                    var tex = Minecraft.getInstance().getTextureManager().getTexture(fullLocation);
                    tex.bind();
                    int gpuW = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                    int gpuH = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                    int minFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
                    int magFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
                    String filterStr = String.format("MIN=%s, MAG=%s",
                            minFilter == 9728 ? "NEAREST" : minFilter == 9729 ? "LINEAR" : String.valueOf(minFilter),
                            magFilter == 9728 ? "NEAREST" : magFilter == 9729 ? "LINEAR" : String.valueOf(magFilter));

                    String renderLog = String.format(
                            "[GUIDE_DEBUG] [RenderFrame] 窗口物理=%dx%d, GUI分辨率=%dx%d (Screen: %dx%d), guiScale=%.1f\n" +
                            "[GUIDE_DEBUG] [RenderFrame] 原图尺寸=%dx%d, GPU纹理尺寸=%dx%d, 纹理过滤=[%s]\n" +
                            "[GUIDE_DEBUG] [RenderFrame] 1:1逻辑尺寸=%.1fx%.1f, 安全区=%.1fx%.1f, scale=%.3f, 最终绘制: pos=(%d, %d), size=(%dx%d)",
                            window.getWidth(), window.getHeight(), window.getGuiScaledWidth(), window.getGuiScaledHeight(), this.width, this.height, guiScale,
                            this.textureWidth, this.textureHeight, gpuW, gpuH, filterStr,
                            naturalW, naturalH, maxW, maxH, scale, drawX, drawY, drawW, drawH);
                    System.out.println(renderLog);
                    Log.info(LogCategory.UI, renderLog);
                } catch (Exception e) {
                    System.out.println("[GUIDE_DEBUG] [RenderFrame] 获取渲染状态异常: " + e.getMessage());
                }
            }

            // 2. 装饰边框与半透明阴影
            graphics.fill(drawX - 3, drawY - 3, drawX + drawW + 3, drawY + drawH + 3, 0x88000000);
            graphics.renderOutline(drawX - 1, drawY - 1, drawW + 2, drawH + 2, 0xFFC89B3C);

            // 3. 在标准 GUI 坐标系绘制图像，贴合窗口尺寸，无矩阵变换冲突
            graphics.blit(fullLocation, drawX, drawY, drawW, drawH, 0.0f, 0.0f, textureWidth, textureHeight, textureWidth, textureHeight);

            // 4. 屏幕左上角浮层调试信息（截屏即可直接查看参数）
            String debugLine1 = String.format("[GUIDE_DEBUG] 原图:%dx%d | 绘制:%dx%d | 窗口物理:%dx%d | GUI缩放:%.1f",
                    textureWidth, textureHeight, drawW, drawH, window.getWidth(), window.getHeight(), guiScale);
            String debugLine2 = String.format("[GUIDE_DEBUG] 资源: %s", fullLocation);
            graphics.drawString(font, debugLine1, 6, 6, 0xFFFFCC00, true);
            graphics.drawString(font, debugLine2, 6, 18, 0xFFFFCC00, true);
        }

        // 5. 底部居中操作提示文本
        Component hint = Component.translatable("gui.wandscape.guidebook.preview_close_hint");
        graphics.drawCenteredString(font, hint, this.width / 2, this.height - 16, 0xFFCCCCCC);

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
