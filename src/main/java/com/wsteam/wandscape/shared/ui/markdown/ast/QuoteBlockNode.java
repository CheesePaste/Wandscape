package com.wsteam.wandscape.shared.ui.markdown.ast;

import java.util.List;

/**
 * AST Node representing a quote block (> text).
 *
 * @param children Inner Markdown nodes contained in the quote block
 */
public record QuoteBlockNode(List<MarkdownNode> children) implements MarkdownNode {
    public QuoteBlockNode {
        children = List.copyOf(children);
    }
}
