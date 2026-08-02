package com.wsteam.wandscape.shared.ui.markdown;

import com.wsteam.wandscape.shared.ui.markdown.ast.*;
import com.wsteam.wandscape.shared.ui.markdown.ast.MarkdownNode.FormattedSpan;
import com.wsteam.wandscape.shared.ui.markdown.parser.MarkdownParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MarkdownParserTest {

    @Test
    void testHeaderParsing() {
        String md = "# Level 1\n## Level 2\n### Level 3";
        List<MarkdownNode> nodes = MarkdownParser.parse(md);

        assertEquals(3, nodes.size());
        assertInstanceOf(HeaderNode.class, nodes.get(0));
        assertEquals(1, ((HeaderNode) nodes.get(0)).level());
        assertEquals("Level 1", ((HeaderNode) nodes.get(0)).text());

        assertEquals(2, ((HeaderNode) nodes.get(1)).level());
        assertEquals(3, ((HeaderNode) nodes.get(2)).level());
    }

    @Test
    void testInlineFormatting() {
        String line = "Hello **bold** world *italic* and `code` with [Action Link](action:test)";
        List<FormattedSpan> spans = MarkdownParser.parseInlineSpans(line);

        assertNotNull(spans);
        assertFalse(spans.isEmpty());

        // Check bold span
        boolean foundBold = spans.stream().anyMatch(s -> s.bold() && "bold".equals(s.text()));
        assertTrue(foundBold, "Should contain bold span for 'bold'");

        // Check italic span
        boolean foundItalic = spans.stream().anyMatch(s -> s.italic() && "italic".equals(s.text()));
        assertTrue(foundItalic, "Should contain italic span for 'italic'");

        // Check code span
        boolean foundCode = spans.stream().anyMatch(s -> s.code() && "code".equals(s.text()));
        assertTrue(foundCode, "Should contain code span for 'code'");

        // Check action link span
        boolean foundLink = spans.stream().anyMatch(s -> "Action Link".equals(s.text()) && "action:test".equals(s.linkAction()));
        assertTrue(foundLink, "Should contain action link span");
    }

    @Test
    void testQuoteBlock() {
        String md = "> This is a quote\n> Second line of quote";
        List<MarkdownNode> nodes = MarkdownParser.parse(md);
        assertEquals(1, nodes.size());
        assertInstanceOf(QuoteBlockNode.class, nodes.get(0));

        QuoteBlockNode quote = (QuoteBlockNode) nodes.get(0);
        assertFalse(quote.children().isEmpty());
    }

    @Test
    void testListParsing() {
        String md = "- Item 1\n- Item 2\n- Item 3";
        List<MarkdownNode> nodes = MarkdownParser.parse(md);
        assertEquals(1, nodes.size());
        assertInstanceOf(ListNode.class, nodes.get(0));

        ListNode list = (ListNode) nodes.get(0);
        assertFalse(list.ordered());
        assertEquals(3, list.items().size());
    }

    @Test
    void testTableParsing() {
        String md = "| Header 1 | Header 2 |\n| :--- | :--- |\n| Row 1 Col 1 | Row 1 Col 2 |\n| Row 2 Col 1 | Row 2 Col 2 |";
        List<MarkdownNode> nodes = MarkdownParser.parse(md);

        assertEquals(1, nodes.size());
        assertInstanceOf(TableNode.class, nodes.get(0));

        TableNode table = (TableNode) nodes.get(0);
        assertEquals(2, table.headers().size());
        assertEquals("Header 1", table.headers().get(0));
        assertEquals("Header 2", table.headers().get(1));

        assertEquals(2, table.rows().size());
        assertEquals("Row 1 Col 1", table.rows().get(0).get(0));
        assertEquals("Row 2 Col 2", table.rows().get(1).get(1));
    }
}
