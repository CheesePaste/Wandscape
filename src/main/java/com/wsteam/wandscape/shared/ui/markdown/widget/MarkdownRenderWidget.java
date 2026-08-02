package com.wsteam.wandscape.shared.ui.markdown.widget;

import com.wsteam.wandscape.shared.ui.markdown.ast.*;
import com.wsteam.wandscape.shared.ui.markdown.parser.MarkdownParser;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Rich-text & Image Markdown Renderer Widget for Minecraft Wandscape UI.
 */
public class MarkdownRenderWidget extends AbstractWidget {

    private List<MarkdownNode> nodes;
    private int contentHeight = 0;
    private int scrollOffset = 0;
    private Consumer<String> actionClickListener;

    private final List<LinkHitBox> linkHitBoxes = new ArrayList<>();

    private record LinkHitBox(int x, int y, int width, int height, String action) {}

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
        if (node instanceof HeaderNode header) {
            return (header.level() == 1 ? 16 : header.level() == 2 ? 14 : 12) + 6;
        } else if (node instanceof TextParagraphNode paragraph) {
            MutableComponent comp = buildComponent(paragraph.spans());
            List<FormattedCharSequence> lines = font.split(comp, width);
            return (lines.size() * (font.lineHeight + 2)) + 4;
        } else if (node instanceof ImageNode img) {
            return (img.height() > 0 ? img.height() : 64) + 8;
        } else if (node instanceof QuoteBlockNode quote) {
            int qh = 4;
            for (MarkdownNode child : quote.children()) {
                qh += calculateNodeHeight(child, font, width - 16);
            }
            return qh + 4;
        } else if (node instanceof ListNode list) {
            int lh = 2;
            for (MarkdownNode item : list.items()) {
                lh += calculateNodeHeight(item, font, width - 12);
            }
            return lh + 2;
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

        linkHitBoxes.clear();

        // Enable scissor clipping
        g.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());

        for (MarkdownNode node : nodes) {
            currentY = renderNode(g, font, node, startX, currentY, renderW, mouseX, mouseY);
            if (currentY > getY() + getHeight() + 100) {
                // Offscreen bottom optimization
                break;
            }
        }

        g.disableScissor();
    }

    private int renderNode(GuiGraphics g, Font font, MarkdownNode node, int x, int y, int width, int mouseX, int mouseY) {
        if (node instanceof HeaderNode header) {
            int color = header.level() == 1 ? MedievalColors.BORDER_GOLD : header.level() == 2 ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE;
            int lineHeight = header.level() == 1 ? 16 : header.level() == 2 ? 14 : 12;

            g.drawString(font, Component.literal(header.text()), x, y + 2, color, true);
            // Header underline for H1
            if (header.level() == 1) {
                g.fill(x, y + lineHeight, x + width, y + lineHeight + 1, MedievalColors.BORDER_GOLD_DARK);
            }
            return y + lineHeight + 6;
        }

        if (node instanceof TextParagraphNode paragraph) {
            MutableComponent comp = buildComponent(paragraph.spans());
            List<FormattedCharSequence> lines = font.split(comp, width);
            int lineY = y;

            for (FormattedCharSequence line : lines) {
                if (lineY + font.lineHeight >= getY() && lineY <= getY() + getHeight()) {
                    g.drawString(font, line, x, lineY, MedievalColors.TEXT_WARM_WHITE, true);
                }
                lineY += font.lineHeight + 2;
            }

            // Register link hitboxes for action spans
            for (MarkdownNode.FormattedSpan span : paragraph.spans()) {
                if (span.linkAction() != null) {
                    int linkW = font.width(span.text());
                    linkHitBoxes.add(new LinkHitBox(x, y, linkW, font.lineHeight + 2, span.linkAction()));
                }
            }

            return lineY + 2;
        }

        if (node instanceof ImageNode img) {
            int imgW = img.width() > 0 ? img.width() : Math.min(width, 128);
            int imgH = img.height() > 0 ? img.height() : 64;
            int imgX = x + (width - imgW) / 2;

            if (y + imgH >= getY() && y <= getY() + getHeight()) {
                try {
                    ResourceLocation tex = ResourceLocation.parse(img.resourceLocation());
                    ResourceLocation activeTex = com.wsteam.wandscape.shared.ui.markdown.texture.MarkdownTextureManager.getActiveTexture(tex);
                    g.blit(activeTex, imgX, y, 0.0f, 0.0f, imgW, imgH, imgW, imgH);
                    // Gold border frame
                    g.renderOutline(imgX - 1, y - 1, imgW + 2, imgH + 2, MedievalColors.BORDER_GOLD_DARK);
                } catch (Exception e) {
                    // Fallback placeholder text if image texture missing
                    g.fill(imgX, y, imgX + imgW, y + imgH, MedievalColors.PARCHMENT_DARK);
                    g.drawString(font, "[" + img.altText() + "]", imgX + 4, y + imgH / 2 - 4, MedievalColors.TEXT_MUTED, false);
                }
            }
            return y + imgH + 8;
        }

        if (node instanceof QuoteBlockNode quote) {
            int quoteStartY = y;
            int innerY = y + 4;

            for (MarkdownNode child : quote.children()) {
                innerY = renderNode(g, font, child, x + 12, innerY, width - 16, mouseX, mouseY);
            }

            int quoteHeight = innerY - quoteStartY + 4;
            if (quoteStartY + quoteHeight >= getY() && quoteStartY <= getY() + getHeight()) {
                // Background & left bar
                g.fill(x + 2, quoteStartY, x + width, quoteStartY + quoteHeight, MedievalColors.PARCHMENT_DARK);
                g.fill(x + 2, quoteStartY, x + 5, quoteStartY + quoteHeight, MedievalColors.BORDER_GOLD);
            }
            return innerY + 4;
        }

        if (node instanceof ListNode list) {
            int listY = y;
            int index = 1;

            for (MarkdownNode item : list.items()) {
                String prefix = list.ordered() ? index + ". " : "• ";
                if (listY + font.lineHeight >= getY() && listY <= getY() + getHeight()) {
                    g.drawString(font, prefix, x, listY, MedievalColors.BORDER_GOLD, true);
                }
                int itemH = renderNode(g, font, item, x + 12, listY, width - 12, mouseX, mouseY);
                listY = itemH;
                index++;
            }
            return listY + 2;
        }

        if (node instanceof TableNode table) {
            if (table.headers().isEmpty()) {
                return y;
            }

            int numCols = table.headers().size();
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
                    String hText = table.headers().get(c);
                    g.drawString(font, font.plainSubstrByWidth(hText, colW - cellPadding * 2), cellX, currentY + 3, MedievalColors.ACCENT_GOLD, true);
                    if (c > 0) {
                        g.fill(x + c * colW, currentY, x + c * colW + 1, currentY + headerH, MedievalColors.BORDER_GOLD_DARK);
                    }
                }
            }
            currentY += headerH;

            // Render Data Rows
            int rowIndex = 0;
            for (List<String> row : table.rows()) {
                int maxRowLines = 1;
                List<List<FormattedCharSequence>> cellLinesList = new ArrayList<>();

                for (int c = 0; c < numCols; c++) {
                    String cellText = c < row.size() ? row.get(c) : "";
                    List<FormattedCharSequence> lines = font.split(Component.literal(cellText), colW - cellPadding * 2);
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

        return y + 10;
    }

    private MutableComponent buildComponent(List<MarkdownNode.FormattedSpan> spans) {
        MutableComponent root = Component.empty();
        for (MarkdownNode.FormattedSpan span : spans) {
            Style style = Style.EMPTY
                    .withBold(span.bold())
                    .withItalic(span.italic())
                    .withStrikethrough(span.strikethrough());

            if (span.linkAction() != null) {
                style = style.withColor(MedievalColors.BORDER_GOLD)
                        .withUnderlined(true)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to execute: " + span.linkAction())));
            }

            root.append(Component.literal(span.text()).withStyle(style));
        }
        return root;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isMouseOver(mouseX, mouseY)) {
            for (LinkHitBox box : linkHitBoxes) {
                if (mouseX >= box.x() && mouseX <= box.x() + box.width()
                        && mouseY >= box.y() && mouseY <= box.y() + box.height()) {
                    if (actionClickListener != null) {
                        actionClickListener.accept(box.action());
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
