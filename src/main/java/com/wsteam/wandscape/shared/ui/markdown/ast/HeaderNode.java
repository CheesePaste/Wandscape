package com.wsteam.wandscape.shared.ui.markdown.ast;

/**
 * AST Node representing a header (# H1, ## H2, ### H3).
 *
 * @param level Header level (1, 2, or 3)
 * @param text  Header text content
 */
public record HeaderNode(int level, String text) implements MarkdownNode {
    public HeaderNode {
        if (level < 1) level = 1;
        if (level > 3) level = 3;
    }
}
