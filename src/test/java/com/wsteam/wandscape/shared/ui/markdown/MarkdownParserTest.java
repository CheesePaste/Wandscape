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
        assertEquals(2, quote.children().size());
    }

    @Test
    void testListParsing() {
        String md = "- Item A\n- Item B\n- Item C";
        List<MarkdownNode> nodes = MarkdownParser.parse(md);

        assertEquals(1, nodes.size());
        assertInstanceOf(ListNode.class, nodes.get(0));

        ListNode list = (ListNode) nodes.get(0);
        assertFalse(list.ordered());
        assertEquals(3, list.items().size());
    }

    @Test
    void testImageParsing() {
        String md = "![TownHall](wandscape:textures/gui/guide/townhall.png =128x64)";
        List<MarkdownNode> nodes = MarkdownParser.parse(md);

        assertEquals(1, nodes.size());
        assertInstanceOf(ImageNode.class, nodes.get(0));

        ImageNode img = (ImageNode) nodes.get(0);
        assertEquals("TownHall", img.altText());
        assertEquals("wandscape:textures/gui/guide/townhall.png", img.resourceLocation());
        assertEquals(128, img.width());
        assertEquals(64, img.height());
    }
}
