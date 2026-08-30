package com.wsteam.wandscape.shared.ui.markdown.widget;

import com.wsteam.wandscape.shared.ui.markdown.ast.*;
import com.wsteam.wandscape.shared.ui.markdown.parser.MarkdownParser;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Rich-text & Image Markdown Renderer Widget for Minecraft Wandscape UI.
 * Uses exact glyph-level character hit detection for interactive markdown links.
 */
public class MarkdownRenderWidget extends AbstractWidget {

    private List<MarkdownNode> nodes;
    private int contentHeight = 0;
    private int scrollOffset = 0;
    private Consumer<String> actionClickListener;

    /**
     * Line bounding box and sequence recorded during the render pass for pixel-perfect hit testing.
     */
    private record RenderedLine(
            int x,
            int y,
            int width,
            int height,
            FormattedCharSequence sequence
    ) {}

    private final List<RenderedLine> renderedLines = new ArrayList<>();

    public MarkdownRenderWidget(int x, int y, int width, int height, String rawMarkdown) {
        super(x, y, width, height, Component.empty());
        setMarkdown(rawMarkdown);
    }

    public void setMarkdown(String rawMarkdown) {
        this.nodes = MarkdownParser.parse(rawMarkdown);
        this.scrollOffset = 0;
        recalculateHeight();
    }

    public void setActionClickListener(Consumer<String> listener) {
        this.actionClickListener = listener;
    }

    public int getContentHeight() {
        return contentHeight;
    }

    public void setScrollOffset(int scrollOffset) {
        this.scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, contentHeight - getHeight())));
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    private void recalculateHeight() {
        if (nodes == null || nodes.isEmpty()) {
            contentHeight = 0;
            return;
        }
        Font font = Minecraft.getInstance().font;
        int renderW = Math.max(10, getWidth() - 12);
        int h = 0;

        for (MarkdownNode node : nodes) {
            h += calculateNodeHeight(node, font, renderW);
        }
        contentHeight = h;
    }

    private int calculateNodeHeight(MarkdownNode node, Font font, int width) {
        if (node instanceof HeaderNode(int level, String text)) {
            MutableComponent comp = Component.literal(text).withStyle(Style.EMPTY.withBold(true));
            List<FormattedCharSequence> lines = font.split(comp, width);
            int lineHeight = level == 1 ? 16 : level == 2 ? 14 : 12;
            return lines.size() * lineHeight + 6;
        } else if (node instanceof TextParagraphNode(List<MarkdownNode.FormattedSpan> spans)) {
            MutableComponent comp = buildComponent(spans);
            List<FormattedCharSequence> lines = font.split(comp, width);
            return lines.size() * (font.lineHeight + 2) + 4;
        } else if (node instanceof ImageNode img) {
            return (img.height() > 0 ? img.height() : 64) + 8;
        } else if (node instanceof QuoteBlockNode(List<MarkdownNode> children)) {
            int qh = 4;
            for (MarkdownNode child : children) {
                qh += calculateNodeHeight(child, font, width - 16);
            }
            return qh + 4;
        } else if (node instanceof ListNode(boolean ordered, List<MarkdownNode> items)) {
            int lh = 2;
            int index = 1;
            for (MarkdownNode item : items) {
                String prefix = ordered ? index + ". " : "• ";
                int prefixW = font.width(prefix);
                lh += calculateNodeHeight(item, font, width - prefixW - 2);
                index++;
            }
            return lh + 2;
        } else if (node instanceof TableNode(List<String> headers, List<List<String>> rows)) {
            if (headers.isEmpty()) return 0;
            int numCols = headers.size();
            int cellPadding = 4;
            int colW = Math.max(40, (width - 4) / numCols);

            int th = font.lineHeight + 6 + 4;
            for (List<String> row : rows) {
                int maxRowLines = 1;
                for (int c = 0; c < numCols; c++) {
                    String cellText = c < row.size() ? row.get(c) : "";
                    MutableComponent comp = buildComponent(MarkdownParser.parseInlineSpans(cellText));
                    List<FormattedCharSequence> lines = font.split(comp, colW - cellPadding * 2);
                    maxRowLines = Math.max(maxRowLines, Math.max(1, lines.size()));
                }
                th += maxRowLines * (font.lineHeight + 2) + 4;
            }
            return th + 6;
        } else if (node instanceof DividerNode) {
            return 8;
        }
        return 10;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int startX = getX() + 4;
        int currentY = getY() + 4 - scrollOffset;
        int renderW = Math.max(10, getWidth() - 12);

        renderedLines.clear();

        // Enable scissor clipping to prevent rendering outside widget frame
        g.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());

        for (MarkdownNode node : nodes) {
            currentY = renderNode(g, font, node, startX, currentY, renderW, mouseX, mouseY);
            if (currentY > getY() + getHeight() + 100) {
                // Offscreen bottom optimization
                break;
            }
        }

        g.disableScissor();

        // Render golden scrollbar thumb if content exceeds widget height
        int maxScroll = Math.max(0, contentHeight - getHeight());
        if (maxScroll > 0) {
            int sbX = getX() + getWidth() - 3;
            int sbY = getY() + 2;
            int sbH = getHeight() - 4;
            int thumbH = Math.max(12, sbH * sbH / Math.max(1, contentHeight));
            int thumbY = sbY + (int) ((long) (sbH - thumbH) * scrollOffset / maxScroll);

            g.fill(sbX, sbY, sbX + 2, sbY + sbH, 0x40000000);
            g.fill(sbX, thumbY, sbX + 2, thumbY + thumbH, MedievalColors.BORDER_GOLD);
        }

        // Render Hover Tooltip for active link if mouse is hovered over a link
        if (isMouseOver(mouseX, mouseY)
                && mouseX >= getX() && mouseX <= getX() + getWidth()
                && mouseY >= getY() && mouseY <= getY() + getHeight()) {
            RenderedLine hoveredLine = getLineAt(mouseX, mouseY);
            if (hoveredLine != null) {
                int relX = mouseX - hoveredLine.x();
                Style style = font.getSplitter().componentStyleAtWidth(hoveredLine.sequence(), relX);
                if (style != null && style.getClickEvent() != null) {
                    String action = style.getClickEvent().getValue();
                    Component tooltip = formatLinkTooltip(action);
                    if (tooltip != null) {
                        g.renderTooltip(font, tooltip, mouseX, mouseY);
                    }
                }
            }
        }
    }

    private int renderNode(GuiGraphics g, Font font, MarkdownNode node, int x, int y, int width, int mouseX, int mouseY) {
        if (node instanceof HeaderNode(int level, String text)) {
            int color = level == 1 ? MedievalColors.BORDER_GOLD : level == 2 ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE;
            int lineHeight = level == 1 ? 16 : level == 2 ? 14 : 12;

            MutableComponent comp = Component.literal(text).withStyle(Style.EMPTY.withBold(true));
            List<FormattedCharSequence> lines = font.split(comp, width);
            int lineY = y;

            for (FormattedCharSequence line : lines) {
                if (lineY + font.lineHeight >= getY() && lineY <= getY() + getHeight()) {
                    g.drawString(font, line, x, lineY + 2, color, true);
                    renderedLines.add(new RenderedLine(x, lineY + 2, font.width(line), font.lineHeight, line));
                }
                lineY += lineHeight;
            }

            // Header underline for H1
            if (level == 1 && lineY >= getY() && lineY <= getY() + getHeight()) {
                g.fill(x, lineY, x + width, lineY + 1, MedievalColors.BORDER_GOLD_DARK);
            }
            return lineY + 6;
        }

        if (node instanceof TextParagraphNode(List<MarkdownNode.FormattedSpan> spans)) {
            MutableComponent comp = buildComponent(spans);
            List<FormattedCharSequence> lines = font.split(comp, width);
            int lineY = y;

            for (FormattedCharSequence line : lines) {
                if (lineY + font.lineHeight >= getY() && lineY <= getY() + getHeight()) {
                    g.drawString(font, line, x, lineY, MedievalColors.TEXT_WARM_WHITE, true);
                    renderedLines.add(new RenderedLine(x, lineY, font.width(line), font.lineHeight, line));
                }
                lineY += font.lineHeight + 2;
            }

            return lineY + 4;
        }

        if (node instanceof ImageNode(String altText, String resourceLocation, int width1, int height1)) {
            int imgW = width1 > 0 ? width1 : Math.min(width, 128);
            int imgH = height1 > 0 ? height1 : 64;
            int imgX = x + (width - imgW) / 2;

            if (y + imgH >= getY() && y <= getY() + getHeight()) {
                try {
                    ResourceLocation tex = ResourceLocation.parse(resourceLocation);
                    ResourceLocation activeTex = com.wsteam.wandscape.shared.ui.markdown.texture.MarkdownTextureManager.getActiveTexture(tex);
                    g.blit(activeTex, imgX, y, 0.0f, 0.0f, imgW, imgH, imgW, imgH);
                    // Gold border frame
                    g.renderOutline(imgX - 1, y - 1, imgW + 2, imgH + 2, MedievalColors.BORDER_GOLD_DARK);
                } catch (Exception e) {
                    // Fallback placeholder text if image texture missing
                    g.fill(imgX, y, imgX + imgW, y + imgH, MedievalColors.PARCHMENT_DARK);
                    g.drawString(font, "[" + altText + "]", imgX + 4, y + imgH / 2 - 4, MedievalColors.TEXT_MUTED, false);
                }
            }
            return y + imgH + 8;
        }

        if (node instanceof QuoteBlockNode(List<MarkdownNode> children)) {
            int quoteInnerH = 0;
            for (MarkdownNode child : children) {
                quoteInnerH += calculateNodeHeight(child, font, width - 16);
            }
            int quoteHeight = quoteInnerH + 8;

            // Draw background and left golden indicator bar FIRST
            if (y + quoteHeight >= getY() && y <= getY() + getHeight()) {
                g.fill(x + 2, y, x + width, y + quoteHeight, MedievalColors.PARCHMENT_DARK);
                g.fill(x + 2, y, x + 5, y + quoteHeight, MedievalColors.BORDER_GOLD);
            }

            // Render quote children text on top of the background
            int innerY = y + 4;
            for (MarkdownNode child : children) {
                innerY = renderNode(g, font, child, x + 12, innerY, width - 16, mouseX, mouseY);
            }

            return innerY + 4;
        }

        if (node instanceof ListNode(boolean ordered, List<MarkdownNode> items)) {
            int listY = y;
            int index = 1;

            for (MarkdownNode item : items) {
                String prefix = ordered ? index + ". " : "• ";
                int prefixW = font.width(prefix);

                if (listY + font.lineHeight >= getY() && listY <= getY() + getHeight()) {
                    g.drawString(font, prefix, x, listY, MedievalColors.BORDER_GOLD, true);
                }
                int itemH = renderNode(g, font, item, x + prefixW + 2, listY, width - prefixW - 2, mouseX, mouseY);
                listY = itemH;
                index++;
            }
            return listY + 2;
        }

        if (node instanceof TableNode(List<String> headers, List<List<String>> rows)) {
            if (headers.isEmpty()) {
                return y;
            }

            int numCols = headers.size();
            int cellPadding = 4;
            int tableW = width - 4;
            int colW = Math.max(40, tableW / numCols);

            int currentY = y + 4;

            // Render Header Row
            int headerH = font.lineHeight + 6;
            if (currentY + headerH >= getY() && currentY <= getY() + getHeight()) {
                g.fill(x, currentY, x + tableW, currentY + headerH, MedievalColors.PARCHMENT_DARK);
                g.fill(x, currentY, x + tableW, currentY + 1, MedievalColors.BORDER_GOLD);
                g.fill(x, currentY + headerH - 1, x + tableW, currentY + headerH, MedievalColors.BORDER_GOLD);

                for (int c = 0; c < numCols; c++) {
                    int cellX = x + c * colW + cellPadding;
                    String hText = headers.get(c);
                    g.drawString(font, font.plainSubstrByWidth(hText, colW - cellPadding * 2), cellX, currentY + 3, MedievalColors.ACCENT_GOLD, true);
                    if (c > 0) {
                        g.fill(x + c * colW, currentY, x + c * colW + 1, currentY + headerH, MedievalColors.BORDER_GOLD_DARK);
                    }
                }
            }
            currentY += headerH;

            // Render Data Rows
            int rowIndex = 0;
            for (List<String> row : rows) {
                int maxRowLines = 1;
                List<List<FormattedCharSequence>> cellLinesList = new ArrayList<>();

                for (int c = 0; c < numCols; c++) {
                    String cellText = c < row.size() ? row.get(c) : "";
                    MutableComponent comp = buildComponent(MarkdownParser.parseInlineSpans(cellText));
                    List<FormattedCharSequence> lines = font.split(comp, colW - cellPadding * 2);
                    if (lines.isEmpty()) {
                        lines = List.of(Component.literal("").getVisualOrderText());
                    }
                    cellLinesList.add(lines);
                    maxRowLines = Math.max(maxRowLines, lines.size());
                }

                int rowH = maxRowLines * (font.lineHeight + 2) + 4;

                if (currentY + rowH >= getY() && currentY <= getY() + getHeight()) {
                    int rowBg = (rowIndex % 2 == 0) ? 0x301C1410 : 0x181C1410;
                    g.fill(x, currentY, x + tableW, currentY + rowH, rowBg);

                    for (int c = 0; c < numCols; c++) {
                        int cellX = x + c * colW + cellPadding;
                        List<FormattedCharSequence> cellLines = cellLinesList.get(c);
                        int lineY = currentY + 2;
                        for (FormattedCharSequence line : cellLines) {
                            g.drawString(font, line, cellX, lineY, MedievalColors.TEXT_WARM_WHITE, true);
                            renderedLines.add(new RenderedLine(cellX, lineY, font.width(line), font.lineHeight, line));
                            lineY += font.lineHeight + 2;
                        }
                        if (c > 0) {
                            g.fill(x + c * colW, currentY, x + c * colW + 1, currentY + rowH, 0x40C8A040);
                        }
                    }
                    g.fill(x, currentY + rowH - 1, x + tableW, currentY + rowH, 0x40C8A040);
                }

                currentY += rowH;
                rowIndex++;
            }

            return currentY + 6;
        }

        if (node instanceof DividerNode) {
            int divY = y + 3;
            if (divY >= getY() && divY <= getY() + getHeight()) {
                g.fill(x, divY, x + width, divY + 1, MedievalColors.BORDER_GOLD_DARK);
            }
            return y + 8;
        }

        return y + 10;
    }

    private MutableComponent buildComponent(List<MarkdownNode.FormattedSpan> spans) {
        MutableComponent root = Component.empty();
        for (MarkdownNode.FormattedSpan span : spans) {
            Style style = Style.EMPTY
                    .withBold(span.bold())
                    .withItalic(span.italic())
                    .withStrikethrough(span.strikethrough());

            if (span.color() != null) {
                style = style.withColor(span.color());
            }

            if (span.code()) {
                style = style.withColor(MedievalColors.BORDER_GOLD);
            }

            if (span.linkAction() != null) {
                style = style.withColor(MedievalColors.BORDER_GOLD)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, span.linkAction()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(span.linkAction())));
            }

            root.append(Component.literal(span.text()).withStyle(style));
        }
        return root;
    }

    private RenderedLine getLineAt(double mouseX, double mouseY) {
        if (mouseX < getX() || mouseX > getX() + getWidth() || mouseY < getY() || mouseY > getY() + getHeight()) {
            return null;
        }
        for (RenderedLine line : renderedLines) {
            if (mouseY >= line.y() && mouseY < line.y() + line.height() + 2
                    && mouseX >= line.x() && mouseX <= line.x() + line.width()) {
                return line;
            }
        }
        return null;
    }

    private Component formatLinkTooltip(String action) {
        if (action == null || action.isBlank()) return null;
        if (action.startsWith("action:")) {
            return Component.translatable("gui.wandscape.guide.action_tooltip", action.substring(7));
        } else if (action.startsWith("http://") || action.startsWith("https://")) {
            return Component.literal(action);
        } else if (action.endsWith(".md") || action.startsWith("guide:")) {
            String doc = action.startsWith("guide:") ? action.substring(6) : action;
            if (doc.endsWith(".md")) {
                doc = doc.substring(0, doc.length() - 3);
            }
            return Component.literal(doc);
        }
        return Component.literal(action);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isMouseOver(mouseX, mouseY)) {
            RenderedLine line = getLineAt(mouseX, mouseY);
            if (line != null) {
                Font font = Minecraft.getInstance().font;
                int relX = (int) (mouseX - line.x());
                Style style = font.getSplitter().componentStyleAtWidth(line.sequence(), relX);
                if (style != null && style.getClickEvent() != null) {
                    String action = style.getClickEvent().getValue();
                    if (actionClickListener != null) {
                        actionClickListener.accept(action);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isMouseOver(mouseX, mouseY)) {
            setScrollOffset(scrollOffset - (int) (scrollY * 12));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {}
}
