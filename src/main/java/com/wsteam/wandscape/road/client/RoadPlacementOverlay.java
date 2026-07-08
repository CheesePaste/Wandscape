package com.wsteam.wandscape.road.client;

import java.util.List;

import org.joml.Quaternionf;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.wsteam.wandscape.road.data.RoadPreset;
import com.wsteam.wandscape.shared.ui.util.BuildingPreviewRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Preset selection overlay for road placement mode.
 *
 * <p>Mirrors the Build panel's {@code BuildingSelectionOverlay} layout:
 * a grid of preset cells with 3D block preview thumbnails, names,
 * hover/selection highlighting, positioned above the bottom tab bar.
 */
public final class RoadPlacementOverlay {

    private static final int PANEL_H = 100;
    static final int CELL_W = 44;
    static final int CELL_H = 44;
    static final int CELL_GAP = 4;
    private static final int GRID_PAD_X = 12;
    private static final int GRID_PAD_TOP = 10;

    private static final int PANEL_BG = 0xEE14161C;
    private static final int PANEL_BORDER = 0xFF3A3E47;
    private static final int CELL_BG = 0xFF1C1F26;
    private static final int CELL_SELECTED_BG = 0xFF2B62C8;
    private static final int CELL_HOVER_BG = 0xFF282C34;
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFAAAAAA;

    private static final int FULL_BRIGHT = 0xF000F0;

    private RoadPlacementOverlay() {}

    // ── Active check ──

    /** The overlay is shown when road placement is active with cursor lifted in panel. */
    public static boolean isActive() {
        return RoadPlacementState.isProjecting();
    }

    // ── Rendering ──

    public static void render(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        if (!RoadPlacementState.isProjecting()) return;

        List<RoadPreset> presets = RoadPlacementState.getPresets();
        int selectedIdx = RoadPlacementState.getSelectedPresetIndex();
        if (presets.isEmpty()) return;

        // Grid layout — auto-cols by screen width
        int toolsWidth = 100;
        int gridAreaW = screenW - GRID_PAD_X * 2 - toolsWidth;
        int cols = Math.max(1, gridAreaW / (CELL_W + CELL_GAP));
        int rows = (presets.size() + cols - 1) / cols;
        int gridW = cols * (CELL_W + CELL_GAP) - CELL_GAP;
        int gridH = rows * (CELL_H + CELL_GAP) - CELL_GAP;
        int panelH = GRID_PAD_TOP + Math.max(gridH, 74) + 10; // Ensure enough height for 2 buttons
        int panelY = screenH - com.wsteam.wandscape.shared.ui.panel.WandscapePanelController.BOTTOM_BAR_HEIGHT - panelH;
        int gridX = toolsWidth + GRID_PAD_X + (gridAreaW - gridW) / 2;
        int gridStartY = panelY + GRID_PAD_TOP;

        // Panel background + top border
        g.fill(RenderType.guiOverlay(), 0, panelY, screenW, panelY + panelH, 0, PANEL_BG);
        g.fill(RenderType.guiOverlay(), 0, panelY, screenW, panelY + 1, 0, PANEL_BORDER);

        // Pass 1: cell backgrounds
        for (int i = 0; i < presets.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cellX = gridX + col * (CELL_W + CELL_GAP);
            int cellY = gridStartY + row * (CELL_H + CELL_GAP);

            boolean selected = i == selectedIdx;
            boolean hovered = mx >= cellX && mx <= cellX + CELL_W
                    && my >= cellY && my <= cellY + CELL_H;

            int bg = selected ? CELL_SELECTED_BG : (hovered ? CELL_HOVER_BG : CELL_BG);
            g.fill(RenderType.guiOverlay(), cellX, cellY, cellX + CELL_W, cellY + CELL_H, 0, bg);
            if (selected) {
                g.fill(RenderType.guiOverlay(), cellX, cellY + CELL_H - 2,
                        cellX + CELL_W, cellY + CELL_H, 0, 0xFFC8A040);
            }
        }

        // Draw left side tool buttons
        int toolsStartX = GRID_PAD_X;
        int toolsStartY = gridStartY;
        int btnW = 84;
        int btnH = 32;

        // Button 1: Square Fill (Active)
        boolean btn1Hover = mx >= toolsStartX && mx <= toolsStartX + btnW && my >= toolsStartY && my <= toolsStartY + btnH;
        int btn1Bg = btn1Hover ? CELL_HOVER_BG : CELL_SELECTED_BG;
        g.fill(RenderType.guiOverlay(), toolsStartX, toolsStartY, toolsStartX + btnW, toolsStartY + btnH, 0, btn1Bg);
        g.fill(RenderType.guiOverlay(), toolsStartX, toolsStartY + btnH - 2, toolsStartX + btnW, toolsStartY + btnH, 0, 0xFF4FA0FF);
        font.drawInBatch("Square Fill", toolsStartX + (btnW - font.width("Square Fill")) / 2f, toolsStartY + 12, TEXT_WHITE, false,
                g.pose().last().pose(), g.bufferSource(), Font.DisplayMode.SEE_THROUGH, 0, FULL_BRIGHT);

        // Button 2: Spline Pen
        int btn2Y = toolsStartY + btnH + 10;
        boolean btn2Hover = mx >= toolsStartX && mx <= toolsStartX + btnW && my >= btn2Y && my <= btn2Y + btnH;
        int btn2Bg = btn2Hover ? CELL_HOVER_BG : CELL_BG;
        g.fill(RenderType.guiOverlay(), toolsStartX, btn2Y, toolsStartX + btnW, btn2Y + btnH, 0, btn2Bg);
        font.drawInBatch("Spline Pen", toolsStartX + (btnW - font.width("Spline Pen")) / 2f, btn2Y + 12, TEXT_DIM, false,
                g.pose().last().pose(), g.bufferSource(), Font.DisplayMode.SEE_THROUGH, 0, FULL_BRIGHT);

        // Flush backgrounds before 3D preview
        g.bufferSource().endBatch(RenderType.guiOverlay());

        // Pass 2: 3D block preview thumbnails + labels
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();

        for (int i = 0; i < presets.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cellX = gridX + col * (CELL_W + CELL_GAP);
            int cellY = gridStartY + row * (CELL_H + CELL_GAP);

            RoadPreset preset = presets.get(i);
            boolean selected = i == selectedIdx;

            // 3D isometric block preview
            renderBlockPreview(blockRenderer, g, preset, cellX, cellY);

            // Label
            String name = preset.displayName();
            int nameW = font.width(name);
            if (nameW > CELL_W - 4) {
                while (font.width(name + ".") > CELL_W - 8 && name.length() > 1) {
                    name = name.substring(0, name.length() - 1);
                }
                name = name + ".";
            }
            int nameX = cellX + (CELL_W - font.width(name)) / 2;
            int nameY = cellY + CELL_H - 11;
            font.drawInBatch(name, nameX, nameY, selected ? TEXT_WHITE : TEXT_DIM, false,
                    g.pose().last().pose(), g.bufferSource(),
                    Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        }
    }

    // ── 3D block preview ──

    /**
     * Renders a single block as a small isometric 3D preview in the cell.
     * Uses {@link BlockRenderDispatcher#renderSingleBlock} with auto-rotation.
     */
    private static void renderBlockPreview(BlockRenderDispatcher blockRenderer,
                                            GuiGraphics g, RoadPreset preset,
                                            int cellX, int cellY) {
        String blockId = preset.pickBlock(0, 0);
        BlockState state = BuildingPreviewRenderer.resolveBlockState(blockId);
        if (state == null) return;

        MultiBufferSource.BufferSource bufferSource = g.bufferSource();
        PoseStack pose = g.pose();

        g.flush();

        pose.pushPose();

        // Center in cell, push Z forward for depth
        pose.translate(cellX + CELL_W / 2f, cellY + CELL_H / 2f - 2, 200);

        // Scale for single block preview
        float scale = 22f;
        pose.scale(scale, -scale, scale);

        // Isometric tilt + auto-rotation (full rotation every 10 seconds)
        float rotY = (System.currentTimeMillis() % 10000) / 10000f * (float) (Math.PI * 2);
        pose.mulPose(new Quaternionf().rotateX((float) Math.toRadians(30)));
        pose.mulPose(new Quaternionf().rotateY(rotY));

        // Shift from block-corner to block-center
        pose.translate(-0.5, -0.5, -0.5);

        RenderSystem.enableDepthTest();
        Lighting.setupFor3DItems();

        blockRenderer.renderSingleBlock(state, pose, bufferSource,
                FULL_BRIGHT, OverlayTexture.NO_OVERLAY);

        bufferSource.endBatch();

        pose.popPose();

        // Restore flat GUI rendering
        Lighting.setupForFlatItems();
        RenderSystem.disableDepthTest();
    }

    // ── Hit detection ──

    /** Returns the preset index at mouse coordinates, or -1. */
    public static int getPresetAt(double mx, double my, int screenW, int screenH) {
        if (!RoadPlacementState.isProjecting()) return -1;

        List<RoadPreset> presets = RoadPlacementState.getPresets();
        if (presets.isEmpty()) return -1;

        int toolsWidth = 100;
        int gridAreaW = screenW - GRID_PAD_X * 2 - toolsWidth;
        int cols = Math.max(1, gridAreaW / (CELL_W + CELL_GAP));
        int rows = (presets.size() + cols - 1) / cols;
        int gridW = cols * (CELL_W + CELL_GAP) - CELL_GAP;
        int gridH = rows * (CELL_H + CELL_GAP) - CELL_GAP;
        int panelH = GRID_PAD_TOP + Math.max(gridH, 74) + 10;
        int panelY = screenH - com.wsteam.wandscape.shared.ui.panel.WandscapePanelController.BOTTOM_BAR_HEIGHT - panelH;
        int gridX = toolsWidth + GRID_PAD_X + (gridAreaW - gridW) / 2;
        int gridStartY = panelY + GRID_PAD_TOP;

        if (my < gridStartY || my > gridStartY + gridH) return -1;
        int col = (int) ((mx - gridX) / (CELL_W + CELL_GAP));
        int row = (int) ((my - gridStartY) / (CELL_H + CELL_GAP));
        if (mx < gridX || mx > gridX + gridW) return -1;
        int idx = row * cols + col;
        if (idx >= 0 && idx < presets.size()) return idx;
        return -1;
    }

    public static boolean isSplinePenClicked(double mx, double my, int screenW, int screenH) {
        if (!RoadPlacementState.isProjecting()) return false;

        List<RoadPreset> presets = RoadPlacementState.getPresets();
        if (presets.isEmpty()) return false;

        int toolsWidth = 100;
        int gridAreaW = screenW - GRID_PAD_X * 2 - toolsWidth;
        int cols = Math.max(1, gridAreaW / (CELL_W + CELL_GAP));
        int rows = (presets.size() + cols - 1) / cols;
        int gridH = rows * (CELL_H + CELL_GAP) - CELL_GAP;
        int panelH = GRID_PAD_TOP + Math.max(gridH, 74) + 10;
        int panelY = screenH - com.wsteam.wandscape.shared.ui.panel.WandscapePanelController.BOTTOM_BAR_HEIGHT - panelH;

        int toolsStartX = GRID_PAD_X;
        int toolsStartY = panelY + GRID_PAD_TOP;
        int btn2Y = toolsStartY + 32 + 10;
        
        return mx >= toolsStartX && mx <= toolsStartX + 84 && my >= btn2Y && my <= btn2Y + 32;
    }
}
