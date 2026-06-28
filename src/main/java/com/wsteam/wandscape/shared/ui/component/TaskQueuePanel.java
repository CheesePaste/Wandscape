package com.wsteam.wandscape.shared.ui.component;

import com.wsteam.wandscape.shared.ui.skin.SkinRender;
import com.wsteam.wandscape.shared.ui.skin.SkinSprite;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

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
 * <p>Index 0 (current task) is locked — all buttons disabled.
 * Index 1 cannot move up; last index cannot move down.
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
            String summary
    ) {
        /** Legacy constructor kept for backward compatibility. */
        public Entry(int index, String blueprintId, String summary) {
            this(index, categorize(blueprintId), extractItemId(blueprintId, summary), 0, blueprintId, summary);
        }

        private static String categorize(String bid) {
            return switch (bid) {
                case "production:decompose" -> "decompose";
                case "production:synthesize" -> "synthesize";
                case "production:craft_wand" -> "craft";
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

    private final List<Entry> entries = new ArrayList<>();
    private final int rowHeight = 16;

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

        // Title
        g.drawString(Minecraft.getInstance().font, "Queue",
                getX() + CONTENT_LEFT_PAD, getY() + topPadding, MedievalColors.TEXT_WARM_WHITE, false);

        // Row area
        int textY     = getY() + topPadding + 10;
        int listBottom = getY() + height - 4; // 4px bottom padding

        for (int row = 0; row < entries.size(); row++) {
            if (textY + rowHeight > listBottom) break;

            Entry e = entries.get(row);
            boolean isCurrent = (e.index == 0);
            int rowBaseY = textY + row * rowHeight;

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
            String label = categoryLabel(e.category);
            int labelColor = isCurrent ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_DIM;
            g.drawString(Minecraft.getInstance().font, label,
                    labelX, centerY - 4, labelColor);

            // Quantity right-aligned in the text column (left of buttons)
            int textColEnd = colRightStart - 2;
            if (e.quantity > 0) {
                String qtyStr = "x" + e.quantity;
                int qtyW = Minecraft.getInstance().font.width(qtyStr);
                g.drawString(Minecraft.getInstance().font, qtyStr,
                        textColEnd - qtyW, centerY - 4, MedievalColors.TEXT_MUTED);
            }

            // ── Action buttons ──
            int btnY = rowBaseY + (rowHeight - BTN_H) / 2;

            boolean canUp    = !isCurrent && onMoveUp != null    && e.index > 1;
            boolean canDown  = !isCurrent && onMoveDown != null  && e.index < entries.size() - 1;
            boolean canDelete = !isCurrent && onDelete != null;

            drawUpBtn  (g, colRightStart,                  btnY, canUp,    mouseX, mouseY,
                        () -> { if (canUp    && onMoveUp != null)    onMoveUp.accept(e.index);    });
            drawDownBtn(g, colRightStart + BTN_W + BTN_GAP, btnY, canDown,  mouseX, mouseY,
                        () -> { if (canDown  && onMoveDown != null)  onMoveDown.accept(e.index);  });
            drawCloseBtn(g,colRightStart + 2*(BTN_W+BTN_GAP),btnY, canDelete, mouseX, mouseY,
                        () -> { if (canDelete && onDelete != null)   onDelete.accept(e.index);    });
        }
    }

    /**
     * Map internal category key to a short display label.
     * Keep strings short so they fit on one line with the icon.
     */
    private static String categoryLabel(String cat) {
        return switch (cat) {
            case "decompose" -> "Decompose";
            case "synthesize" -> "Synthesize";
            case "craft"      -> "Craft Wand";
            case "brew"       -> "Brew";
            case "build"      -> "Build";
            case "gather"     -> "Gather";
            default           -> cat;
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
        SkinRender.drawUpArrow(g, btnX, btnY, BTN_W, BTN_H, state);
        storePressAction(btnX, btnY, active, onPress);
    }

    private void drawDownBtn(GuiGraphics g, int btnX, int btnY,
                             boolean active, int mouseX, int mouseY, Runnable onPress) {
        int state = active
                ? (mouseX >= btnX && mouseX < btnX + BTN_W && mouseY >= btnY && mouseY < btnY + BTN_H
                    ? ARROW_STATE_HOVER
                    : ARROW_STATE_NORMAL)
                : ARROW_STATE_DISABLED;
        SkinRender.drawDownArrow(g, btnX, btnY, BTN_W, BTN_H, state);
        storePressAction(btnX, btnY, active, onPress);
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

        for (int row = entries.size() - 1; row >= 0; row--) {
            int rowBaseY = textY + row * rowHeight;
            if (rowBaseY + rowHeight < textY || rowBaseY > listBottom) continue;

            Entry e = entries.get(row);
            boolean isCurrent = (e.index == 0);
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
                    active = !isCurrent && onMoveUp != null && e.index > 1;
                    action = () -> { if (active && onMoveUp != null) onMoveUp.accept(e.index); };
                }
                case 1 -> { // ↓
                    active = !isCurrent && onMoveDown != null && e.index < entries.size() - 1;
                    action = () -> { if (active && onMoveDown != null) onMoveDown.accept(e.index); };
                }
                default -> { // ×
                    active = !isCurrent && onDelete != null;
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
