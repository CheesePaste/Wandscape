package com.wsteam.wandscape.compat.patchouli;

import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import com.wsteam.wandscape.foundation.ui.guidebook.GuideManifest;
import net.minecraft.resources.ResourceLocation;
import vazkii.patchouli.api.PatchouliAPI;

/**
 * 帕秋莉手册客户端调用隔离实现。
 * 仅在 PatchouliCompat.isLoaded() 为真时由 JVM 解析装载。
 *
 * <p>页名 → 条目的映射不再手写：读 {@link GuideManifest}（{@code gen_patchouli.py} 生成的结构清单），
 * 与兜底阅读器同源，两套渲染因此认同一套页名。此前那份手写枚举已经漏过新加的条目，
 * 且与生成器的「先出现者胜出」规则相反。
 */
final class PatchouliCompatImpl {

    private PatchouliCompatImpl() {}

    static void initClient() {
        PatchouliBookRenderer.init();
    }

    /**
     * 打开手册。页名与兜底侧同一套：空串/null/{@code index} → 手册首页，
     * {@code category:<分类 id>} → 该分类的第一条（帕秋莉没有「打开分类」的 API），
     * 条目 id 与去词尾 {@code _guide} 的别名 → 该条目。认不出来就开首页。
     */
    static void openBook(String docPath) {
        if (PatchouliAPI.get().isStub()) {
            return;
        }
        GuideManifest manifest = GuideManifest.anyLocale();
        if (manifest == null) {
            PatchouliAPI.get().openBookGUI(PatchouliCompat.BOOK_ID);
            return;
        }

        String page = manifest.resolve(docPath);
        if (page == null) {
            // 未知页名：开首页并把名字记下来，免得玩家以为指令没反应
            Log.warnOnce(LogCategory.UI, "patchouli-unknown-page",
                    "[指南] 认不出页名 '{}'（条目 id / 别名 / category:<分类>），已改为打开手册首页",
                    docPath);
            PatchouliAPI.get().openBookGUI(PatchouliCompat.BOOK_ID);
            return;
        }

        ResourceLocation entryId = entryFor(manifest, page);
        if (entryId == null) {
            PatchouliAPI.get().openBookGUI(PatchouliCompat.BOOK_ID);
            return;
        }
        PatchouliAPI.get().openBookEntry(PatchouliCompat.BOOK_ID, entryId, 0);
    }

    /** 规范页 id → 帕秋莉条目 id；着陆页返回 null（开首页）。 */
    @javax.annotation.Nullable
    private static ResourceLocation entryFor(GuideManifest manifest, String page) {
        if (GuideManifest.ROOT_PAGE.equals(page)) {
            return null;
        }
        GuideManifest.Entry entry;
        if (page.startsWith(GuideManifest.CATEGORY_PREFIX)) {
            String category = page.substring(GuideManifest.CATEGORY_PREFIX.length());
            java.util.List<GuideManifest.Entry> inCategory = manifest.entriesIn(category);
            entry = inCategory.isEmpty() ? null : inCategory.get(0);
        } else {
            entry = manifest.firstEntryFor(page);
        }
        if (entry == null) {
            return null;
        }
        return ResourceLocation.fromNamespaceAndPath(
                "wandscape", entry.category() + "/" + entry.doc());
    }

    static boolean isBookOpen() {
        if (PatchouliAPI.get().isStub()) {
            return false;
        }
        ResourceLocation openGui = PatchouliAPI.get().getOpenBookGui();
        return PatchouliCompat.BOOK_ID.equals(openGui);
    }
}
