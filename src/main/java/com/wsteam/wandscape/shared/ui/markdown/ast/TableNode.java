package com.wsteam.wandscape.shared.ui.markdown.ast;

import java.util.List;

/**
 * Table AST node representing a Markdown table grid.
 */
public record TableNode(
        List<String> headers,
        List<List<String>> rows
) implements MarkdownNode {}
