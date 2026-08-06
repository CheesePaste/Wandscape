package com.wsteam.wandscape.projection.client;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.projection.network.ProjectionPlacePacket;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.shared.ui.util.BuildingPreviewRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Construction screen — fine-tune a building placement before submitting the build task.
 *
 * <p>Opened from projection mode once the ghost preview is pinned (right-click while pinned).
 * Shows a large 3D preview, editable X/Y/Z coordinates (the world ghost moves live behind the
 * screen), and a Submit button that reuses {@link ProjectionPlacePacket} to enqueue the build.
 */
public class ConstructionScreen extends MedievalScreen {

    private static final int PW = 300;
    private static final int PH = 230;

    private static final int PREVIEW_X = 10;
    private static final int PREVIEW_Y = 26;
    private static final int PREVIEW_W = 280;
    private static final int PREVIEW_H = 108;

    private static final int LABEL_X = 16;
    private static final int BOX_X = 36;
    private static final int BOX_W = 150;
    private static final int BOX_H = 16;
    private static final int ROW_Y = 140;
    private static final int ROW_GAP = 20;
    private static final int STATUS_Y = 196;
    private static final int SUBMIT_Y = 206;

    private final BuildingConfig config;
    private final String buildingTypeId;
    private final int rotationSteps;
    private final int defaultX;
    private final int defaultY;
    private final int defaultZ;

    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private Component status;

    public ConstructionScreen(BuildingConfig config, String buildingTypeId,
                              BlockPos pinPos, int rotationSteps) {
        super(I18n.name("gui.wandscape.construction.title", "施工"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.construction.title", "施工"));
        this.showCloseButton = true;
        this.config = config;
        this.buildingTypeId = buildingTypeId;
        this.rotationSteps = rotationSteps;
        this.defaultX = pinPos.getX();
        this.defaultY = pinPos.getY();
        this.defaultZ = pinPos.getZ();
    }

    @Override
    protected void init() {
        super.init();
        xBox = makeBox(0, String.valueOf(defaultX));
        yBox = makeBox(1, String.valueOf(defaultY));
        zBox = makeBox(2, String.valueOf(defaultZ));

        addRenderableWidget(new MedievalButton(
                leftPos + 20, topPos + SUBMIT_Y, 260, 20,
                I18n.name("gui.wandscape.construction.submit", "Submit 建造"), this::submit));
    }

    private EditBox makeBox(int row, String value) {
        EditBox box = new EditBox(font, leftPos + BOX_X, topPos + ROW_Y + row * ROW_GAP,
                BOX_W, BOX_H, I18n.name("gui.wandscape.construction.coord", "坐标"));
        box.setValue(value);
        box.setMaxLength(9);
        box.setBordered(false);
        box.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        box.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        box.setCanLoseFocus(true);
        box.setFilter(s -> s.matches("-?\\d{0,9}"));
        box.setResponder(this::onCoordChanged);
        addRenderableWidget(box);
        return box;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        renderMinimalHeader(g);
        renderCloseButton(g, mouseX, mouseY);

        // Coordinate labels + inset fields
        for (int row = 0; row < 3; row++) {
            int y = topPos + ROW_Y + row * ROW_GAP;
            String label = switch (row) {
                case 0 -> "X";
                case 1 -> "Y";
                default -> "Z";
            };
            g.drawString(font, label, leftPos + LABEL_X, y + (BOX_H - font.lineHeight) / 2,
                    MedievalColors.ACCENT_GOLD);
            drawInsetField(g, leftPos + BOX_X, y, BOX_W, BOX_H);
        }

        // Flush GUI batch so the 3D preview renders on top of the glass panel
        g.bufferSource().endBatch(RenderType.gui());
        BuildingPreviewRenderer.renderPreview(g, config,
                leftPos + PREVIEW_X, topPos + PREVIEW_Y, PREVIEW_W, PREVIEW_H);

        if (status != null) {
            g.drawCenteredString(font, status, leftPos + PW / 2, topPos + STATUS_Y, 0xFFE06060);
        }

        for (Renderable renderable : this.renderables) {
            renderable.render(g, mouseX, mouseY, partialTick);
        }
    }

    /** Live-update the pinned world ghost as the user edits coordinates. */
    private void onCoordChanged(String s) {
        BlockPos pos = parsePos();
        if (pos == null) {
            status = null;
            return;
        }
        ProjectionClientState.setGhostPos(pos);
        BuildingApi api = WandscapeApis.getBuildingApi();
        boolean overlap = api != null && api.getBuildingAt(pos) != null;
        ProjectionClientState.setOverlapDetected(overlap);
        status = overlap ? I18n.name("gui.wandscape.construction.overlap", "该位置与现有建筑重叠") : null;
    }

    private void submit() {
        BlockPos pos = parsePos();
        if (pos == null) {
            status = I18n.name("gui.wandscape.construction.invalid", "坐标无效（Y 需在 -64 ~ 320）");
            return;
        }
        BuildingApi api = WandscapeApis.getBuildingApi();
        if (api != null && api.getBuildingAt(pos) != null) {
            status = I18n.name("gui.wandscape.construction.overlap", "该位置与现有建筑重叠");
            return;
        }

        PacketDistributor.sendToServer(new ProjectionPlacePacket(buildingTypeId, pos, rotationSteps));
        ProjectionClientState.setPinned(false);

        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);
        // Back to the building selection bar for the next placement
        WandscapePanelState.openBuildingBar();
    }

    private BlockPos parsePos() {
        try {
            int x = Integer.parseInt(xBox.getValue().trim());
            int y = Integer.parseInt(yBox.getValue().trim());
            int z = Integer.parseInt(zBox.getValue().trim());
            if (y < -64 || y > 320) return null;
            return new BlockPos(x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
