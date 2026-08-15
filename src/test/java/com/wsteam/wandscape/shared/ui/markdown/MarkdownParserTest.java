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
    void testLinksWithLeadingTextAndEmoji() {
        String line = "👉 [跳转至 🏛️ 市政厅指南](townhall_guide.md)";
        List<FormattedSpan> spans = MarkdownParser.parseInlineSpans(line);

        assertEquals(2, spans.size());
        assertEquals("👉 ", spans.get(0).text());
        assertNull(spans.get(0).linkAction());

        assertEquals("跳转至 🏛️ 市政厅指南", spans.get(1).text());
        assertEquals("townhall_guide.md", spans.get(1).linkAction());
    }

    @Test
    void testMultipleLinksOnSameLine() {
        String line = "查看 [指南A](guide_a.md) 与 [指南B](guide_b.md) 的详情";
        List<FormattedSpan> spans = MarkdownParser.parseInlineSpans(line);

        assertEquals(5, spans.size());
        assertEquals("查看 ", spans.get(0).text());
        assertEquals("指南A", spans.get(1).text());
        assertEquals("guide_a.md", spans.get(1).linkAction());
        assertEquals(" 与 ", spans.get(2).text());
        assertEquals("指南B", spans.get(3).text());
        assertEquals("guide_b.md", spans.get(3).linkAction());
        assertEquals(" 的详情", spans.get(4).text());
    }

    @Test
    void testLinkWithInnerFormatting() {
        String line = "[**加粗链接**](action:bold) 和 [`代码链接`](action:code)";
        List<FormattedSpan> spans = MarkdownParser.parseInlineSpans(line);

        assertEquals(3, spans.size());
        assertEquals("加粗链接", spans.get(0).text());
        assertTrue(spans.get(0).bold());
        assertEquals("action:bold", spans.get(0).linkAction());

        assertEquals(" 和 ", spans.get(1).text());

        assertEquals("代码链接", spans.get(2).text());
        assertTrue(spans.get(2).code());
        assertEquals("action:code", spans.get(2).linkAction());
    }

    @Test
    void testBracketsInTextBeforeLink() {
        String line = "[提示] 详情请查看 [新手指南](getting_started.md)";
        List<FormattedSpan> spans = MarkdownParser.parseInlineSpans(line);

        assertEquals(2, spans.size());
        assertEquals("[提示] 详情请查看 ", spans.get(0).text());
        assertNull(spans.get(0).linkAction());

        assertEquals("新手指南", spans.get(1).text());
        assertEquals("getting_started.md", spans.get(1).linkAction());
    }

    @Test
    void testDividerRuleParsing() {
        String md = "Paragraph 1\n\n---\n\nParagraph 2";
        List<MarkdownNode> nodes = MarkdownParser.parse(md);

        assertEquals(3, nodes.size());
        assertInstanceOf(TextParagraphNode.class, nodes.get(0));
        assertInstanceOf(DividerNode.class, nodes.get(1));
        assertInstanceOf(TextParagraphNode.class, nodes.get(2));
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
    void testOrderedListParsing() {
        String md = "1. First step\n2. Second step\n3. Third step";
        List<MarkdownNode> nodes = MarkdownParser.parse(md);
        assertEquals(1, nodes.size());
        assertInstanceOf(ListNode.class, nodes.get(0));

        ListNode list = (ListNode) nodes.get(0);
        assertTrue(list.ordered());
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
