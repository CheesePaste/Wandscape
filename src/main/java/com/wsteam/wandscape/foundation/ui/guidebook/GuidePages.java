package com.wsteam.wandscape.foundation.ui.guidebook;

import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.markdown.navigation.DocumentLoader;

import java.util.List;

/**
 * 兜底手册的取页层：把页 id 变成能渲染的 md 正文。
 *
 * <p>三种页：
 * <ul>
 *   <li>着陆页（{@link GuideManifest#ROOT_PAGE}）——书名 + 着陆文案 + 八个分类与它们底下的条目；</li>
 *   <li>分类页（{@code category:<分类 id>}）——分类名 + 分类描述 + 该分类的条目（正文里的《建筑》点进这里）；</li>
 *   <li>条目页——{@code guidebook/<语言>/<条目 id>.md} 原文。</li>
 * </ul>
 *
 * <p>两件事以前做不到、现在补上：一是**结构**——兜底过去只有一摞 md，没有分类也没有目录，
 * 结构全靠 {@link GuideManifest}；二是**《…》**——手册正文里的书名号在帕秋莉那侧是链接，
 * 兜底过去只当纯文本画出来，这里在加载后统一改写成 md 链接。
 *
 * <p>与帕秋莉侧的分工：分类页在帕秋莉里没有对应 API（只能开条目），那边把
 * {@code category:<id>} 降级成该分类的第一条，两边因此指向同一处内容。
 */
public final class GuidePages {

    private GuidePages() {}

    /**
     * 归一化页名，返回可存进历史栈的规范页 id；清单缺失时原样返回（保持改造前的行为）。
     */
    public static String canonical(String rawId) {
        GuideManifest manifest = GuideManifest.forLocale(DocumentLoader.localeDir());
        if (manifest == null) {
            return (rawId == null || rawId.isBlank()) ? "index_guide" : rawId;
        }
        String resolved = manifest.resolve(rawId);
        // 认不出来就原样带着，让 404 页把玩家敲的那串显示出来
        return resolved != null ? resolved : rawId;
    }

    /** 取页正文。永不返回 null：未知页是一页 404 文本，由 {@link DocumentLoader} 给出。 */
    public static String load(String rawId) {
        GuideManifest manifest = GuideManifest.forLocale(DocumentLoader.localeDir());
        if (manifest == null) {
            // 清单缺失：退回改造前的行为（空串＝概览页），但仍然只放行像个页 id 的字符串
            String id = (rawId == null || rawId.isBlank()) ? "index_guide" : rawId.trim();
            return id.matches("[a-z0-9_]+")
                    ? DocumentLoader.loadMarkdown(id)
                    : DocumentLoader.notFoundPage(id);
        }
        String page = manifest.resolve(rawId);
        if (page == null) {
            // 认不出来就是 404：不拿这串去拼资源路径（/wandscape guide 的页名是玩家给的）
            return DocumentLoader.notFoundPage(String.valueOf(rawId));
        }
        if (GuideManifest.ROOT_PAGE.equals(page)) {
            return rewriteTitles(landingPage(manifest), manifest);
        }
        if (page.startsWith(GuideManifest.CATEGORY_PREFIX)) {
            GuideManifest.Category category = manifest.category(
                    page.substring(GuideManifest.CATEGORY_PREFIX.length()));
            if (category == null) {
                return DocumentLoader.notFoundPage(String.valueOf(rawId));
            }
            return rewriteTitles(categoryPage(manifest, category), manifest);
        }
        return rewriteTitles(DocumentLoader.loadMarkdown(page), manifest);
    }

    /** 页面标题：取渲染出来的 md 的 H1；取不到返回 null（调用方退回固定标题）。 */
    @javax.annotation.Nullable
    public static String title(String markdown) {
        if (markdown == null) {
            return null;
        }
        for (String line : markdown.split("\n", 40)) {
            String s = line.strip();
            if (s.startsWith("# ")) {
                return s.substring(2).strip();
            }
            if (!s.isEmpty() && !s.startsWith("#")) {
                return null; // H1 之前就有正文，说明这不是一份带标题的页
            }
        }
        return null;
    }

    // ── 合成 ────────────────────────────────────────────────────────────────

    /**
     * 着陆页：书名 + 着陆文案，然后只列八个分类（分类名、描述、入口链接）。
     * 条目不下沉到这一页——六十多条铺一屏长得没法看，点进分类页再看。
     */
    private static String landingPage(GuideManifest manifest) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(manifest.landingTitle()).append("\n\n");
        if (!manifest.landingText().isBlank()) {
            sb.append(manifest.landingText().strip()).append("\n\n");
        }
        for (GuideManifest.Category category : manifest.categories()) {
            sb.append("## ").append(category.name()).append("\n\n");
            if (!category.desc().isBlank()) {
                sb.append(category.desc().strip()).append("\n\n");
            }
            sb.append("- [").append(entriesLinkLabel(manifest, category))
                    .append("](category:").append(category.id()).append(")\n\n");
        }
        return sb.toString();
    }

    /** 分类页：分类名 + 分类描述 + 条目链接 + 回目录。 */
    private static String categoryPage(GuideManifest manifest, GuideManifest.Category category) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(category.name()).append("\n\n");
        if (!category.desc().isBlank()) {
            sb.append(category.desc().strip()).append("\n\n");
        }
        List<GuideManifest.Entry> entries = manifest.entriesIn(category.id());
        for (GuideManifest.Entry entry : entries) {
            sb.append("- [").append(entry.name()).append("](").append(entry.doc()).append(".md)\n");
        }
        sb.append("\n- [").append(I18n.string("gui.wandscape.guidebook.back_to_index", "返回目录"))
                .append("](").append(GuideManifest.ROOT_PAGE).append(".md)\n");
        return sb.toString();
    }

    /** 分类入口的链接文字：带上条目数，省得点进去才发现这一屏有多长。 */
    private static String entriesLinkLabel(GuideManifest manifest, GuideManifest.Category category) {
        return I18n.name("gui.wandscape.guidebook.category_entries",
                "查看全部 %s 条", manifest.entriesIn(category.id()).size()).getString();
    }

    // ── 《…》 → 链接 ────────────────────────────────────────────────────────

    /**
     * 把正文里的《标题》改写成 md 链接，标题表里没有的保持原样（与帕秋莉侧一致：
     * 生成器同样只转它认识的标题）。行内代码与链接标签里的书名号不动，免得把它们的字面量拆开。
     */
    static String rewriteTitles(String markdown, GuideManifest manifest) {
        if (markdown == null || markdown.isEmpty() || !markdown.contains("《")) {
            return markdown;
        }
        StringBuilder out = new StringBuilder(markdown.length() + 64);
        boolean inCode = false;
        int labelDepth = 0;
        for (int i = 0; i < markdown.length(); i++) {
            char c = markdown.charAt(i);
            if (c == '`') {
                inCode = !inCode;
                out.append(c);
                continue;
            }
            if (!inCode) {
                if (c == '[') {
                    labelDepth++;
                } else if (c == ']' && labelDepth > 0) {
                    labelDepth--;
                }
            }
            if (c == '《' && !inCode && labelDepth == 0) {
                int end = markdown.indexOf('》', i + 1);
                int lineEnd = markdown.indexOf('\n', i + 1);
                if (end > i && (lineEnd < 0 || end < lineEnd) && end - i <= 40) {
                    String title = markdown.substring(i + 1, end);
                    String target = manifest.titleTarget(title.strip());
                    String link = linkTarget(manifest, target);
                    if (link != null) {
                        out.append('[').append(markdown, i, end + 1).append("](").append(link).append(')');
                        i = end;
                        continue;
                    }
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 标题表的目标 → 兜底认识的页名；目标是分类就写成 {@code category:<id>}。 */
    @javax.annotation.Nullable
    private static String linkTarget(GuideManifest manifest, @javax.annotation.Nullable String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        if (target.startsWith(GuideManifest.CATEGORY_PREFIX)) {
            return target;
        }
        if (manifest.category(target) != null) {
            return GuideManifest.CATEGORY_PREFIX + target;
        }
        return manifest.hasDoc(target) ? target + ".md" : null;
    }
}
