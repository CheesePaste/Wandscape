package com.wsteam.wandscape.shared.ui.component;

import com.mojang.blaze3d.systems.RenderSystem;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.skin.SkinRender;
import com.wsteam.wandscape.shared.ui.skin.SkinSprite;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.shared.ui.theme.WandscapeTheme;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            List<String> missingElements
    ) {
        /** Legacy constructor kept for backward compatibility. */
        public Entry(int index, String blueprintId, String summary) {
            this(index, categorize(blueprintId), extractItemId(blueprintId, summary), 0, blueprintId, summary, false, List.of());
        }

        private static String categorize(String bid) {
            return switch (bid) {
                case "production:decompose" -> "decompose";
                case "production:synthesize" -> "synthesize";
                case "production:craft_wand" -> "craft";
                case "production:craft_spell" -> "transcribe";
                case "production:brew_potion" -> "brew";
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
    private java.util.function.IntConsumer onMoveUp;
    private java.util.function.IntConsumer onMoveDown;

    // Item-icon cache: itemOrRecipeId → ItemStack (or null if not found)
    private final Map<String, ItemStack> iconCache = new HashMap<>();

    // Layout constants
    private static final int ICON_SIZE   = 12;   // icon cell: 12×12 px
    private static final int ICON_GAP    = 2;    // gap between icon and label
    private static final int BTN_W       = 14;
    private static final int BTN_H       = 14;
    private static final int BTN_GAP     = 1;
    // 3 buttons × 14 + 2 gaps × 1 = 44px right margin
    private static final int BTN_AREA_W  = 3 * BTN_W + 2 * BTN_GAP;
    // Left padding for text content
    private static final int CONTENT_LEFT_PAD = 4;

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

    public void setOnDelete(java.util.function.IntConsumer onDelete)       { this.onDelete = onDelete; }
    public void setOnMoveUp(java.util.function.IntConsumer onMoveUp)        { this.onMoveUp = onMoveUp; }
    public void setOnMoveDown(java.util.function.IntConsumer onMoveDown)    { this.onMoveDown = onMoveDown; }

    /**
     * Replace all entries. Call from the parent Screen when new queue data arrives.
     */
    public void setEntries(List<Entry> entries) {
        this.entries.clear();
        this.iconCache.clear();
        if (entries != null) {
            this.entries.addAll(entries);
        }
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
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
        if (infos == null) return;
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
        int iconY = y + (rowHeight - ICON_SIZE) / 2 + 1;
        g.renderItem(stack, x, iconY);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        // Background panel
        SkinRender.drawPanel9Slice(g, SkinSprite.PANEL_B, getX(), getY(), width, height);

        int topPadding   = 4;
        int rightPad     = 4;  // padding between button area and panel right edge
        int colRightStart = getX() + width - BTN_AREA_W - rightPad;

        // Row area
        int textY     = getY() + topPadding + 10;
        int listBottom = getY() + height - 4; // 4px bottom padding

        // ── Current (executing) tasks — top rows, locked, each with a progress bar ──
        int rowStartY = textY;
        for (int i = 0; i < currents.size(); i++) {
            renderCurrentRow(g, textY + i * CURRENT_ROW_H, currents.get(i));
            rowStartY = textY + (i + 1) * CURRENT_ROW_H;
        }

        for (int row = 0; row < entries.size(); row++) {
            int rowBaseY = rowStartY + row * rowHeight;
            if (rowBaseY + rowHeight > listBottom) break;

            Entry e = entries.get(row);

            // Alternating row background
            if (row % 2 == 1) {
                g.fill(getX() + 1, rowBaseY, getX() + width - 1, rowBaseY + rowHeight - 1, 0x22FFFFFF);
            }

            int contentX = getX() + CONTENT_LEFT_PAD;
            int centerY  = rowBaseY + rowHeight / 2;

            // ── Icon ──
            ItemStack icon = resolveIcon(e.itemOrRecipeId);
            if (icon != null) {
                renderIcon(g, icon, contentX, rowBaseY, rowHeight);
            }

            // ── Category label + quantity ──
            int labelX = contentX + ICON_SIZE + ICON_GAP;
            Component label = categoryLabel(e.category);
            g.drawString(Minecraft.getInstance().font, label,
                    labelX, centerY - 4, MedievalColors.TEXT_DIM);

            // ── Insufficient marker: dark-red "缺元素" tag + missing element icons ──
            int textColEnd = colRightStart - 2;
            if (e.insufficient) {
                Component shortTag = I18n.name("gui.wandscape.queue.insufficient", "缺元素");
                int tagX = labelX + Minecraft.getInstance().font.width(label) + 2;
                int tagW = Minecraft.getInstance().font.width(shortTag);
                g.drawString(Minecraft.getInstance().font, shortTag,
                        tagX, centerY - 4, MedievalColors.TEXT_DIM);
                int iconX = tagX + tagW + 2;
                for (String el : e.missingElements) {
                    if (iconX + 11 > textColEnd) break; // clip at quantity column
                    ResourceLocation ico = WandscapeTheme.elementIcon(el);
                    if (ico != null) {
                        WandscapeTheme.drawIcon(g, ico, iconX, centerY - 5, 9, 9, WandscapeTheme.elementColor(el));
                        iconX += 11;
                    }
                }
            }

            // Quantity right-aligned in the text column (left of buttons)
            if (e.quantity > 0) {
                String qtyStr = "x" + e.quantity;
                int qtyW = Minecraft.getInstance().font.width(qtyStr);
                g.drawString(Minecraft.getInstance().font, qtyStr,
                        textColEnd - qtyW, centerY - 4, MedievalColors.TEXT_MUTED);
            }

            // ── Action buttons ──
            int btnY = rowBaseY + (rowHeight - BTN_H) / 2;

            boolean canUp    = onMoveUp != null    && e.index > 0;
            boolean canDown  = onMoveDown != null  && e.index < entries.size() - 1;
            boolean canDelete = onDelete != null;

            drawUpBtn  (g, colRightStart,                  btnY, canUp,    mouseX, mouseY,
                        () -> { if (canUp    && onMoveUp != null)    onMoveUp.accept(e.index);    });
            drawDownBtn(g, colRightStart + BTN_W + BTN_GAP, btnY, canDown,  mouseX, mouseY,
                        () -> { if (canDown  && onMoveDown != null)  onMoveDown.accept(e.index);  });
            drawCloseBtn(g,colRightStart + 2*(BTN_W+BTN_GAP),btnY, canDelete, mouseX, mouseY,
                        () -> { if (canDelete && onDelete != null)   onDelete.accept(e.index);    });
        }
    }

    /** Draw a locked current-task row: icon + label + remaining time + progress bar. */
    private void renderCurrentRow(GuiGraphics g, int rowY, Current c) {
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
        g.drawString(Minecraft.getInstance().font, categoryLabel(c.entry.category()), labelX, rowY + 2, MedievalColors.ACCENT_GOLD);

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

    private void drawUpBtn(GuiGraphics g, int btnX, int btnY,
                           boolean active, int mouseX, int mouseY, Runnable onPress) {
        int state = active
                ? (mouseX >= btnX && mouseX < btnX + BTN_W && mouseY >= btnY && mouseY < btnY + BTN_H
                    ? ARROW_STATE_HOVER
                    : ARROW_STATE_NORMAL)
                : ARROW_STATE_DISABLED;
        renderArrow(g, btnX, btnY, state, true);
        storePressAction(btnX, btnY, active, onPress);
    }

    private void drawDownBtn(GuiGraphics g, int btnX, int btnY,
                             boolean active, int mouseX, int mouseY, Runnable onPress) {
        int state = active
                ? (mouseX >= btnX && mouseX < btnX + BTN_W && mouseY >= btnY && mouseY < btnY + BTN_H
                    ? ARROW_STATE_HOVER
                    : ARROW_STATE_NORMAL)
                : ARROW_STATE_DISABLED;
        renderArrow(g, btnX, btnY, state, false);
        storePressAction(btnX, btnY, active, onPress);
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
        storePressAction(btnX, btnY, active, onPress);
    }

    // ── Click handling ─────────────────────────────────────────────────────

    /** Store the last active button's action (used by legacy hit-test path, kept for safety). */
    private Runnable lastPressAction;
    private void storePressAction(int btnX, int btnY, boolean active, Runnable onPress) {
        if (active) this.lastPressAction = onPress;
    }

    /**
     * Hit-test: find which active button the mouse is over and fire its action.
     * Scans rows bottom-up so the last rendered visible row takes priority when overlapping.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0) return false;

        int mx = (int) mouseX;
        int my = (int) mouseY;
        int topPadding = 4;
        int textY     = getY() + topPadding + 10;
        int listBottom = getY() + height - 4;
        int rowStartY = textY + currents.size() * CURRENT_ROW_H;

        for (int row = entries.size() - 1; row >= 0; row--) {
            int rowBaseY = rowStartY + row * rowHeight;
            if (rowBaseY + rowHeight < textY || rowBaseY > listBottom) continue;

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
                case 0 -> { // ↑
                    active = onMoveUp != null && e.index > 0;
                    action = () -> { if (active && onMoveUp != null) onMoveUp.accept(e.index); };
                }
                case 1 -> { // ↓
                    active = onMoveDown != null && e.index < entries.size() - 1;
                    action = () -> { if (active && onMoveDown != null) onMoveDown.accept(e.index); };
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

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
