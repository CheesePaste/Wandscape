package com.wsteam.wandscape.foundation.ui.markdown.ast;

import java.util.List;

/**
 * AST Node representing a text paragraph containing rich formatted spans.
 *
 * @param spans List of formatted inline spans
 */
public record TextParagraphNode(List<FormattedSpan> spans) implements MarkdownNode {
    public TextParagraphNode {
        spans = List.copyOf(spans);
    }
}
