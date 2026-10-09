package com.wsteam.wandscape.foundation.ui.component;

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

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/**
 * Side panel displaying a building's task queue, organized into visual blocks grouped
 * by source (building construction, road construction, player request, etc.).
 *
 * <p>Each group has:
 * <ul>
 *   <li>A header with collapse/expand toggle indicator (▼ / ▶), group title, total task count,
 *       and a group cancel button [×] to cancel all tasks in that group with one click.</li>
 *   <li>A colored vertical accent strip and subtle frame distinguishing each group.</li>
 *   <li>Inside expanded groups: running tasks (with progress bar) and pending tasks
 *       (with icon, category, quantity, shortage tags, and [↑] [↓] [×] buttons).</li>
 * </ul>
 */
public class TaskQueuePanel extends AbstractWidget {

    /**
     * One entry in the task queue.
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
            boolean capacityBlocked,
            String sourceType,
            String sourceId,
            String sourceName
    ) {
        /** Compact constructor defaulting source info to player. */
        public Entry(int index, String category, String itemOrRecipeId, int quantity,
                     String blueprintId, String summary, boolean insufficient,
                     List<String> missingElements, boolean capacityBlocked) {
            this(index, category, itemOrRecipeId, quantity, blueprintId, summary,
                    insufficient, missingElements, capacityBlocked, "player", "", "");
        }

        /** Legacy constructor kept for backward compatibility. */
        public Entry(int index, String blueprintId, String summary) {
            this(index, categorize(blueprintId), extractItemId(blueprintId, summary), 0,
                    blueprintId, summary, false, List.of(), false, "player", "", "");
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
     */
    public record CurrentInfo(
            Entry entry,
            int stepIndex,
            int totalSteps,
            int channelRemainingTicks,
            int channelTotalTicks,
            boolean pending
    ) {}

    /** Internal representation of a running task with client-side smoothed countdown. */
    private static final class Current {
        Entry entry;
        int stepIndex;
        int totalSteps;
        int channelRemaining;
        int channelTotal;
        boolean pending;
        double animatedRemaining;
    }

    /**
     * Visual block representing tasks originating from the same source
     * (e.g. under-construction building, road segment, player order).
     */
    public static final class TaskGroup {
        public final String key;
        public final String sourceType;
        public final String sourceId;
        public final String sourceName;
        public final List<Current> currents = new ArrayList<>();
        public final List<Entry> entries = new ArrayList<>();

        public TaskGroup(String key, String sourceType, String sourceId, String sourceName) {
            this.key = key;
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.sourceName = sourceName;
        }

        public int totalCount() {
            return currents.size() + entries.size();
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Current> currents = new ArrayList<>();
    private final List<TaskGroup> groups = new ArrayList<>();
    private final Set<String> collapsedGroups = new HashSet<>();

    // Layout constants
    private final int rowHeight = 16;
    private static final int HEADER_H        = 16;
    private static final int CURRENT_ROW_H   = 18;
    private static final int BLOCK_GAP       = 3;
    private static final int ICON_SIZE       = 16;
    private static final int ICON_GAP        = 2;
    private static final int BTN_W           = 14;
    private static final int BTN_H           = 14;
    private static final int BTN_GAP         = 1;
    private static final int BTN_AREA_W      = 3 * BTN_W + 2 * BTN_GAP;
    private static final int CONTENT_LEFT_PAD = 3;
    private static final int CONTENT_TOP_PAD  = 5;
    private static final int CONTENT_BOTTOM_PAD = 5;

    // Sprite state indices
    private static final int ARROW_STATE_NORMAL   = 0;
    private static final int ARROW_STATE_HOVER    = 1;
    private static final int ARROW_STATE_DISABLED = 2;
    private static final int CLOSE_STATE_DISABLED = 3;
    private static final float HOVER_BRIGHTEN     = 1.6F;

    private int scrollOffset;

    /** Callbacks wired by parent Screen. */
    private IntConsumer onDelete;
    private IntConsumer onMoveToTop;
    private IntConsumer onMoveToBottom;
    private BiConsumer<String, String> onCancelGroup;

    // Item-icon cache: itemOrRecipeId → ItemStack
    private final Map<String, ItemStack> iconCache = new HashMap<>();

    // Hover tooltip tracking
    private ItemStack hoveredTooltipStack;
    private List<Component> hoveredTooltipLines;

    public TaskQueuePanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.literal("Task Queue"));
    }

    public void setOnDelete(IntConsumer onDelete)                             { this.onDelete = onDelete; }
    public void setOnMoveToTop(IntConsumer onMoveToTop)                       { this.onMoveToTop = onMoveToTop; }
    public void setOnMoveToBottom(IntConsumer onMoveToBottom)                 { this.onMoveToBottom = onMoveToBottom; }
    public void setOnMoveUp(IntConsumer onMoveUp)                             { this.onMoveToTop = onMoveUp; }
    public void setOnMoveDown(IntConsumer onMoveDown)                         { this.onMoveToBottom = onMoveDown; }
    public void setOnCancelGroup(BiConsumer<String, String> onCancelGroup)   { this.onCancelGroup = onCancelGroup; }

    public void setEntries(List<Entry> entries) {
        this.entries.clear();
        this.iconCache.clear();
        if (entries != null) {
            this.entries.addAll(entries);
        }
        rebuildGroups();
        this.scrollOffset = Math.min(this.scrollOffset, maxScroll());
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public void setCurrent(@Nullable CurrentInfo info) {
        setCurrents(info == null ? List.of() : List.of(info));
    }

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
        rebuildGroups();
        this.scrollOffset = Math.min(this.scrollOffset, maxScroll());
    }

    private void rebuildGroups() {
        groups.clear();
        Map<String, TaskGroup> map = new LinkedHashMap<>();

        // 1. Running tasks
        for (Current c : currents) {
            String sType = (c.entry != null && c.entry.sourceType() != null) ? c.entry.sourceType() : "player";
            String sId = (c.entry != null && c.entry.sourceId() != null) ? c.entry.sourceId() : "";
            String sName = (c.entry != null && c.entry.sourceName() != null) ? c.entry.sourceName() : "";
            String key = toGroupKey(sType, sId);
            TaskGroup g = map.computeIfAbsent(key, k -> new TaskGroup(k, sType, sId, sName));
            g.currents.add(c);
        }

        // 2. Pending tasks
        for (Entry e : entries) {
            String sType = e.sourceType() != null ? e.sourceType() : "player";
            String sId = e.sourceId() != null ? e.sourceId() : "";
            String sName = e.sourceName() != null ? e.sourceName() : "";
            String key = toGroupKey(sType, sId);
            TaskGroup g = map.computeIfAbsent(key, k -> new TaskGroup(k, sType, sId, sName));
            g.entries.add(e);
        }

        groups.addAll(map.values());
        collapsedGroups.retainAll(map.keySet());
    }

    private static String toGroupKey(String sType, String sId) {
        if ("building".equalsIgnoreCase(sType) && sId != null && !sId.isEmpty()) {
            return "building:" + sId;
        }
        if ("road".equalsIgnoreCase(sType) && sId != null && !sId.isEmpty()) {
            return "road:" + sId;
        }
        if (sType != null && !sType.isEmpty()) {
            return sType.toLowerCase();
        }
        return "player";
    }

    private static Component groupTitle(TaskGroup g) {
        String type = g.sourceType != null ? g.sourceType.toLowerCase() : "player";
        return switch (type) {
            case "building" -> {
                String name = (g.sourceName != null && !g.sourceName.isEmpty()) ? g.sourceName : "建筑";
                yield I18n.name("gui.wandscape.queue.source.building_name", "建造: %s", name);
            }
            case "road" -> I18n.name("gui.wandscape.queue.source.road", "道路施工");
            case "player" -> I18n.name("gui.wandscape.queue.source.player", "玩家请求");
            case "restock" -> I18n.name("gui.wandscape.queue.source.restock", "商店补货");
            case "auto" -> I18n.name("gui.wandscape.queue.source.auto", "自动补给");
            default -> Component.literal(g.sourceName != null && !g.sourceName.isEmpty() ? g.sourceName : type);
        };
    }

    private static int groupAccentColor(String sourceType) {
        if (sourceType == null) return 0xFF6AB0DE;
        return switch (sourceType.toLowerCase()) {
            case "building" -> 0xFFD4A840; // Medieval gold
            case "road"     -> 0xFF7CAE7A; // Earthy green
            case "player"   -> 0xFF6AB0DE; // Soft cyan
            case "restock"  -> 0xFFE5A93C; // Amber / merchant
            case "auto"     -> 0xFFAAAAAA; // Gray
            default         -> 0xFFBB86FC; // Purple
        };
    }

    private int groupHeight(TaskGroup g) {
        if (collapsedGroups.contains(g.key)) {
            return HEADER_H;
        }
        return HEADER_H + g.currents.size() * CURRENT_ROW_H + g.entries.size() * rowHeight + 2;
    }

    private int contentTop() {
        return getY() + CONTENT_TOP_PAD;
    }

    private int contentBottom() {
        return getY() + height - CONTENT_BOTTOM_PAD;
    }

    private int viewportHeight() {
        return Math.max(0, contentBottom() - contentTop());
    }

    private int contentHeight() {
        if (groups.isEmpty()) return 0;
        int h = 0;
        for (TaskGroup g : groups) {
            h += groupHeight(g) + BLOCK_GAP;
        }
        return h;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - viewportHeight());
    }

    public void tickProgress() {
        for (Current c : currents) {
            if (c.entry != null && !c.pending && c.channelTotal > 0 && c.animatedRemaining > 0) {
                c.animatedRemaining = Math.max(0, c.animatedRemaining - 1);
            }
        }
    }

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

    private static void drawProgressBar(GuiGraphics g, int x, int y, int w, int h, float frac) {
        g.fill(x, y, x + w, y + h, MedievalColors.PROGRESS_BG);
        int fw = Math.round(w * frac);
        if (fw > 0) {
            g.fill(x, y, x + fw, y + h, MedievalColors.PROGRESS_FILL);
        }
    }

    @Nullable
    private ItemStack resolveIcon(String itemOrRecipeId) {
        if (itemOrRecipeId == null || itemOrRecipeId.isBlank()) return null;
        return iconCache.computeIfAbsent(itemOrRecipeId, id -> {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) return null;
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

    private void renderIcon(GuiGraphics g, ItemStack stack, int x, int y, int cellH) {
        if (stack.isEmpty()) return;
        int iconY = y + (cellH - ICON_SIZE) / 2;
        g.renderItem(stack, x, iconY);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        hoveredTooltipStack = null;
        hoveredTooltipLines = null;

        // Background panel
        MedievalScreen.drawInsetField(g, getX(), getY(), width, height);

        if (groups.isEmpty()) {
            Component emptyText = I18n.name("gui.wandscape.queue.empty", "暂无制作任务");
            int tw = Minecraft.getInstance().font.width(emptyText);
            int tx = getX() + (width - tw) / 2;
            int ty = getY() + (height - 8) / 2;
            g.drawString(Minecraft.getInstance().font, emptyText, tx, ty, MedievalColors.TEXT_DIM);
            return;
        }

        int regionTop  = contentTop();
        int listBottom = contentBottom();
        int maxScroll  = maxScroll();
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }

        boolean scrollable = listBottom > regionTop && maxScroll > 0;
        if (scrollable) {
            g.enableScissor(getX(), regionTop, getX() + width, listBottom);
        }

        int hoverMouseY = (mouseY >= regionTop && mouseY < listBottom) ? mouseY : Integer.MIN_VALUE;

        int blockX = getX() + 3;
        int blockW = width - 6;
        int curY = regionTop - scrollOffset;

        for (TaskGroup grp : groups) {
            int gH = groupHeight(grp);
            int blockY = curY;
            curY += gH + BLOCK_GAP;

            // Frustum cull
            if (blockY + gH <= regionTop) continue;
            if (blockY >= listBottom) break;

            boolean isCollapsed = collapsedGroups.contains(grp.key);
            int accentColor = groupAccentColor(grp.sourceType);

            // Block outer frame & background
            g.fill(blockX, blockY, blockX + blockW, blockY + gH, 0x440E121B);
            g.fill(blockX, blockY, blockX + blockW, blockY + 1, 0x55384256);
            g.fill(blockX, blockY + gH - 1, blockX + blockW, blockY + gH, 0x55384256);
            g.fill(blockX + blockW - 1, blockY, blockX + blockW, blockY + gH, 0x55384256);
            // Left vertical accent stripe
            g.fill(blockX, blockY, blockX + 2, blockY + gH, accentColor);

            // Header bar
            int headerY = blockY;
            boolean headerHovered = hoverMouseY >= headerY && hoverMouseY < headerY + HEADER_H
                    && mouseX >= blockX && mouseX < blockX + blockW;
            if (headerHovered) {
                g.fill(blockX + 2, headerY, blockX + blockW - 1, headerY + HEADER_H, 0x1AFFFFFF);
            } else {
                g.fill(blockX + 2, headerY, blockX + blockW - 1, headerY + HEADER_H, 0x22000000);
            }

            // Collapse arrow: ▼ (expanded) or ▶ (collapsed)
            String arrowStr = isCollapsed ? "▶" : "▼";
            g.drawString(Minecraft.getInstance().font, arrowStr, blockX + 5, headerY + 4, accentColor);

            // Header title & count
            Component title = groupTitle(grp);
            String countStr = "(" + grp.totalCount() + ")";
            int countW = Minecraft.getInstance().font.width(countStr);
            int closeBtnX = blockX + blockW - BTN_W - 3;
            int closeBtnY = headerY + (HEADER_H - BTN_H) / 2;

            // Close button [×] on header (one-click cancel group)
            boolean closeHovered = headerHovered && mouseX >= closeBtnX && mouseX < closeBtnX + BTN_W
                    && hoverMouseY >= closeBtnY && hoverMouseY < closeBtnY + BTN_H;
            drawCloseBtn(g, closeBtnX, closeBtnY, onCancelGroup != null, mouseX, hoverMouseY, null);

            // Title max available width
            int maxTitleW = closeBtnX - countW - (blockX + 16) - 4;
            String rawTitle = title.getString();
            if (Minecraft.getInstance().font.width(rawTitle) > maxTitleW) {
                rawTitle = Minecraft.getInstance().font.plainSubstrByWidth(rawTitle, Math.max(10, maxTitleW - 8)) + "…";
            }
            g.drawString(Minecraft.getInstance().font, rawTitle, blockX + 16, headerY + 4, MedievalColors.TEXT_WARM_WHITE);
            int titleEnd = blockX + 16 + Minecraft.getInstance().font.width(rawTitle);
            g.drawString(Minecraft.getInstance().font, countStr, titleEnd + 3, headerY + 4, MedievalColors.TEXT_MUTED);

            // Header tooltip tracking
            if (headerHovered) {
                if (closeHovered) {
                    hoveredTooltipLines = List.of(I18n.name("gui.wandscape.queue.tooltip.cancel_group", "一键取消此组所有任务"));
                } else {
                    hoveredTooltipLines = List.of(I18n.name("gui.wandscape.queue.tooltip.group_summary",
                            "%s (进行中: %s, 排队: %s)", title.getString(), grp.currents.size(), grp.entries.size()));
                }
            }

            // If not collapsed, render the contained tasks
            if (!isCollapsed) {
                // Divider line under header
                g.fill(blockX + 2, headerY + HEADER_H - 1, blockX + blockW - 1, headerY + HEADER_H, 0x333A455C);

                int itemY = headerY + HEADER_H + 1;
                int innerX = blockX + 2;
                int innerW = blockW - 3;

                // Running tasks
                for (Current c : grp.currents) {
                    if (itemY + CURRENT_ROW_H > regionTop && itemY < listBottom) {
                        renderCurrentRow(g, itemY, c, innerX, innerW, mouseX, hoverMouseY);
                    }
                    itemY += CURRENT_ROW_H;
                }

                // Pending entries
                for (int i = 0; i < grp.entries.size(); i++) {
                    Entry e = grp.entries.get(i);
                    if (itemY + rowHeight > regionTop && itemY < listBottom) {
                        renderEntryRow(g, itemY, e, innerX, innerW, mouseX, hoverMouseY, i % 2 == 1);
                    }
                    itemY += rowHeight;
                }
            }
        }

        if (scrollable) {
            g.disableScissor();
            RenderUtil.drawScrollbar(g, getX() + width - 3, regionTop, 3, listBottom - regionTop,
                    contentHeight(), scrollOffset);
        }
    }

    private void renderCurrentRow(GuiGraphics g, int rowY, Current c, int rowX, int rowW, int mouseX, int mouseY) {
        g.fill(rowX, rowY, rowX + rowW, rowY + CURRENT_ROW_H - 1, 0x33283E5A);

        int contentX = rowX + CONTENT_LEFT_PAD;
        int textRight = rowX + rowW - 4;

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

        if (!c.pending) {
            int barW = Math.max(8, textRight - labelX);
            drawProgressBar(g, labelX, rowY + 13, barW, 3, progressFraction(c));
        }

        if (mouseX >= rowX && mouseX < rowX + rowW && mouseY >= rowY && mouseY < rowY + CURRENT_ROW_H) {
            if (icon != null && !icon.isEmpty()) {
                hoveredTooltipStack = icon;
            }
        }
    }

    private void renderEntryRow(GuiGraphics g, int rowY, Entry e, int rowX, int rowW, int mouseX, int mouseY, boolean isOdd) {
        if (isOdd) {
            g.fill(rowX, rowY, rowX + rowW, rowY + rowHeight - 1, 0x18FFFFFF);
        }

        int colRightStart = rowX + rowW - BTN_AREA_W - 2;
        int contentX = rowX + CONTENT_LEFT_PAD;
        int centerY  = rowY + rowHeight / 2;
        int btnY     = rowY + (rowHeight - BTN_H) / 2;

        ItemStack icon = resolveIcon(e.itemOrRecipeId);
        if (icon != null) {
            renderIcon(g, icon, contentX, rowY, rowHeight);
        }

        int labelX = contentX + ICON_SIZE + ICON_GAP;
        Component label = categoryLabel(e.category);
        g.drawString(Minecraft.getInstance().font, label, labelX, centerY - 4, MedievalColors.TEXT_DIM);

        int curX = labelX + Minecraft.getInstance().font.width(label);
        if (e.quantity > 0) {
            String qtyStr = " x" + e.quantity;
            g.drawString(Minecraft.getInstance().font, qtyStr, curX, centerY - 4, MedievalColors.TEXT_MUTED);
            curX += Minecraft.getInstance().font.width(qtyStr);
        }

        int textColEnd = colRightStart - 2;
        int statusBlockX = textColEnd;
        if (e.capacityBlocked) {
            Component shortTag = I18n.name("gui.wandscape.queue.capacity", "容量不足");
            int tagW = Minecraft.getInstance().font.width(shortTag);
            statusBlockX = textColEnd - tagW;
            if (statusBlockX >= curX + 2) {
                g.drawString(Minecraft.getInstance().font, shortTag, statusBlockX, centerY - 4, 0xFFE05040);
            }
        } else if (e.insufficient) {
            if (e.missingElements != null && !e.missingElements.isEmpty()) {
                Component shortTag = I18n.name("gui.wandscape.queue.insufficient", "缺");
                int tagW = Minecraft.getInstance().font.width(shortTag);
                int iconCount = e.missingElements.size();
                int totalBlockW = tagW + 2 + iconCount * 11 - 2;
                statusBlockX = textColEnd - totalBlockW;
                if (statusBlockX < curX + 2) statusBlockX = curX + 2;

                g.drawString(Minecraft.getInstance().font, shortTag, statusBlockX, centerY - 4, 0xFFE05040);

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
                    g.drawString(Minecraft.getInstance().font, shortTag, statusBlockX, centerY - 4, 0xFFE05040);
                }
            }
        }

        // Hover tooltip tracking
        if (mouseY >= rowY && mouseY < rowY + rowHeight) {
            if (mouseX >= rowX && mouseX < colRightStart) {
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
            } else if (mouseX >= colRightStart && mouseX < colRightStart + BTN_AREA_W && mouseY >= btnY && mouseY < btnY + BTN_H) {
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

        // Action buttons
        boolean canTop    = onMoveToTop != null    && e.index > 0;
        boolean canBottom = onMoveToBottom != null && e.index < entries.size() - 1;
        boolean canDelete = onDelete != null;

        drawToTopBtn   (g, colRightStart,                   btnY, canTop,    mouseX, mouseY);
        drawToBottomBtn(g, colRightStart + BTN_W + BTN_GAP,  btnY, canBottom, mouseX, mouseY);
        drawCloseBtn   (g, colRightStart + 2 * (BTN_W + BTN_GAP), btnY, canDelete, mouseX, mouseY, null);
    }

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

    private void drawToTopBtn(GuiGraphics g, int btnX, int btnY, boolean active, int mouseX, int mouseY) {
        int state = active
                ? (mouseX >= btnX && mouseX < btnX + BTN_W && mouseY >= btnY && mouseY < btnY + BTN_H
                    ? ARROW_STATE_HOVER : ARROW_STATE_NORMAL)
                : ARROW_STATE_DISABLED;
        renderArrow(g, btnX, btnY, state, true);
        int barColor = (state == ARROW_STATE_DISABLED) ? 0x55445068 : ((state == ARROW_STATE_HOVER) ? 0xFFFFFFFF : MedievalColors.ACCENT_GOLD);
        g.fill(btnX + 3, btnY + 2, btnX + BTN_W - 3, btnY + 3, barColor);
    }

    private void drawToBottomBtn(GuiGraphics g, int btnX, int btnY, boolean active, int mouseX, int mouseY) {
        int state = active
                ? (mouseX >= btnX && mouseX < btnX + BTN_W && mouseY >= btnY && mouseY < btnY + BTN_H
                    ? ARROW_STATE_HOVER : ARROW_STATE_NORMAL)
                : ARROW_STATE_DISABLED;
        renderArrow(g, btnX, btnY, state, false);
        int barColor = (state == ARROW_STATE_DISABLED) ? 0x55445068 : ((state == ARROW_STATE_HOVER) ? 0xFFFFFFFF : MedievalColors.ACCENT_GOLD);
        g.fill(btnX + 3, btnY + BTN_H - 3, btnX + BTN_W - 3, btnY + BTN_H - 2, barColor);
    }

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

    private void drawCloseBtn(GuiGraphics g, int btnX, int btnY, boolean active, int mouseX, int mouseY, @Nullable Runnable onPress) {
        int state = active
                ? (mouseX >= btnX && mouseX < btnX + BTN_W && mouseY >= btnY && mouseY < btnY + BTN_H ? 1 : 0)
                : CLOSE_STATE_DISABLED;
        SkinRender.drawCloseButton(g, btnX, btnY, BTN_W, BTN_H, state);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0) return false;

        int mx = (int) mouseX;
        int my = (int) mouseY;
        int regionTop = contentTop();
        int listBottom = contentBottom();

        if (my < regionTop || my >= listBottom) return false;
        if (mx < getX() || mx >= getX() + width) return false;

        int blockX = getX() + 3;
        int blockW = width - 6;
        int curY = regionTop - scrollOffset;

        for (TaskGroup grp : groups) {
            int gH = groupHeight(grp);
            int blockY = curY;
            curY += gH + BLOCK_GAP;

            if (my < blockY || my >= blockY + gH) continue;
            if (mx < blockX || mx >= blockX + blockW) continue;

            boolean isCollapsed = collapsedGroups.contains(grp.key);

            // Inside header?
            if (my < blockY + HEADER_H) {
                int closeBtnX = blockX + blockW - BTN_W - 3;
                int closeBtnY = blockY + (HEADER_H - BTN_H) / 2;
                if (mx >= closeBtnX && mx < closeBtnX + BTN_W && my >= closeBtnY && my < closeBtnY + BTN_H) {
                    if (onCancelGroup != null) {
                        onCancelGroup.accept(grp.sourceType, grp.sourceId);
                        return true;
                    }
                } else {
                    // Clicked header elsewhere -> toggle collapse!
                    if (isCollapsed) {
                        collapsedGroups.remove(grp.key);
                    } else {
                        collapsedGroups.add(grp.key);
                    }
                    scrollOffset = Math.min(scrollOffset, maxScroll());
                    return true;
                }
                return false;
            }

            // Inside expanded group content?
            if (!isCollapsed) {
                int itemY = blockY + HEADER_H + 1;
                // Skip currents (running tasks are progress-only)
                itemY += grp.currents.size() * CURRENT_ROW_H;
                int innerX = blockX + 2;
                int innerW = blockW - 3;
                int colRightStart = innerX + innerW - BTN_AREA_W - 2;

                for (Entry e : grp.entries) {
                    if (my >= itemY && my < itemY + rowHeight) {
                        int btnY = itemY + (rowHeight - BTN_H) / 2;
                        if (my >= btnY && my < btnY + BTN_H && mx >= colRightStart && mx < colRightStart + BTN_AREA_W) {
                            int col = (mx - colRightStart) / (BTN_W + BTN_GAP);
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
                                case 2 -> { // ×
                                    active = onDelete != null;
                                    action = () -> { if (active && onDelete != null) onDelete.accept(e.index); };
                                }
                                default -> {
                                    active = false;
                                    action = null;
                                }
                            }
                            if (active && action != null) {
                                action.run();
                                return true;
                            }
                        }
                        return false;
                    }
                    itemY += rowHeight;
                }
            }
            return false;
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible) return false;
        if (mouseX < getX() || mouseX >= getX() + width
                || mouseY < getY() || mouseY >= getY() + height) {
            return false;
        }
        int maxScroll = maxScroll();
        if (maxScroll <= 0) return false;
        scrollOffset = (int) Math.clamp(scrollOffset - scrollY * rowHeight * 2, 0, maxScroll);
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
