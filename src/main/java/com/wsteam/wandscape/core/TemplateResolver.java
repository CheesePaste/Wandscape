package com.wsteam.wandscape.core;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code {{variable}}} placeholders in template strings.
 * Variables without a matching value are left as-is (no exception).
 */
public final class TemplateResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^}]+)}}");

    private TemplateResolver() {}

    /**
     * Resolve all {@code {{key}}} placeholders using the given variable map.
     * Unmatched placeholders stay unchanged for debugging.
     */
    public static String resolve(String template, Map<String, String> variables) {
        if (template == null || !template.contains("{{")) return template;

        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = variables.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolve all values in a map whose values contain {@code {{}}} placeholders.
     * Returns a new map with resolved strings (keys are unchanged).
     */
    public static Map<String, String> resolveMap(Map<String, String> templateMap, Map<String, String> variables) {
        if (templateMap == null || templateMap.isEmpty()) return Map.of();
        Map<String, String> result = new HashMap<>();
        for (var entry : templateMap.entrySet()) {
            result.put(entry.getKey(), resolve(entry.getValue(), variables));
        }
        return result;
    }
}
