package com.wsteam.wandscape.foundation.ui.markdown.navigation;

import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Classpath Markdown document resource loader.
 *
 * Guide documents live under {@code assets/wandscape/guidebook/<locale>/} so content follows the
 * client's language. Locale directory names: {@code en} for any en_* language, {@code zh_cn}
 * for zh_*. A missing localized file falls back to the default {@code zh_cn} directory.
 */
public final class DocumentLoader {

    private static final String GUIDE_ROOT = "assets/wandscape/guidebook/";
    private static final String DEFAULT_LOCALE = "zh_cn";

    private DocumentLoader() {}

    /**
     * Load Markdown content by path or ID from classpath resources.
     * Supports formats like:
     * - "assets/wandscape/guidebook/zh_cn/townhall_guide.md"
     * - "guide:assets/wandscape/guidebook/townhall_guide.md"
     * - "townhall_guide.md"
     * - "townhall_guide"
     *
     * <p>调用方不应把玩家输入的任意字符串直接递进来（{@code assets/} 开头的输入会被原样当作
     * 类路径读取）。手册那边先经 {@link com.wsteam.wandscape.foundation.ui.guidebook.GuideManifest}
     * 白名单校验。
     */
    public static String loadMarkdown(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }

        for (String path : resolveCandidates(location)) {
            try (InputStream is = DocumentLoader.class.getClassLoader().getResourceAsStream(path)) {
                if (is != null) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {}
        }

        return notFoundPage(location);
    }

    /**
     * 未知页的占位正文。把请求的那串原样显示出来——作者据此看得出是哪个页名没对上。
     * 手册的入口只应对**清单里认识的页 id** 调 {@link #loadMarkdown}；认不出来的走这里，
     * 免得玩家输入的字符串被当成类路径去读。
     */
    public static String notFoundPage(String location) {
        return I18n.name("gui.wandscape.doc.notfound",
                "# 404 文档未找到\n\n无法读取指定文档: `%s`", location).getString();
    }

    /** Resource paths to try for a guide location: current locale first, then the default locale. */
    private static List<String> resolveCandidates(String location) {
        String path = location.trim();
        if (path.startsWith("guidebook:")) {
            path = path.substring("guidebook:".length()).trim();
        }

        List<String> candidates = new ArrayList<>();
        if (!path.startsWith("assets/")) {
            String file = path.endsWith(".md") ? path : path + ".md";
            candidates.add(localized(localeDir(), file));
            candidates.add(localized(DEFAULT_LOCALE, file));
            return candidates;
        }

        if (path.startsWith(GUIDE_ROOT)) {
            String rest = path.substring(GUIDE_ROOT.length());
            int slash = rest.indexOf('/');
            String firstSeg = slash < 0 ? rest : rest.substring(0, slash);
            if (isLocaleDir(firstSeg)) {
                candidates.add(path);
            } else {
                candidates.add(localized(localeDir(), rest));
            }
            candidates.add(localized(DEFAULT_LOCALE, rest));
            return candidates;
        }

        candidates.add(path);
        return candidates;
    }

    private static String localized(String localeDir, String file) {
        return GUIDE_ROOT + localeDir + "/" + file;
    }

    private static boolean isLocaleDir(String segment) {
        return segment.equals("en") || segment.equals("zh_cn") || segment.equals(localeDir());
    }

    /**
     * Guide subdirectory for the current client language; falls back to the default content.
     * 手册的运行期清单按同一个目录名分语言，所以两处必须用同一份判断。
     */
    public static String localeDir() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getLanguageManager() != null) {
            String lang = mc.getLanguageManager().getSelected();
            if (lang != null) {
                String lower = lang.toLowerCase();
                if (lower.startsWith("en")) {
                    return "en";
                }
                if (lower.startsWith("zh")) {
                    return "zh_cn";
                }
                return "en";
            }
        }
        return DEFAULT_LOCALE;
    }
}
