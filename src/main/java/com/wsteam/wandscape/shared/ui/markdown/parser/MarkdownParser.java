package com.wsteam.wandscape.shared.ui.markdown.parser;

import com.wsteam.wandscape.shared.ui.markdown.ast.*;
import com.wsteam.wandscape.shared.ui.markdown.ast.MarkdownNode.FormattedSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, zero-dependency Markdown parser designed for Minecraft Wandscape UI.
 */
public final class MarkdownParser {

    private static final Pattern IMAGE_PATTERN = Pattern.compile("^!\\[(.*?)\\]\\((.*?)(?:\\s+=(\\d+)x(\\d+))?\\)$");
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^(\\d+)[.)]\\s+(.*)$");

    private MarkdownParser() {}

    /**
     * Parse raw markdown string into a list of AST nodes.
     *
     * @param markdown Raw text content
     * @return List of parsed AST nodes
     */
    public static List<MarkdownNode> parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        List<MarkdownNode> nodes = new ArrayList<>();
        String[] lines = markdown.replace("\r\n", "\n").split("\n");

        List<String> quoteBuffer = new ArrayList<>();
        List<String> listBuffer = new ArrayList<>();
        List<String> tableBuffer = new ArrayList<>();
        boolean currentListOrdered = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Check table accumulation
            if (isTableLine(trimmed)) {
                flushListBuffer(nodes, listBuffer, currentListOrdered);
                flushQuoteBuffer(nodes, quoteBuffer);
                tableBuffer.add(trimmed);
                continue;
            } else if (!tableBuffer.isEmpty()) {
                flushTableBuffer(nodes, tableBuffer);
            }

            // Check quote block accumulation
            if (trimmed.startsWith(">")) {
                flushListBuffer(nodes, listBuffer, currentListOrdered);
                quoteBuffer.add(trimmed.substring(1).trim());
                continue;
            } else if (!quoteBuffer.isEmpty()) {
                flushQuoteBuffer(nodes, quoteBuffer);
            }

            // Check list item accumulation
            if (isListItem(trimmed)) {
                boolean ordered = isOrderedListItem(trimmed);
                if (!listBuffer.isEmpty() && currentListOrdered != ordered) {
                    flushListBuffer(nodes, listBuffer, currentListOrdered);
                }
                currentListOrdered = ordered;
                listBuffer.add(stripListMarker(trimmed));
                continue;
            } else if (!listBuffer.isEmpty()) {
                flushListBuffer(nodes, listBuffer, currentListOrdered);
            }

            // Empty line -> section separator
            if (trimmed.isEmpty()) {
                continue;
            }

            // Horizontal rule / Divider (---, ***, ___)
            if (isDivider(trimmed)) {
                nodes.add(new DividerNode());
                continue;
            }

            // Headers (# H1, ## H2, ### H3, ...)
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                    level++;
                }
                if (level < trimmed.length() && trimmed.charAt(level) == ' ') {
                    String title = trimmed.substring(level + 1).trim();
                    nodes.add(new HeaderNode(Math.min(3, Math.max(1, level)), title));
                    continue;
                }
            }

            // Standalone image
            Matcher imgMatcher = IMAGE_PATTERN.matcher(trimmed);
            if (imgMatcher.matches()) {
                String alt = imgMatcher.group(1);
                String loc = imgMatcher.group(2);
                int w = imgMatcher.group(3) != null ? Integer.parseInt(imgMatcher.group(3)) : 0;
                int h = imgMatcher.group(4) != null ? Integer.parseInt(imgMatcher.group(4)) : 0;
                nodes.add(new ImageNode(alt, loc, w, h));
                continue;
            }

            // Standard text paragraph
            List<FormattedSpan> spans = parseInlineSpans(trimmed);
            if (!spans.isEmpty()) {
                nodes.add(new TextParagraphNode(spans));
            }
        }

        // Flush remaining buffers at EOF
        flushQuoteBuffer(nodes, quoteBuffer);
        flushListBuffer(nodes, listBuffer, currentListOrdered);
        flushTableBuffer(nodes, tableBuffer);

        return nodes;
    }

    private static boolean isDivider(String line) {
        if (line.length() < 3) return false;
        return line.equals("---") || line.equals("***") || line.equals("___")
                || line.matches("^-{3,}$") || line.matches("^\\*{3,}$") || line.matches("^_{3,}$");
    }

    private static boolean isTableLine(String trimmed) {
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2;
    }

    private static void flushTableBuffer(List<MarkdownNode> nodes, List<String> tableBuffer) {
        if (tableBuffer.isEmpty()) return;

        if (tableBuffer.size() >= 2) {
            String headerLine = tableBuffer.get(0);
            List<String> headers = parseTableRowCells(headerLine);

            List<List<String>> rows = new ArrayList<>();
            for (int i = 1; i < tableBuffer.size(); i++) {
                String line = tableBuffer.get(i);
                if (line.replaceAll("[|:\\-\\s]", "").isEmpty()) {
                    continue; // Skip separator line | :--- | :--- |
                }
                rows.add(parseTableRowCells(line));
            }
            if (!headers.isEmpty()) {
                nodes.add(new TableNode(headers, rows));
            }
        }
        tableBuffer.clear();
    }

    private static List<String> parseTableRowCells(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);

        String[] parts = trimmed.split("\\|");
        List<String> cells = new ArrayList<>();
        for (String p : parts) {
            cells.add(p.trim());
        }
        return cells;
    }

    private static boolean isListItem(String line) {
        return line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") || ORDERED_LIST_PATTERN.matcher(line).matches();
    }

    private static boolean isOrderedListItem(String line) {
        return ORDERED_LIST_PATTERN.matcher(line).matches();
    }

    private static String stripListMarker(String line) {
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
            return line.substring(2).trim();
        }
        Matcher m = ORDERED_LIST_PATTERN.matcher(line);
        if (m.matches()) {
            return m.group(2).trim();
        }
        return line;
    }

    private static void flushQuoteBuffer(List<MarkdownNode> nodes, List<String> quoteBuffer) {
        if (!quoteBuffer.isEmpty()) {
            String combined = String.join(" ", quoteBuffer);
            nodes.add(new QuoteBlockNode(List.of(new TextParagraphNode(parseInlineSpans(combined)))));
            quoteBuffer.clear();
        }
    }

    private static void flushListBuffer(List<MarkdownNode> nodes, List<String> listBuffer, boolean ordered) {
        if (!listBuffer.isEmpty()) {
            List<MarkdownNode> items = new ArrayList<>();
            for (String itemStr : listBuffer) {
                items.add(new TextParagraphNode(parseInlineSpans(itemStr)));
            }
            nodes.add(new ListNode(ordered, items));
            listBuffer.clear();
        }
    }

    /**
     * Parse inline formatted spans (bold, italic, strikethrough, code, links).
     */
    public static List<FormattedSpan> parseInlineSpans(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<FormattedSpan> spans = new ArrayList<>();
        int cursor = 0;
        int len = text.length();

        boolean bold = false;
        boolean italic = false;
        boolean strikethrough = false;
        boolean code = false;

        StringBuilder buf = new StringBuilder();

        while (cursor < len) {
            // 1. Inline Image tag ![alt](src) — skip inline so it doesn't break link parser
            if (cursor + 1 < len && text.charAt(cursor) == '!' && text.charAt(cursor + 1) == '[') {
                int closeBracket = findMatchingBracket(text, cursor + 1);
                if (closeBracket > cursor + 1 && closeBracket + 1 < len && text.charAt(closeBracket + 1) == '(') {
                    int closeParen = findMatchingParen(text, closeBracket + 1);
                    if (closeParen > closeBracket + 1) {
                        cursor = closeParen + 1;
                        continue;
                    }
                }
            }

            // 2. Action Link [text](action:id) or standard link [text](url)
            if (text.charAt(cursor) == '[' && !code) {
                int closeBracket = findMatchingBracket(text, cursor);
                if (closeBracket > cursor && closeBracket + 1 < len && text.charAt(closeBracket + 1) == '(') {
                    int closeParen = findMatchingParen(text, closeBracket + 1);
                    if (closeParen > closeBracket + 1) {
                        if (buf.length() > 0) {
                            spans.add(new FormattedSpan(buf.toString(), bold, italic, strikethrough, code, null, null));
                            buf.setLength(0);
                        }

                        String linkText = text.substring(cursor + 1, closeBracket);
                        String linkAction = text.substring(closeBracket + 2, closeParen).trim();

                        boolean linkBold = bold;
                        boolean linkItalic = italic;
                        boolean linkCode = false;
                        // Strip and apply inner formatting if link text is wrapped in **bold** or `code`
                        if (linkText.startsWith("**") && linkText.endsWith("**") && linkText.length() >= 4) {
                            linkBold = true;
                            linkText = linkText.substring(2, linkText.length() - 2);
                        } else if (linkText.startsWith("*") && linkText.endsWith("*") && linkText.length() >= 2) {
                            linkItalic = true;
                            linkText = linkText.substring(1, linkText.length() - 1);
                        } else if (linkText.startsWith("`") && linkText.endsWith("`") && linkText.length() >= 2) {
                            linkCode = true;
                            linkText = linkText.substring(1, linkText.length() - 1);
                        }

                        spans.add(new FormattedSpan(linkText, linkBold, linkItalic, strikethrough, linkCode, null, linkAction));
                        cursor = closeParen + 1;
                        continue;
                    }
                }
            }

            // 3. Bold **text**
            if (cursor + 1 < len && text.startsWith("**", cursor) && !code) {
                if (buf.length() > 0) {
                    spans.add(new FormattedSpan(buf.toString(), bold, italic, strikethrough, code, null, null));
                    buf.setLength(0);
                }
                bold = !bold;
                cursor += 2;
                continue;
            }

            // 4. Strikethrough ~~text~~
            if (cursor + 1 < len && text.startsWith("~~", cursor) && !code) {
                if (buf.length() > 0) {
                    spans.add(new FormattedSpan(buf.toString(), bold, italic, strikethrough, code, null, null));
                    buf.setLength(0);
                }
                strikethrough = !strikethrough;
                cursor += 2;
                continue;
            }

            // 5. Italic *text*
            if (text.charAt(cursor) == '*' && !code) {
                if (buf.length() > 0) {
                    spans.add(new FormattedSpan(buf.toString(), bold, italic, strikethrough, code, null, null));
                    buf.setLength(0);
                }
                italic = !italic;
                cursor += 1;
                continue;
            }

            // 6. Code `text`
            if (text.charAt(cursor) == '`') {
                if (buf.length() > 0) {
                    spans.add(new FormattedSpan(buf.toString(), bold, italic, strikethrough, code, null, null));
                    buf.setLength(0);
                }
                code = !code;
                cursor += 1;
                continue;
            }

            buf.append(text.charAt(cursor));
            cursor++;
        }

        if (buf.length() > 0) {
            spans.add(new FormattedSpan(buf.toString(), bold, italic, strikethrough, code, null, null));
        }

        return spans;
    }

    private static int findMatchingBracket(String text, int openPos) {
        int depth = 0;
        for (int i = openPos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findMatchingParen(String text, int openPos) {
        int depth = 0;
        for (int i = openPos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
