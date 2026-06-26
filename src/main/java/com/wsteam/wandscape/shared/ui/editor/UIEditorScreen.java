package com.wsteam.wandscape.shared.ui.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.ui.component.*;
import com.wsteam.wandscape.shared.ui.skin.SkinSprite;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/**
 * In-game UI layout editor. Press U to open.
 * <ul>
 * <li>Drag widgets to reposition</li>
 * <li>Drag resize handles (8-point) to resize</li>
 * <li>Component palette to add new widgets</li>
 * <li>Delete key or button to remove widgets</li>
 * <li>Arrow keys nudge selected widget</li>
 * <li>Grid snap + save/load layout JSON</li>
 * </ul>
 */
public class UIEditorScreen extends MedievalScreen {

    private static final int PW = 360;
    private static final int PH = 250;

    // ── Resize handle types ──
    private enum Handle { NW, N, NE, E, SE, S, SW, W }

    private static final int HANDLE_SIZE = 5;
    private static final int MIN_WIDGET_SIZE = 8;

    // ── Editable widget entry ──
    private static class EditableEntry {
        final String id;
        final String type;
        final AbstractWidget widget;
        int dragOffsetX, dragOffsetY;
        int resizeStartX, resizeStartY;
        int resizeStartW, resizeStartH;

        EditableEntry(String id, String type, AbstractWidget widget) {
            this.id = id;
            this.type = type;
            this.widget = widget;
        }
    }

    // ── Palette template ──
    private record PaletteEntry(String type, String label, Supplier<AbstractWidget> factory) {}

    private final List<EditableEntry> entries = new ArrayList<>();
    private final List<PaletteEntry> paletteDefs = new ArrayList<>();
    private final List<MedievalButton> paletteBtns = new ArrayList<>();
    private int nextId = 1;

    private EditableEntry selected;
    private EditableEntry dragging;
    private Handle resizeHandle;
    private boolean draggingWidget;

    // ── Control buttons ──
    private MedievalButton saveBtn;
    private MedievalButton loadBtn;
    private MedievalButton resetBtn;
    private MedievalButton gridBtn;
    private MedievalButton deleteBtn;
    private boolean snapToGrid = true;

    private SearchBar layoutNameInput;
    private ScrollableList<String> layoutList;

    private String propLabel = "";
    private String propPos = "";
    private String propSize = "";

    public UIEditorScreen() {
        super(Component.literal("UI Layout Editor"), PW, PH);
        setTitleBar("UI Editor");
        headerHeight = 20;
    }

    @Override
    protected void init() {
        super.init();

        int canvasX = leftPos + 6;
        int canvasY = topPos + headerHeight + 3;
        int canvasW = PW - 140;
        int canvasH = PH - headerHeight - 6;
        int sideX = leftPos + canvasW + 4;
        int sideW = PW - canvasW - 10;

        // ── Build palette definitions ──
        buildPalette();

        // ── Seed initial widgets ──
        entries.clear();
        addEntry("button", "MedievalButton", new MedievalButton(canvasX + 10, canvasY + 8, 90, 18,
                Component.literal("Button"), () -> {}));
        addEntry("icon_close", "IconButton", new IconButton(canvasX + 110, canvasY + 5, 22, "X",
                MedievalColors.DANGER_RED, Component.literal("Close"), () -> {}, SkinSprite.CLOSE_BTN));
        addEntry("search_bar", "SearchBar", new SearchBar(canvasX + 10, canvasY + 35, canvasW - 24, 14, "Search...", s -> {}));
        addEntry("slider", "Slider", new Slider(canvasX + 10, canvasY + 58, 140, 1, 64, 32, v -> {}));
        addEntry("progress", "ProgressIndicator", new ProgressIndicator(canvasX + 10, canvasY + 85, 140, 14, 0.65f));

        // ── Side panel: Palette (widget buttons, top of side panel) ──
        paletteBtns.clear();
        int palY = canvasY + 1;
        for (int i = 0; i < paletteDefs.size(); i++) {
            PaletteEntry pe = paletteDefs.get(i);
            MedievalButton btn = new MedievalButton(sideX + 2, palY + i * 10, sideW - 4, 9,
                    Component.literal(pe.label()), () -> addFromPalette(pe));
            paletteBtns.add(btn);
            addRenderableWidget(btn);
        }
        int paletteEnd = palY + paletteDefs.size() * 10 + 2;

        // ── Properties (compact, 1 line) ──
        int propsEnd = paletteEnd + 12;

        // ── Layout name input ──
        int inputY = propsEnd + 2;
        layoutNameInput = new SearchBar(sideX + 2, inputY, sideW - 4, 13, "layout_name", s -> {});
        addRenderableWidget(layoutNameInput);

        // ── Action buttons (compact rows) ──
        int btnY = inputY + 16;
        int btnW = (sideW - 6) / 2;
        saveBtn = new MedievalButton(sideX + 2, btnY, btnW, 12, Component.literal("Save"), () -> doSave());
        loadBtn = new MedievalButton(sideX + 2 + btnW + 2, btnY, btnW, 12, Component.literal("Load"), () -> doLoad());
        addRenderableWidget(saveBtn);
        addRenderableWidget(loadBtn);

        int btnY2 = btnY + 14;
        resetBtn = new MedievalButton(sideX + 2, btnY2, btnW, 12, Component.literal("Reset"), () -> doReset());
        gridBtn = new MedievalButton(sideX + 2 + btnW + 2, btnY2, btnW, 12,
                Component.literal(snapToGrid ? "Grid:ON" : "Grid:OFF"),
                () -> { snapToGrid = !snapToGrid; gridBtn.setMessage(
                        Component.literal(snapToGrid ? "Grid:ON" : "Grid:OFF")); });
        addRenderableWidget(resetBtn);
        addRenderableWidget(gridBtn);

        int btnY3 = btnY2 + 14;
        deleteBtn = new MedievalButton(sideX + 2, btnY3, sideW - 4, 12,
                Component.literal("Delete Sel"), () -> doDelete());
        deleteBtn.active = false;
        addRenderableWidget(deleteBtn);

        // ── Saved layouts list (remaining space) ──
        int listY = btnY3 + 16;
        int listH = canvasY + canvasH - listY - 2;
        if (listH > 20) {
            layoutList = new ScrollableList<>(sideX + 2, listY, sideW - 4, listH, 10) {
                @Override
                protected void renderRow(GuiGraphics g, String item, int x, int y, int index,
                                          boolean selected, boolean hovered) {
                    int color = selected ? MedievalColors.ACCENT_GOLD
                            : hovered ? MedievalColors.TEXT_WARM_WHITE
                            : MedievalColors.TEXT_MUTED;
                    g.drawString(Minecraft.getInstance().font, item, x, y, color);
                }
            };
            layoutList.setItems(UILayoutManager.listLayouts());
            layoutList.setOnSelect(idx -> {
                String name = layoutList.getSelected();
                if (name != null) layoutNameInput.setValue(name);
            });
            addRenderableWidget(layoutList);
        }
    }

    private void addFromPalette(PaletteEntry pe) {
        int canvasX = leftPos + 6;
        int canvasY = topPos + headerHeight + 3;
        AbstractWidget w = pe.factory().get();
        w.setX(canvasX + 10 + (nextId % 4) * 24);
        w.setY(canvasY + 10 + (nextId % 4) * 28);
        String entryId = pe.type().toLowerCase().replace(" ", "_") + "_" + nextId;
        nextId++;
        addEntry(entryId, pe.type(), w);
        selected = entries.get(entries.size() - 1);
        deleteBtn.active = true;
        updateProperties();
    }

    private void buildPalette() {
        paletteDefs.clear();
        paletteDefs.add(new PaletteEntry("MedievalButton", "+Button",
                () -> new MedievalButton(0, 0, 100, 20, Component.literal("Button"), () -> {})));
        paletteDefs.add(new PaletteEntry("IconButton", "+Icon",
                () -> new IconButton(0, 0, 24, "X", MedievalColors.DANGER_RED,
                        Component.literal("Close"), () -> {}, SkinSprite.CLOSE_BTN)));
        paletteDefs.add(new PaletteEntry("HelpButton", "+Help",
                () -> new HelpButton(0, 0, 24, 24, () -> {})));
        paletteDefs.add(new PaletteEntry("OptionButton", "+Option",
                () -> new OptionButton(0, 0, 24, 24, () -> {})));
        paletteDefs.add(new PaletteEntry("LessButton", "+Less",
                () -> new LessButton(0, 0, () -> {})));
        paletteDefs.add(new PaletteEntry("MoreButton", "+More",
                () -> new MoreButton(0, 0, () -> {})));
        paletteDefs.add(new PaletteEntry("LeftArrow", "+LArrow",
                () -> new LeftArrowButton(0, 0, () -> {})));
        paletteDefs.add(new PaletteEntry("RightArrow", "+RArrow",
                () -> new RightArrowButton(0, 0, () -> {})));
        paletteDefs.add(new PaletteEntry("SearchBar", "+Search",
                () -> new SearchBar(0, 0, 160, 16, "Search...", s -> {})));
        paletteDefs.add(new PaletteEntry("Slider", "+Slider",
                () -> new Slider(0, 0, 160, 1, 64, 32, v -> {})));
        paletteDefs.add(new PaletteEntry("ProgressIndicator", "+Progress",
                () -> {
                    var p = new ProgressIndicator(0, 0, 160, 16, 0.5f);
                    p.setLabel("50%");
                    return p;
                }));
    }

    public void addEntry(String id, String type, AbstractWidget widget) {
        entries.add(new EditableEntry(id, type, widget));
        addRenderableWidget(widget);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int canvasX = leftPos + 6;
        int canvasY = topPos + headerHeight + 3;
        int canvasW = PW - 140;
        int canvasH = PH - headerHeight - 6;
        int sideX = leftPos + canvasW + 4;
        int sideW = PW - canvasW - 10;

        // Canvas border
        g.renderOutline(canvasX, canvasY, canvasW, canvasH, MedievalColors.BORDER_GOLD);

        // Selection + resize handles
        if (selected != null) {
            AbstractWidget w = selected.widget;
            int sx = w.getX(), sy = w.getY(), sw = w.getWidth(), sh = w.getHeight();

            // Selection highlight
            g.renderOutline(sx - 1, sy - 1, sw + 2, sh + 2, MedievalColors.ACCENT_GOLD);

            // Resize handles (8-point)
            drawHandle(g, sx - HANDLE_SIZE / 2, sy - HANDLE_SIZE / 2, mouseX, mouseY, Handle.NW);
            drawHandle(g, sx + sw / 2 - HANDLE_SIZE / 2, sy - HANDLE_SIZE / 2, mouseX, mouseY, Handle.N);
            drawHandle(g, sx + sw - HANDLE_SIZE / 2, sy - HANDLE_SIZE / 2, mouseX, mouseY, Handle.NE);
            drawHandle(g, sx + sw - HANDLE_SIZE / 2, sy + sh / 2 - HANDLE_SIZE / 2, mouseX, mouseY, Handle.E);
            drawHandle(g, sx + sw - HANDLE_SIZE / 2, sy + sh - HANDLE_SIZE / 2, mouseX, mouseY, Handle.SE);
            drawHandle(g, sx + sw / 2 - HANDLE_SIZE / 2, sy + sh - HANDLE_SIZE / 2, mouseX, mouseY, Handle.S);
            drawHandle(g, sx - HANDLE_SIZE / 2, sy + sh - HANDLE_SIZE / 2, mouseX, mouseY, Handle.SW);
            drawHandle(g, sx - HANDLE_SIZE / 2, sy + sh / 2 - HANDLE_SIZE / 2, mouseX, mouseY, Handle.W);
        }

        // Hover highlight (non-selected)
        for (EditableEntry entry : entries) {
            if (entry != selected && entry.widget.isMouseOver(mouseX, mouseY)) {
                AbstractWidget w = entry.widget;
                g.renderOutline(w.getX() - 1, w.getY() - 1, w.getWidth() + 2, w.getHeight() + 2,
                        MedievalColors.INFO_BLUE);
                break;
            }
        }

        // Side panel background
        g.fill(sideX, canvasY, sideX + sideW, canvasY + canvasH, MedievalColors.PARCHMENT_BG);
        g.renderOutline(sideX, canvasY, sideW, canvasH, MedievalColors.BORDER_GOLD_DARK);

        // ── Palette label + border ──
        int palEnd = canvasY + 1 + paletteDefs.size() * 10 + 2;
        g.drawString(font, "Add:", sideX + 3, canvasY + 1, MedievalColors.ACCENT_GOLD);
        g.renderOutline(sideX + 1, canvasY + 11, sideW - 2, palEnd - canvasY - 11, MedievalColors.BORDER_GOLD_DARK);

        // ── Property panel ──
        int propY = palEnd + 3;
        g.drawString(font, "Sel:", sideX + 3, propY, MedievalColors.ACCENT_GOLD);
        g.drawString(font, propLabel, sideX + 22, propY, MedievalColors.TEXT_WARM_WHITE);
        propY += 10;
        g.drawString(font, propPos, sideX + 3, propY, MedievalColors.TEXT_MUTED);
        g.drawString(font, propSize, sideX + 3 + sideW / 2, propY, MedievalColors.TEXT_MUTED);

        // ── Grid dots ──
        if (snapToGrid) {
            for (int gx = canvasX + 4; gx < canvasX + canvasW; gx += 8) {
                for (int gy = canvasY + 4; gy < canvasY + canvasH; gy += 8) {
                    g.fill(gx, gy, gx + 1, gy + 1, 0x20FFFFFF);
                }
            }
        }
    }

    private void drawHandle(GuiGraphics g, int hx, int hy, int mouseX, int mouseY, Handle handle) {
        boolean hovered = mouseX >= hx && mouseX < hx + HANDLE_SIZE
                && mouseY >= hy && mouseY < hy + HANDLE_SIZE;
        int color = hovered ? MedievalColors.ACCENT_GOLD : MedievalColors.BORDER_GOLD;
        g.fill(hx, hy, hx + HANDLE_SIZE, hy + HANDLE_SIZE, color);
    }

    // ── Mouse ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int mouseXi = (int) mouseX;
        int mouseYi = (int) mouseY;

        // 1. Check resize handles on selected widget
        if (selected != null) {
            Handle h = hitHandle(mouseXi, mouseYi, selected.widget);
            if (h != null) {
                resizeHandle = h;
                selected.resizeStartX = mouseXi;
                selected.resizeStartY = mouseYi;
                selected.resizeStartW = selected.widget.getWidth();
                selected.resizeStartH = selected.widget.getHeight();
                return true;
            }
        }

        // 3. Check widget clicks (reverse order = top-most)
        for (int i = entries.size() - 1; i >= 0; i--) {
            EditableEntry entry = entries.get(i);
            if (entry.widget.isMouseOver(mouseX, mouseY)) {
                selected = entry;
                dragging = entry;
                draggingWidget = true;
                entry.dragOffsetX = mouseXi - entry.widget.getX();
                entry.dragOffsetY = mouseYi - entry.widget.getY();
                deleteBtn.active = true;
                updateProperties();
                return true;
            }
        }

        // Click outside — deselect
        selected = null;
        dragging = null;
        draggingWidget = false;
        resizeHandle = null;
        deleteBtn.active = false;
        updateProperties();
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        int mouseXi = (int) mouseX;
        int mouseYi = (int) mouseY;

        if (resizeHandle != null && selected != null && button == 0) {
            resizeWidget(mouseXi, mouseYi);
            updateProperties();
            return true;
        }

        if (draggingWidget && dragging != null && button == 0) {
            int newX = mouseXi - dragging.dragOffsetX;
            int newY = mouseYi - dragging.dragOffsetY;

            if (snapToGrid) {
                newX = (newX / 4) * 4;
                newY = (newY / 4) * 4;
            }

            int canvasX = leftPos + 8;
            int canvasY = topPos + headerHeight + 4;
            int canvasW = PW - 140;
            int canvasH = PH - headerHeight - 6;
            newX = Math.clamp(newX, canvasX + 1, canvasX + canvasW - dragging.widget.getWidth() - 1);
            newY = Math.clamp(newY, canvasY + 1, canvasY + canvasH - dragging.widget.getHeight() - 1);

            dragging.widget.setX(newX);
            dragging.widget.setY(newY);
            updateProperties();
            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = null;
        draggingWidget = false;
        resizeHandle = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ── Resize logic ──

    private Handle hitHandle(int mx, int my, AbstractWidget w) {
        int sx = w.getX(), sy = w.getY(), sw = w.getWidth(), sh = w.getHeight();
        if (hitRect(mx, my, sx - HANDLE_SIZE / 2, sy - HANDLE_SIZE / 2)) return Handle.NW;
        if (hitRect(mx, my, sx + sw / 2 - HANDLE_SIZE / 2, sy - HANDLE_SIZE / 2)) return Handle.N;
        if (hitRect(mx, my, sx + sw - HANDLE_SIZE / 2, sy - HANDLE_SIZE / 2)) return Handle.NE;
        if (hitRect(mx, my, sx + sw - HANDLE_SIZE / 2, sy + sh / 2 - HANDLE_SIZE / 2)) return Handle.E;
        if (hitRect(mx, my, sx + sw - HANDLE_SIZE / 2, sy + sh - HANDLE_SIZE / 2)) return Handle.SE;
        if (hitRect(mx, my, sx + sw / 2 - HANDLE_SIZE / 2, sy + sh - HANDLE_SIZE / 2)) return Handle.S;
        if (hitRect(mx, my, sx - HANDLE_SIZE / 2, sy + sh - HANDLE_SIZE / 2)) return Handle.SW;
        if (hitRect(mx, my, sx - HANDLE_SIZE / 2, sy + sh / 2 - HANDLE_SIZE / 2)) return Handle.W;
        return null;
    }

    private boolean hitRect(int mx, int my, int rx, int ry) {
        return mx >= rx && mx < rx + HANDLE_SIZE && my >= ry && my < ry + HANDLE_SIZE;
    }

    private void resizeWidget(int mx, int my) {
        AbstractWidget w = selected.widget;
        int rsx = selected.resizeStartX, rsy = selected.resizeStartY;
        int rsw = selected.resizeStartW, rsh = selected.resizeStartH;
        int dx = mx - rsx;
        int dy = my - rsy;

        int newX = w.getX(), newY = w.getY(), newW = rsw, newH = rsh;

        switch (resizeHandle) {
            case NW -> { newX = w.getX() + dx; newY = w.getY() + dy; newW = rsw - dx; newH = rsh - dy; }
            case N  -> { newY = w.getY() + dy; newH = rsh - dy; }
            case NE -> { newY = w.getY() + dy; newW = rsw + dx; newH = rsh - dy; }
            case E  -> { newW = rsw + dx; }
            case SE -> { newW = rsw + dx; newH = rsh + dy; }
            case S  -> { newH = rsh + dy; }
            case SW -> { newX = w.getX() + dx; newW = rsw - dx; newH = rsh + dy; }
            case W  -> { newX = w.getX() + dx; newW = rsw - dx; }
        }

        if (snapToGrid) {
            newX = (newX / 4) * 4;
            newY = (newY / 4) * 4;
            newW = Math.max(MIN_WIDGET_SIZE, (newW / 4) * 4);
            newH = Math.max(MIN_WIDGET_SIZE, (newH / 4) * 4);
        } else {
            newW = Math.max(MIN_WIDGET_SIZE, newW);
            newH = Math.max(MIN_WIDGET_SIZE, newH);
        }

        int canvasX = leftPos + 6;
        int canvasY = topPos + headerHeight + 3;
        int canvasW = PW - 140;
        int canvasH = PH - headerHeight - 6;

        w.setX(Math.clamp(newX, canvasX + 1, canvasX + canvasW - MIN_WIDGET_SIZE));
        w.setY(Math.clamp(newY, canvasY + 1, canvasY + canvasH - MIN_WIDGET_SIZE));
        w.setWidth(Math.clamp(newW, MIN_WIDGET_SIZE, canvasX + canvasW - w.getX()));
        w.setHeight(Math.clamp(newH, MIN_WIDGET_SIZE, canvasY + canvasH - w.getY()));
    }

    // ── Keyboard ──

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 261 && selected != null) { // Delete key
            doDelete();
            return true;
        }
        if (selected != null) {
            int step = snapToGrid ? 4 : 1;
            switch (keyCode) {
                case 263 -> { selected.widget.setX(selected.widget.getX() - step); updateProperties(); return true; }
                case 262 -> { selected.widget.setX(selected.widget.getX() + step); updateProperties(); return true; }
                case 265 -> { selected.widget.setY(selected.widget.getY() - step); updateProperties(); return true; }
                case 264 -> { selected.widget.setY(selected.widget.getY() + step); updateProperties(); return true; }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── Properties ──

    private void updateProperties() {
        if (selected != null) {
            AbstractWidget w = selected.widget;
            propLabel = selected.id + " (" + selected.type + ")";
            propPos = "X: " + w.getX() + "  Y: " + w.getY();
            propSize = "W: " + w.getWidth() + "  H: " + w.getHeight();
        } else {
            propLabel = "(none)";
            propPos = "";
            propSize = "";
        }
    }

    // ── Actions ──

    private void doDelete() {
        if (selected == null) return;
        removeWidget(selected.widget);
        entries.remove(selected);
        selected = null;
        deleteBtn.active = false;
        updateProperties();
    }

    private void doSave() {
        String name = layoutNameInput.getValue().trim();
        if (name.isEmpty()) { layoutNameInput.setValue("untitled"); name = "untitled"; }
        List<WidgetLayout> widgets = new ArrayList<>();
        for (EditableEntry entry : entries) {
            AbstractWidget w = entry.widget;
            widgets.add(new WidgetLayout(entry.id, w.getX(), w.getY(), w.getWidth(), w.getHeight()));
        }
        UILayoutManager.save(new WidgetLayout.ScreenLayout(name, PW, PH, widgets));
        if (layoutList != null) layoutList.setItems(UILayoutManager.listLayouts());
    }

    private void doLoad() {
        String name = layoutNameInput.getValue().trim();
        if (name.isEmpty()) return;
        WidgetLayout.ScreenLayout layout = UILayoutManager.load(name);
        if (layout == null) return;

        // Clear all existing widgets
        for (EditableEntry entry : entries) {
            removeWidget(entry.widget);
        }
        entries.clear();
        selected = null;
        deleteBtn.active = false;

        // Recreate from layout
        for (WidgetLayout wl : layout.widgets()) {
            AbstractWidget w = createWidgetById(wl.id());
            if (w != null) {
                w.setX(wl.x());
                w.setY(wl.y());
                if (wl.width() > 0) w.setWidth(wl.width());
                if (wl.height() > 0) w.setHeight(wl.height());
                addEntry(wl.id(), wl.id(), w);
            }
        }
        updateProperties();
    }

    private void doReset() {
        for (EditableEntry entry : entries) removeWidget(entry.widget);
        entries.clear();
        selected = null;
        deleteBtn.active = false;
        nextId = 1;
        init();
        updateProperties();
    }

    private AbstractWidget createWidgetById(String id) {
        String base = id.replaceAll("_\\d+$", ""); // strip trailing _N
        for (PaletteEntry pe : paletteDefs) {
            if (pe.type().toLowerCase().replace(" ", "_").equals(base)) return pe.factory().get();
        }
        // Fallback heuristics
        if (base.contains("button")) return new MedievalButton(0, 0, 100, 20, Component.literal("Button"), () -> {});
        if (base.contains("icon")) return new IconButton(0, 0, 24, "X", MedievalColors.DANGER_RED, Component.literal(""), () -> {}, SkinSprite.CLOSE_BTN);
        if (base.contains("help")) return new HelpButton(0, 0, 24, 24, () -> {});
        if (base.contains("option")) return new OptionButton(0, 0, 24, 24, () -> {});
        if (base.contains("less")) return new LessButton(0, 0, () -> {});
        if (base.contains("more")) return new MoreButton(0, 0, () -> {});
        if (base.contains("left")) return new LeftArrowButton(0, 0, () -> {});
        if (base.contains("right")) return new RightArrowButton(0, 0, () -> {});
        if (base.contains("search")) return new SearchBar(0, 0, 160, 16, "Search...", s -> {});
        if (base.contains("slider")) return new Slider(0, 0, 160, 1, 64, 32, v -> {});
        if (base.contains("progress")) { var p = new ProgressIndicator(0, 0, 160, 16, 0.5f); p.setLabel("50%"); return p; }
        if (base.contains("element")) { var ep = new ElementPanel(0, 0, 160); Map<ElementType, Long> s = new LinkedHashMap<>(); for (ElementType et : ElementType.values()) s.put(et, 0L); ep.setElements(s); return ep; }
        return null;
    }
}
