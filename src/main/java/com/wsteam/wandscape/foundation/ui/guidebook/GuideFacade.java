package com.wsteam.wandscape.foundation.ui.guidebook;

import com.wsteam.wandscape.compat.patchouli.PatchouliCompat;
import com.wsteam.wandscape.foundation.ui.markdown.navigation.DocumentLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 指南系统统一门面（GuideFacade）。
 *
 * <p>负责将所有指南打开请求（快捷键 H、各界面帮助按钮、指南书物品右键、/wandscape guide 指令）
 * 统一路由到对应的渲染层：
 * <ul>
 *   <li>安装了 Patchouli 模组：路由至帕秋莉书本（wandscape:guide）。</li>
 *   <li>未安装 Patchouli 模组：降级至游戏内 Markdown 阅读器（{@link GuidebookScreen}）。</li>
 * </ul>
 */
public final class GuideFacade {

    private GuideFacade() {}

    /**
     * 打开指定指南文档。
     *
     * @param parentScreen 兜底 Markdown 界面关闭时返回的上一级界面，可为 null
     * @param docPath      文档路径或条目标识（如 "warehouse_guide"、"index_guide"）
     */
    public static void open(Screen parentScreen, String docPath) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        if (PatchouliCompat.isLoaded()) {
            PatchouliCompat.openBook(docPath);
            return;
        }

        // 兜底只读 Markdown 手册
        String safePath = (docPath != null && !docPath.isBlank()) ? docPath : "index_guide";
        String content = DocumentLoader.loadMarkdown(safePath);
        mc.setScreen(new GuidebookScreen(parentScreen, content, safePath));
    }

    /**
     * 打开指定指南文档（无上一级界面）。
     */
    public static void open(String docPath) {
        open(null, docPath);
    }

    /**
     * 检查当前是否正处于指南界面（兼容 Patchouli 与兜底 GuidebookScreen）。
     */
    public static boolean isGuideOpen(Minecraft mc) {
        if (mc == null) return false;
        if (mc.screen instanceof GuidebookScreen) {
            return true;
        }
        if (PatchouliCompat.isLoaded()) {
            return PatchouliCompat.isBookOpen();
        }
        return false;
    }

    /**
     * 检查当前是否正在展示特定文档的指南界面。
     */
    public static boolean isShowingDocument(Minecraft mc, String docPath) {
        if (mc == null) return false;
        if (mc.screen instanceof GuidebookScreen guide) {
            return guide.isShowingDocument(docPath);
        }
        if (PatchouliCompat.isLoaded()) {
            return PatchouliCompat.isBookOpen();
        }
        return false;
    }
}
