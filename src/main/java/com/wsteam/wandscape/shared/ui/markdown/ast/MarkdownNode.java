package com.wsteam.wandscape.shared.ui.markdown.ast;

import java.util.List;

/**
 * Sealed interface for all AST nodes in the Wandscape Markdown parser.
 */
public sealed interface MarkdownNode permits HeaderNode, TextParagraphNode, ImageNode, QuoteBlockNode, ListNode, TableNode, DividerNode {

    /**
     * Inline text span containing formatting information (bold, italic, strikethrough, code, color, action link).
     */
    record FormattedSpan(
            String text,
            boolean bold,
            boolean italic,
            boolean strikethrough,
            boolean code,
            Integer color,
            String linkAction
    ) {
        public FormattedSpan(String text) {
            this(text, false, false, false, false, null, null);
        }
    }
}
