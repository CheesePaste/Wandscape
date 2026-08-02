package com.wsteam.wandscape.shared.ui.markdown.navigation;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Classpath Markdown document resource loader.
 */
public final class DocumentLoader {

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

        String path = location.trim();
        if (path.startsWith("guide:")) {
            path = path.substring(6).trim();
        }

        if (!path.startsWith("assets/")) {
            if (path.endsWith(".md")) {
                path = "assets/wandscape/guide/" + path;
            } else {
                path = "assets/wandscape/guide/" + path + ".md";
            }
        }

        try (InputStream is = DocumentLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}

        return "# 404 文档未找到\n\n无法读取指定文档: `" + location + "`";
    }
}
