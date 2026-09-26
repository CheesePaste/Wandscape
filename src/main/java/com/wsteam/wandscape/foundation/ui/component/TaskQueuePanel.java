package com.wsteam.wandscape.foundation.ui.component;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.ecs.World;

import com.mojang.blaze3d.systems.RenderSystem;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.skin.SkinRender;
import com.wsteam.wandscape.foundation.ui.skin.SkinSprite;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme;
import com.wsteam.wandscape.foundation.ui.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.*;
/**
 * Side panel displaying a building's task queue.
 * Each entry shows:
 *   [icon] [category label] × [quantity]   [↑] [↓] [×]
 *
 * <p>Category icons are drawn from Minecraft's own item/block registry.
 * If the itemOrRecipeId cannot be resolved, a generic placeholder is shown.
 *
 * <p>The top row is the locked currently-executing task (see {@link #setCurrent}),
 * with a progress bar. Pending rows below are all actionable: first pending
 * cannot move up; last pending cannot move down.
 */
public class TaskQueuePanel extends AbstractWidget {

    /**
     * One entry as received from the server.
     * Use {@link #fromBlueprint(String, String, int, String, String)} to construct
     * from legacy blueprintId + summary when structured data is unavailable.
     */
    public record Entry(
            int index,
            String category,
            String itemOrRecipeId,
            int quantity,
            String blueprintId,
            String summary,
            boolean insufficient,
            List<String> missingElements,
            boolean capacityBlocked
    ) {
        /** Legacy constructor kept for backward compatibility. */
        public Entry(int index, String blueprintId, String summary) {
            this(index, categorize(blueprintId), extractItemId(blueprintId, summary), 0, blueprintId, summary, false, List.of(), false);
        }

        private static String categorize(String bid) {
            return switch (bid) {
                case "production:decompose" -> "decompose";
                case "production:synthesize" -> "synthesize";
                case "production:craft" -> "craft";
                case "production:craft_spell" -> "transcribe";
                default -> bid.startsWith("build:") ? "build" : "other";
            };
        }

        private static String extractItemId(String bid, String summary) {
            // Best-effort: strip the "Action " prefix from legacy summary
            // e.g. "Decompose minecraft:oak_log x64" → "minecraft:oak_log"
            int sp = summary.indexOf(' ');
            if (sp > 0) {
                String rest = summary.substring(sp + 1);
                int sp2 = rest.indexOf(' ');
                if (sp2 > 0) return rest.substring(0, sp2);
                return rest;
            }
            return bid;
        }
    }

    /**
     * The building's currently executing (head) task + its progress.
     * Channel tasks ({@code channelTotalTicks > 0}) show a countdown; multi-step
     * tasks fall back to step progress. {@code pending} means the task has a channel
     * configured but it has not started yet (NPC en route) — show a waiting label
     * instead of a progress bar + countdown.
     */
    public record CurrentInfo(
            Entry entry,
            int stepIndex,
            int totalSteps,
            int channelRemainingTicks,
            int channelTotalTicks,
            boolean pending
    ) {}

    private final List<Entry> entries = new ArrayList<>();
    private final int rowHeight = 16;

    // ── Unified scrolling (mouse wheel) ──
    /**
     * Pixel offset into the panel content. Running rows and pending entries are one
     * continuous list: a shared station can run one task per member, and with enough
     * running rows the pending area was squeezed to nothing, so the wheel now moves
     * both blocks together instead of only the pending ones.
     */
    private int scrollOffset;

    // ── Current (executing) tasks ──
    private static final int CURRENT_ROW_H = 18;
    private final List<Current> currents = new ArrayList<>();

    /** One running task shown at the top of the panel, with its own animated countdown. */
    private static final class Current {
        Entry entry;
        int stepIndex;
        int totalSteps;
        int channelRemaining;
        int channelTotal;
        /** Channel task accepted but not started (NPC en route): show waiting label, no animation. */
        boolean pending;
        /** Smoothed remaining channel ticks, decremented per client tick between refreshes. */
        double animatedRemaining;
    }

    /** Callbacks wired by the parent Screen. */
    private java.util.function.IntConsumer onDelete;
    private java.util.function.IntConsumer onMoveToTop;
    private java.util.function.IntConsumer onMoveToBottom;

    // Item-icon cache: itemOrRecipeId → ItemStack (or null if not found)
    private final Map<String, ItemStack> iconCache = new HashMap<>();

    // Hover tooltip tracking
    private ItemStack hoveredTooltipStack;
    private List<Component> hoveredTooltipLines;

    // Layout constants
    private static final int ICON_SIZE   = 16;   // icon cell: 16×16 px
    private static final int ICON_GAP    = 2;    // gap between icon and label
    private static final int BTN_W       = 14;
    private static final int BTN_H       = 14;
    private static final int BTN_GAP     = 1;
    // 3 buttons × 14 + 2 gaps × 1 = 44px right margin
    private static final int BTN_AREA_W  = 3 * BTN_W + 2 * BTN_GAP;
    // Left padding for text content
    private static final int CONTENT_LEFT_PAD = 4;
    // Panel padding plus the title strip above the first row / below the last one
    private static final int CONTENT_TOP_PAD    = 4 + 10;
    private static final int CONTENT_BOTTOM_PAD = 4;

    // Sprite state indices
    private static final int ARROW_STATE_NORMAL   = 0;
    private static final int ARROW_STATE_HOVER    = 1;
    private static final int ARROW_STATE_DISABLED = 2;
    private static final int CLOSE_STATE_DISABLED = 3;

    // Normal and hover share the same arrow sprite; hover is brightened to stay distinguishable.
    private static final float HOVER_BRIGHTEN = 1.6F;

    public TaskQueuePanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.literal("Task Queue"));
    }

    public void setOnDelete(java.util.function.IntConsumer onDelete)               { this.onDelete = onDelete; }
    public void setOnMoveToTop(java.util.function.IntConsumer onMoveToTop)        { this.onMoveToTop = onMoveToTop; }
    public void setOnMoveToBottom(java.util.function.IntConsumer onMoveToBottom)  { this.onMoveToBottom = onMoveToBottom; }
    public void setOnMoveUp(java.util.function.IntConsumer onMoveUp)              { this.onMoveToTop = onMoveUp; }
    public void setOnMoveDown(java.util.function.IntConsumer onMoveDown)          { this.onMoveToBottom = onMoveDown; }

    /**
     * Replace all entries. Call from the parent Screen when new queue data arrives.
     */
    public void setEntries(List<Entry> entries) {
        this.entries.clear();
        this.iconCache.clear();
        if (entries != null) {
            this.entries.addAll(entries);
        }
        // The queue refreshes every ~second; keep the user's scroll position but clamp it
        // to the new content range so a shrunken queue never leaves the viewport empty.
        this.scrollOffset = Math.min(this.scrollOffset, maxScroll());
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /** Top edge of the scrollable content region (running rows come first, then pending entries). */
    private int contentTop() {
        return getY() + CONTENT_TOP_PAD;
    }

    /** Bottom edge (exclusive) of the scrollable content region. */
    private int contentBottom() {
        return getY() + height - CONTENT_BOTTOM_PAD;
    }

    /** Height of the visible content region. */
    private int viewportHeight() {
        return Math.max(0, contentBottom() - contentTop());
    }

    /** Total pixel height of the running rows plus the pending entries. */
    private int contentHeight() {
        return currents.size() * CURRENT_ROW_H + entries.size() * rowHeight;
    }

    /** Maximum pixel scroll offset; 0 when everything already fits. */
    private int maxScroll() {
        return Math.max(0, contentHeight() - viewportHeight());
    }

    /** Convenience single-current setter (kept for callers that only have one running task). */
    public void setCurrent(@javax.annotation.Nullable CurrentInfo info) {
        setCurrents(info == null ? List.of() : List.of(info));
    }

    /**
     * Replace the currently executing tasks shown at the top of the panel. A shared
     * building (workstation family / node) may run several concurrently — one per
     * member — so this accepts a list. Pass empty when nothing is running.
     */
    public void setCurrents(List<CurrentInfo> infos) {
        this.currents.clear();
        if (infos != null) {
            for (CurrentInfo info : infos) {
                if (info == null) continue;
                Current c = new Current();
                c.entry = info.entry();
                c.stepIndex = info.stepIndex();
                c.totalSteps = info.totalSteps();
                c.channelRemaining = info.channelRemainingTicks();
                c.channelTotal = info.channelTotalTicks();
                c.pending = info.pending();
                c.animatedRemaining = Math.max(0, info.channelRemainingTicks());
                this.currents.add(c);
            }
        }
        // Running rows are part of the scrolled content, so a shrinking batch can leave
        // the offset past the end — clamp it back.
        this.scrollOffset = Math.min(this.scrollOffset, maxScroll());
    }

    /** Decrement the animated channel countdown by one client tick. Call from the parent Screen's tick(). */
    public void tickProgress() {
        for (Current c : currents) {
            if (c.entry != null && !c.pending && c.channelTotal > 0 && c.animatedRemaining > 0) {
                c.animatedRemaining = Math.max(0, c.animatedRemaining - 1);
            }
        }
    }

    /** Fraction 0..1 through a current task (channel-based, else step-based). */
    private static float progressFraction(Current c) {
        if (c.entry == null || c.pending) return 0;
        if (c.channelTotal > 0) {
            float frac = 1f - (float) c.animatedRemaining / Math.max(1, c.channelTotal);
            return Math.max(0, Math.min(1, frac));
        }
        if (c.totalSteps > 0) {
            return (float) c.stepIndex / Math.max(1, c.totalSteps);
        }
        return 0;
    }

    /** Short "time remaining" label for a current task row, or a waiting label when not started. */
    private static String timeLabel(Current c) {
        if (c.entry == null) return "";
        if (c.pending) return pendingLabel(c.entry.category());
        if (c.channelTotal > 0) {
            int sec = (int) Math.ceil(c.animatedRemaining / 20.0);
            if (sec >= 60) return String.format("%d:%02d", sec / 60, sec % 60);
            return "≈" + sec + "s";
        }
        if (c.totalSteps > 0) {
            return c.stepIndex + "/" + c.totalSteps;
        }
        return "";
    }

    /** Localized "waiting to start" label for a pending channel task. */
    private static String pendingLabel(String cat) {
        return switch (cat) {
            case "decompose" -> I18n.name("gui.wandscape.queue.pending.decompose", "待分解").getString();
            case "synthesize" -> I18n.name("gui.wandscape.queue.pending.synthesize", "待合成").getString();
            case "craft"      -> I18n.name("gui.wandscape.queue.pending.craft", "待制作").getString();
            case "brew"       -> I18n.name("gui.wandscape.queue.pending.brew", "待炼制").getString();
            case "build"      -> I18n.name("gui.wandscape.queue.pending.build", "待建造").getString();
            case "gather"     -> I18n.name("gui.wandscape.queue.pending.gather", "待采集").getString();
            case "transcribe" -> I18n.name("gui.wandscape.queue.pending.transcribe", "待抄录").getString();
            default           -> I18n.name("gui.wandscape.queue.pending.other", "待执行").getString();
        };
    }

    /** Simple track + gold fill progress bar. */
    private static void drawProgressBar(GuiGraphics g, int x, int y, int w, int h, float frac) {
        g.fill(x, y, x + w, y + h, 0x66000000);
        int fw = Math.round(w * frac);
        if (fw > 0) {
            g.fill(x, y, x + fw, y + h, 0xFFD4A840);
        }
    }

    /** Resolve a cached ItemStack for the given resource id, or null. */
    @javax.annotation.Nullable
    private ItemStack resolveIcon(String itemOrRecipeId) {
        if (itemOrRecipeId == null || itemOrRecipeId.isBlank()) return null;
        return iconCache.computeIfAbsent(itemOrRecipeId, id -> {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) return null;
            // Try block first, then item
            Block block = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
            if (block != null && !(block.defaultBlockState().isAir())) {
                return new ItemStack(block);
            }
            Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                return new ItemStack(item);
            }
            return null;
        });
    }

    /** Render an icon stack at (x, y), centred vertically within a rowHeight tall cell. */
    private void renderIcon(GuiGraphics g, ItemStack stack, int x, int y, int rowHeight) {
        if (stack.isEmpty()) return;
        int iconY = y + (rowHeight - ICON_SIZE) / 2;
        g.renderItem(stack, x, iconY);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        hoveredTooltipStack = null;
        hoveredTooltipLines = null;

        // Background panel
        SkinRender.drawPanel9Slice(g, SkinSprite.PANEL_B, getX(), getY(), width, height);

        int rightPad     = 4;  // padding between button area and panel right edge
        int colRightStart = getX() + width - BTN_AREA_W - rightPad;

        // Row area — running rows and pending entries share one scroll region
        int regionTop  = contentTop();
        int listBottom = contentBottom();
        int maxScroll  = maxScroll();
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }

        boolean scrollable = listBottom > regionTop && maxScroll > 0;
        if (scrollable) {
            // Clip partially-scrolled rows so they never paint over the panel edges
            g.enableScissor(getX(), regionTop, getX() + width, listBottom);
        }

        // Hover only counts inside the viewport: a row scrolled under the top edge is not visible.
        int hoverMouseY = mouseY >= regionTop && mouseY < listBottom ? mouseY : Integer.MIN_VALUE;

        // ── Current (executing) tasks — leading block of the scroll region, each with a progress bar ──
        int currentBase = regionTop - scrollOffset;
        for (int i = 0; i < currents.size(); i++) {
            int rowY = currentBase + i * CURRENT_ROW_H;
            if (rowY + CURRENT_ROW_H <= regionTop) continue;   // scrolled off the top edge
            if (rowY >= listBottom) break;                     // past the bottom edge
            renderCurrentRow(g, rowY, currents.get(i), mouseX, hoverMouseY);
        }

        // ── Pending entries — directly below the running rows, moving with the same offset ──
        int pendingBase = currentBase + currents.size() * CURRENT_ROW_H;
        int startRow = Math.max(0, (regionTop - pendingBase) / rowHeight);
        for (int row = startRow; row < entries.size(); row++) {
            int rowBaseY = pendingBase + row * rowHeight;
            if (rowBaseY + rowHeight <= regionTop) continue;   // scrolled off the top edge
            if (rowBaseY >= listBottom) break;                 // past the bottom edge

            Entry e = entries.get(row);

            // Alternating row background
            if (row % 2 == 1) {
                g.fill(getX() + 1, rowBaseY, getX() + width - 1, rowBaseY + rowHeight - 1, 0x22FFFFFF);
            }

            int contentX = getX() + CONTENT_LEFT_PAD;
            int centerY  = rowBaseY + rowHeight / 2;
            int btnY     = rowBaseY + (rowHeight - BTN_H) / 2;

            // ── Icon ──
            ItemStack icon = resolveIcon(e.itemOrRecipeId);
            if (icon != null) {
                renderIcon(g, icon, contentX, rowBaseY, rowHeight);
            }

            // ── Category label + item quantity ──
            int labelX = contentX + ICON_SIZE + ICON_GAP;
            Component label = categoryLabel(e.category);
            g.drawString(Minecraft.getInstance().font, label,
                    labelX, centerY - 4, MedievalColors.TEXT_DIM);

            int curX = labelX + Minecraft.getInstance().font.width(label);
            if (e.quantity > 0) {
                String qtyStr = " x" + e.quantity;
                g.drawString(Minecraft.getInstance().font, qtyStr,
                        curX, centerY - 4, MedievalColors.TEXT_MUTED);
                curX += Minecraft.getInstance().font.width(qtyStr);
            }

            // ── Blockage status tag (right-aligned before action buttons) ──
            int textColEnd = colRightStart - 2;
            int statusBlockX = textColEnd;

            if (e.capacityBlocked) {
                Component shortTag = I18n.name("gui.wandscape.queue.capacity", "容量不足");
                int tagW = Minecraft.getInstance().font.width(shortTag);
                statusBlockX = textColEnd - tagW;
                if (statusBlockX >= curX + 2) {
                    g.drawString(Minecraft.getInstance().font, shortTag,
                            statusBlockX, centerY - 4, 0xFFE05040);
                }
            } else if (e.insufficient) {
                if (e.missingElements != null && !e.missingElements.isEmpty()) {
                    Component shortTag = I18n.name("gui.wandscape.queue.insufficient", "缺");
                    int tagW = Minecraft.getInstance().font.width(shortTag);
                    int iconCount = e.missingElements.size();
                    int totalBlockW = tagW + 2 + iconCount * 11 - 2;
                    statusBlockX = textColEnd - totalBlockW;
                    if (statusBlockX < curX + 2) statusBlockX = curX + 2;

                    g.drawString(Minecraft.getInstance().font, shortTag,
                            statusBlockX, centerY - 4, 0xFFE05040);

                    int iconX = statusBlockX + tagW + 2;
                    for (String el : e.missingElements) {
                        if (iconX + 9 > textColEnd) break;
                        ResourceLocation ico = WandscapeTheme.elementIcon(el);
                        if (ico != null) {
                            WandscapeTheme.drawIcon(g, ico, iconX, centerY - 5, 9, 9, WandscapeTheme.elementColor(el));
                        }
                        iconX += 11;
                    }
                } else {
                    Component shortTag = I18n.name("gui.wandscape.queue.missing_materials", "缺材料");
                    int tagW = Minecraft.getInstance().font.width(shortTag);
                    statusBlockX = textColEnd - tagW;
                    if (statusBlockX >= curX + 2) {
                        g.drawString(Minecraft.getInstance().font, shortTag,
                                statusBlockX, centerY - 4, 0xFFE05040);
                    }
                }
            }

            // ── Hover tooltip tracking ──
            if (hoverMouseY >= rowBaseY && hoverMouseY < rowBaseY + rowHeight) {
                if (mouseX >= getX() && mouseX < colRightStart) {
                    if ((e.capacityBlocked || e.insufficient) && mouseX >= statusBlockX && mouseX <= textColEnd) {
                        if (e.capacityBlocked) {
                            hoveredTooltipLines = List.of(I18n.name("gui.wandscape.queue.tooltip.capacity", "殖民地仓库容量不足"));
                        } else if (e.missingElements != null && !e.missingElements.isEmpty()) {
                            String elNames = formatElements(e.missingElements);
                            hoveredTooltipLines = List.of(I18n.name("gui.wandscape.queue.tooltip.missing_elements", "缺少元素: %s", elNames));
                        } else {
                            hoveredTooltipLines = List.of(I18n.name("gui.wandscape.queue.tooltip.missing_materials", "缺少原料，等待输入"));
                        }
                    } else if (icon != null && !icon.isEmpty()) {
                        hoveredTooltipStack = icon;
                    }
                } else if (mouseX >= colRightStart && mouseX < colRightStart + BTN_AREA_W && hoverMouseY >= btnY && hoverMouseY < btnY + BTN_H) {
                    int col = (mouseX - colRightStart) / (BTN_W + BTN_GAP);
                    if (col == 0) {
                        hoveredTooltipLines = List.of(I18n.name("gui.wandscape.queue.tooltip.move_to_top", "置顶任务"));
                    } else if (col == 1) {
                        hoveredTooltipLines = List.of(I18n.name("gui.wandscape.queue.tooltip.move_to_bottom", "置底任务"));
                    } else if (col == 2) {
                        hoveredTooltipLines = List.of(I18n.name("gui.wandscape.queue.tooltip.cancel", "取消任务"));
                    }
                }
            }

            // ── Action buttons ──
            boolean canTop    = onMoveToTop != null    && e.index > 0;
            boolean canBottom = onMoveToBottom != null && e.index < entries.size() - 1;
            boolean canDelete = onDelete != null;

            drawToTopBtn   (g, colRightStart,                   btnY, canTop,    mouseX, hoverMouseY);
            drawToBottomBtn(g, colRightStart + BTN_W + BTN_GAP,  btnY, canBottom, mouseX, hoverMouseY);
            drawCloseBtn   (g, colRightStart + 2*(BTN_W+BTN_GAP),btnY, canDelete, mouseX, hoverMouseY,
                        () -> { if (canDelete && onDelete != null)   onDelete.accept(e.index);    });
        }

        if (scrollable) {
            g.disableScissor();
            // Thin scrollbar in the right padding, shown only while the content overflows
            RenderUtil.drawScrollbar(g, getX() + width - 3, regionTop, 3, listBottom - regionTop,
                    contentHeight(), scrollOffset);
        }
    }

    /** Draw a locked current-task row: icon + label + remaining time + progress bar. */
    private void renderCurrentRow(GuiGraphics g, int rowY, Current c, int mouseX, int mouseY) {
        // Gold-tinted highlight so the running task stands out from pending rows
        g.fill(getX() + 1, rowY, getX() + width - 1, rowY + CURRENT_ROW_H - 1, 0x44D4A840);

        int contentX = getX() + CONTENT_LEFT_PAD;
        // Current row has no buttons — time text and progress bar can use the full panel width,
        // keeping them clear of long category labels.
        int textRight = getX() + width - 4;

        ItemStack icon = resolveIcon(c.entry.itemOrRecipeId());
        if (icon != null) {
            renderIcon(g, icon, contentX, rowY, CURRENT_ROW_H);
        }

        int labelX = contentX + ICON_SIZE + ICON_GAP;
        Component catLabel = categoryLabel(c.entry.category());
        g.drawString(Minecraft.getInstance().font, catLabel, labelX, rowY + 2, MedievalColors.ACCENT_GOLD);

        int curX = labelX + Minecraft.getInstance().font.width(catLabel);
        if (c.entry.quantity() > 0) {
            g.drawString(Minecraft.getInstance().font, " x" + c.entry.quantity(), curX, rowY + 2, MedievalColors.TEXT_MUTED);
        }

        String time = timeLabel(c);
        if (!time.isEmpty()) {
            int timeW = Minecraft.getInstance().font.width(time);
            g.drawString(Minecraft.getInstance().font, time, textRight - timeW, rowY + 2, MedievalColors.TEXT_MUTED);
        }

        // Progress bar spans from the label start to the right text edge.
        // Skipped while the channel task is pending (not started yet) — a waiting label shows instead.
        if (!c.pending) {
            int barW = Math.max(8, textRight - labelX);
            drawProgressBar(g, labelX, rowY + 13, barW, 3, progressFraction(c));
        }

        if (mouseX >= getX() && mouseX < getX() + width && mouseY >= rowY && mouseY < rowY + CURRENT_ROW_H) {
            if (icon != null && !icon.isEmpty()) {
                hoveredTooltipStack = icon;
            }
        }
    }

    /**
     * Render tooltip if hovering over a queue row item or shortage tag.
     * Call from Screen's {@code renderForeground}.
     */
    public void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (hoveredTooltipStack != null && !hoveredTooltipStack.isEmpty()) {
            g.renderTooltip(Minecraft.getInstance().font, hoveredTooltipStack, mouseX, mouseY);
        } else if (hoveredTooltipLines != null && !hoveredTooltipLines.isEmpty()) {
            g.renderComponentTooltip(Minecraft.getInstance().font, hoveredTooltipLines, mouseX, mouseY);
        }
    }

    private static String formatElements(List<String> elements) {
        if (elements == null || elements.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) sb.append(", ");
            String id = elements.get(i);
            sb.append(I18n.name("element.wandscape." + id, id).getString());
        }
        return sb.toString();
    }

    /**
     * Map internal category key to a short display label (localized).
     * Keep strings short so they fit on one line with the icon.
     */
    private static Component categoryLabel(String cat) {
        String key = "gui.wandscape.queue.category." + cat;
        return switch (cat) {
            case "decompose" -> I18n.name(key, "Decompose");
            case "synthesize" -> I18n.name(key, "Synthesize");
            case "craft"      -> I18n.name(key, "Craft");
            case "brew"       -> I18n.name(key, "Brew");
            case "build"      -> I18n.name(key, "Build");
            case "gather"     -> I18n.name(key, "Gather");
            case "transcribe" -> I18n.name(key, "Transcribe");
            default           -> I18n.name(key, cat);
        };
    }

    // ── Sprite button helpers ──────────────────────────────────────────────

    private void drawToTopBtn(GuiGraphics g, int btnX, int btnY,
                              boolean active, int mouseX, int mouseY) {
        int state = active
                ? (mouseX >= btnX && mouseX < btnX + BTN_W && mouseY >= btnY && mouseY < btnY + BTN_H
                    ? ARROW_STATE_HOVER
                    : ARROW_STATE_NORMAL)
                : ARROW_STATE_DISABLED;
        renderArrow(g, btnX, btnY, state, true);
        int barColor = (state == ARROW_STATE_DISABLED) ? 0x668B7355 : ((state == ARROW_STATE_HOVER) ? 0xFFFFFFFF : 0xFFD4A840);
        g.fill(btnX + 3, btnY + 2, btnX + BTN_W - 3, btnY + 3, barColor);
    }

    private void drawToBottomBtn(GuiGraphics g, int btnX, int btnY,
                                 boolean active, int mouseX, int mouseY) {
        int state = active
                ? (mouseX >= btnX && mouseX < btnX + BTN_W && mouseY >= btnY && mouseY < btnY + BTN_H
                    ? ARROW_STATE_HOVER
                    : ARROW_STATE_NORMAL)
                : ARROW_STATE_DISABLED;
        renderArrow(g, btnX, btnY, state, false);
        int barColor = (state == ARROW_STATE_DISABLED) ? 0x668B7355 : ((state == ARROW_STATE_HOVER) ? 0xFFFFFFFF : 0xFFD4A840);
        g.fill(btnX + 3, btnY + BTN_H - 3, btnX + BTN_W - 3, btnY + BTN_H - 2, barColor);
    }

    /**
     * Draws an up/down arrow sprite, brightening it on hover so the shared
     * normal sprite stays visually distinguishable. Shader color is always reset.
     */
    private void renderArrow(GuiGraphics g, int btnX, int btnY, int state, boolean up) {
        if (state == ARROW_STATE_HOVER) {
            RenderSystem.setShaderColor(HOVER_BRIGHTEN, HOVER_BRIGHTEN, HOVER_BRIGHTEN, 1.0F);
        }
        if (up) {
            SkinRender.drawUpArrow(g, btnX, btnY, BTN_W, BTN_H, state);
        } else {
            SkinRender.drawDownArrow(g, btnX, btnY, BTN_W, BTN_H, state);
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void drawCloseBtn(GuiGraphics g, int btnX, int btnY,
                              boolean active, int mouseX, int mouseY, Runnable onPress) {
        int state = active
                ? (mouseX >= btnX && mouseX < btnX + BTN_W && mouseY >= btnY && mouseY < btnY + BTN_H
                    ? 1
                    : 0)
                : CLOSE_STATE_DISABLED;
        SkinRender.drawCloseButton(g, btnX, btnY, BTN_W, BTN_H, state);
    }

    // ── Click handling ─────────────────────────────────────────────────────

    /**
     * Hit-test: find which active button the mouse is over and fire its action.
     * Scans rows bottom-up so the last rendered visible row takes priority when overlapping.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0) return false;

        int mx = (int) mouseX;
        int my = (int) mouseY;
        int regionTop = contentTop();
        int listBottom = contentBottom();
        // Pending rows start after the running rows, both shifted by the shared scroll offset
        int pendingBase = regionTop + currents.size() * CURRENT_ROW_H - scrollOffset;

        for (int row = entries.size() - 1; row >= 0; row--) {
            int rowBaseY = pendingBase + row * rowHeight;
            if (rowBaseY + rowHeight <= regionTop || rowBaseY >= listBottom) continue;

            Entry e = entries.get(row);
            int btnY = rowBaseY + (rowHeight - BTN_H) / 2;
            if (my < btnY || my > btnY + BTN_H) continue;

            int colRightStart = getX() + width - BTN_AREA_W - 4;

            // Determine which button column the mouse X falls in
            int col = -1;
            for (int c = 0; c < 3; c++) {
                int bx = colRightStart + c * (BTN_W + BTN_GAP);
                if (mx >= bx && mx < bx + BTN_W) { col = c; break; }
            }
            if (col < 0) return false;

            boolean active;
            Runnable action;
            switch (col) {
                case 0 -> { // ⤒
                    active = onMoveToTop != null && e.index > 0;
                    action = () -> { if (active && onMoveToTop != null) onMoveToTop.accept(e.index); };
                }
                case 1 -> { // ⤓
                    active = onMoveToBottom != null && e.index < entries.size() - 1;
                    action = () -> { if (active && onMoveToBottom != null) onMoveToBottom.accept(e.index); };
                }
                default -> { // ×
                    active = onDelete != null;
                    action = () -> { if (active && onDelete != null) onDelete.accept(e.index); };
                }
            }
            if (active) { action.run(); return true; }
            return false;
        }
        return false;
    }

    /** Mouse-wheel scrolls the whole panel content (running rows + pending entries). */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible) return false;
        if (mouseX < getX() || mouseX >= getX() + width
                || mouseY < getY() || mouseY >= getY() + height) {
            return false;
        }
        int maxScroll = maxScroll();
        if (maxScroll <= 0) return false;
        // 2 rows per notch, matching ScrollableList
        scrollOffset = (int) Math.clamp(scrollOffset - scrollY * rowHeight * 2, 0, maxScroll);
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
