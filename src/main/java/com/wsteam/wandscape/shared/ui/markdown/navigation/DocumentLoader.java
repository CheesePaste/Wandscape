package com.wsteam.wandscape.shared.ui.markdown.navigation;

import com.wsteam.wandscape.shared.ui.I18n;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Classpath Markdown document resource loader.
 *
 * Guide documents live under {@code assets/wandscape/guide/<locale>/} so content follows the
 * client's language. Locale directory names: {@code en} for any en_* language, {@code zh_cn}
 * for zh_*. A missing localized file falls back to the default {@code zh_cn} directory.
 */
public final class DocumentLoader {

    private static final String GUIDE_ROOT = "assets/wandscape/guide/";
    private static final String DEFAULT_LOCALE = "zh_cn";

    private DocumentLoader() {}

    /**
     * Load Markdown content by path or ID from classpath resources.
     * Supports formats like:
     * - "assets/wandscape/guide/test_guide.md"
     * - "guide:assets/wandscape/guide/townhall_guide.md"
     * - "townhall_guide.md"
     * - "townhall_guide"
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

        return I18n.name("gui.wandscape.doc.notfound",
                "# 404 文档未找到\n\n无法读取指定文档: `%s`", location).getString();
    }

    /** Resource paths to try for a guide location: current locale first, then the default locale. */
    private static List<String> resolveCandidates(String location) {
        String path = location.trim();
        if (path.startsWith("guide:")) {
            path = path.substring(6).trim();
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

    /** Guide subdirectory for the current client language; falls back to the default content. */
    private static String localeDir() {
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
