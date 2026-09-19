package com.wsteam.wandscape.foundation.ui.guidebook;

import com.wsteam.wandscape.compat.patchouli.PatchouliCompat;
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
     * <p>页名两种渲染都认同一套：条目 id、去掉词尾 {@code _guide} 的别名（{@code warehouse}）、
     * 分类页 {@code category:<分类 id>}，空串/null 是着陆页。分类在帕秋莉里没有对应 API，
     * 那边降级成该分类的第一条——两处落到同一篇内容上。
     *
     * @param parentScreen 兜底 Markdown 界面关闭时返回的上一级界面，可为 null
     * @param docPath      页名，可为 null（着陆页）
     */
    public static void open(Screen parentScreen, String docPath) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        if (PatchouliCompat.isLoaded()) {
            PatchouliCompat.openBook(docPath);
            return;
        }

        // 兜底只读 Markdown 手册：正文由界面自己按页名取，这里不做预读
        mc.setScreen(new GuidebookScreen(parentScreen, docPath));
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
     *
     * <p>帕秋莉那侧查不到「现在打开的是哪一条」（API 只给 {@code getOpenBookGui()}），
     * 于是只能回到「这本手册开着」——对唯一的使用者（样条编辑器的 H 开关）恰好是对的：
     * 开着就关掉。别拿这个判断去做「是不是这一页」的精细分支。
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
