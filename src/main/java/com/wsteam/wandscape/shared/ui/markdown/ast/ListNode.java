package com.wsteam.wandscape.shared.ui.markdown.ast;

import java.util.List;

/**
 * AST Node representing a list (- item or 1. item).
 *
 * @param ordered True if numbered list, false if bullet list
 * @param items   List item Markdown nodes
 */
public record ListNode(boolean ordered, List<MarkdownNode> items) implements MarkdownNode {
    public ListNode {
        items = List.copyOf(items);
    }
}
