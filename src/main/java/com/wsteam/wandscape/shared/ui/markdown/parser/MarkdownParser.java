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
    private static final Pattern INLINE_PATTERN = Pattern.compile(
            "(\\*\\*|\\*|~~|`|\\[)|(!\\[)"
    );

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
        boolean currentListOrdered = false;

        for (String line : lines) {
            String trimmed = line.trim();

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

            // Headers
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                    level++;
                }
                if (level <= 3 && level < trimmed.length() && trimmed.charAt(level) == ' ') {
                    String title = trimmed.substring(level + 1).trim();
                    nodes.add(new HeaderNode(level, title));
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

            // Fallback: Text Paragraph
            nodes.add(new TextParagraphNode(parseInlineSpans(line)));
        }

        // Flush remaining buffers
        if (!quoteBuffer.isEmpty()) {
            flushQuoteBuffer(nodes, quoteBuffer);
        }
        if (!listBuffer.isEmpty()) {
            flushListBuffer(nodes, listBuffer, currentListOrdered);
        }

        return nodes;
    }

    private static void flushQuoteBuffer(List<MarkdownNode> target, List<String> quoteBuffer) {
        if (quoteBuffer.isEmpty()) {
            return;
        }
        String combined = String.join("\n", quoteBuffer);
        quoteBuffer.clear();
        target.add(new QuoteBlockNode(parse(combined)));
    }

    private static void flushListBuffer(List<MarkdownNode> target, List<String> listBuffer, boolean ordered) {
        if (listBuffer.isEmpty()) {
            return;
        }
        List<MarkdownNode> items = new ArrayList<>();
        for (String itemStr : listBuffer) {
            items.add(new TextParagraphNode(parseInlineSpans(itemStr)));
        }
        listBuffer.clear();
        target.add(new ListNode(ordered, items));
    }

    private static boolean isListItem(String line) {
        return line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") || isOrderedListItem(line);
    }

    private static boolean isOrderedListItem(String line) {
        return line.matches("^\\d+\\.\\s+.*");
    }

    private static String stripListMarker(String line) {
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
            return line.substring(2).trim();
        }
        int dotIdx = line.indexOf(". ");
        if (dotIdx > 0 && dotIdx < 5) {
            return line.substring(dotIdx + 2).trim();
        }
        return line;
    }

    /**
     * Parse inline markdown formatting tokens inside a line.
     * Supports: **bold**, *italic*, ~~strike~~, `code`, [text](link)
     */
    public static List<FormattedSpan> parseInlineSpans(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<FormattedSpan> spans = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();

        boolean bold = false;
        boolean italic = false;
        boolean strike = false;
        boolean code = false;
        String currentLink = null;

        int i = 0;
        int len = text.length();

        while (i < len) {
            // Check link [text](action)
            if (!code && text.charAt(i) == '[' && i < len - 1) {
                int closingBracket = text.indexOf(']', i + 1);
                if (closingBracket > i && closingBracket < len - 1 && text.charAt(closingBracket + 1) == '(') {
                    int closingParen = text.indexOf(')', closingBracket + 2);
                    if (closingParen > closingBracket + 1) {
                        if (currentText.length() > 0) {
                            spans.add(new FormattedSpan(currentText.toString(), bold, italic, strike, code, null, currentLink));
                            currentText.setLength(0);
                        }
                        String linkText = text.substring(i + 1, closingBracket);
                        String linkAction = text.substring(closingBracket + 2, closingParen);
                        spans.add(new FormattedSpan(linkText, bold, italic, strike, code, null, linkAction));
                        i = closingParen + 1;
                        continue;
                    }
                }
            }

            // Check bold **
            if (!code && i + 1 < len && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                if (currentText.length() > 0) {
                    spans.add(new FormattedSpan(currentText.toString(), bold, italic, strike, code, null, currentLink));
                    currentText.setLength(0);
                }
                bold = !bold;
                i += 2;
                continue;
            }

            // Check italic *
            if (!code && text.charAt(i) == '*') {
                if (currentText.length() > 0) {
                    spans.add(new FormattedSpan(currentText.toString(), bold, italic, strike, code, null, currentLink));
                    currentText.setLength(0);
                }
                italic = !italic;
                i++;
                continue;
            }

            // Check strikethrough ~~
            if (!code && i + 1 < len && text.charAt(i) == '~' && text.charAt(i + 1) == '~') {
                if (currentText.length() > 0) {
                    spans.add(new FormattedSpan(currentText.toString(), bold, italic, strike, code, null, currentLink));
                    currentText.setLength(0);
                }
                strike = !strike;
                i += 2;
                continue;
            }

            // Check inline code `
            if (text.charAt(i) == '`') {
                if (currentText.length() > 0) {
                    spans.add(new FormattedSpan(currentText.toString(), bold, italic, strike, code, null, currentLink));
                    currentText.setLength(0);
                }
                code = !code;
                i++;
                continue;
            }

            // Append normal character
            currentText.append(text.charAt(i));
            i++;
        }

        if (currentText.length() > 0) {
            spans.add(new FormattedSpan(currentText.toString(), bold, italic, strike, code, null, currentLink));
        }

        return spans;
    }
}
