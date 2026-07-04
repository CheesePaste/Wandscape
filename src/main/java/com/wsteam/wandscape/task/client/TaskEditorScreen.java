package com.wsteam.wandscape.task.client;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.shared.data.BlueprintInfo;
import com.wsteam.wandscape.shared.data.ParamTypeInfo;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.task.network.TaskCreatePacket;
import com.wsteam.wandscape.task.network.TaskEditorOpenPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
/**
 * Task editor GUI — allows players to browse blueprints, edit parameters,
 * and publish tasks to the colony.
 *
 * <p>Extends {@link MedievalScreen} for the parchment/gold theme.
 *
 * <p>Two-phase layout:
 * <ol>
 *   <li>Loading state: blueprint list empty, shows spinner text</li>
 *   <li>Ready state: list + params + priority + publish</li>
 * </ol>
 * Blueprint list arrives asynchronously via network; the screen
 * detects the transition in {@link #tick()} and rebuilds widgets.
 */
public class TaskEditorScreen extends MedievalScreen {

    // ── Layout constants ──

    private static final int BP_LIST_H = 100;
    private static final int PARAM_AREA_Y = BP_LIST_H + 6;
    private static final int PARAM_ROW_H = 20;
    private static final int LABEL_W = 80;
    private static final int INPUT_W = 200;
    private static final int INPUT_H = 16;
    private static final int PRIORITY_GAP = 10;
    private static final int PUBLISH_GAP = 28;

    // ── State ──

    private boolean widgetsBuilt = false;
    private final List<EditBox> paramInputs = new java.util.ArrayList<>();
    private EditBox priorityInput;
    private MedievalButton publishButton;

    public TaskEditorScreen() {
        super(Component.literal(""), 320, 400);
        setTitleBar("Task Editor");
    }

    // ════════════════════════════════════════════════════════════
    //  Init
    // ════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        super.init();

        // Close button
        var closeBtn = new MedievalButton(
                leftPos + panelWidth - 26, topPos + 3, 20, 16,
                Component.literal("X"),
                this::onClose);
        addRenderableWidget(closeBtn);

        // If blueprints already cached (e.g. screen was closed/reopened quickly)
        if (!TaskEditorClientState.getBlueprints().isEmpty()) {
            buildEditorWidgets();
        }

        // Request fresh list from server
        PacketDistributor.sendToServer(new TaskEditorOpenPacket());
    }

    // ════════════════════════════════════════════════════════════
    //  Tick — detect async blueprint arrival
    // ════════════════════════════════════════════════════════════

    @Override
    public void tick() {
        super.tick();
        // If blueprints arrived after init and widgets aren't built yet, build now
        if (!widgetsBuilt && !TaskEditorClientState.getBlueprints().isEmpty()) {
            buildEditorWidgets();
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Build editor widgets (called once blueprints are available)
    // ════════════════════════════════════════════════════════════

    private void buildEditorWidgets() {
        if (widgetsBuilt) return;
        widgetsBuilt = true;

        // Blueprint list
        int listX = leftPos + 10;
        int listY = topPos + headerHeight;
        var bpList = new ScrollableList<BlueprintInfo>(
                listX, listY, panelWidth - 20, BP_LIST_H, 16) {
            @Override
            protected void renderRow(GuiGraphics g, BlueprintInfo item,
                                     int x, int y, int index,
                                     boolean selected, boolean hovered) {
                int color = selected ? MedievalColors.ACCENT_GOLD
                        : (hovered ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED);
                g.drawString(font, item.displayName(), x + 2, y + 3, color);
                if (item.description() != null && !item.description().isEmpty()
                        && selected) {
                    g.drawString(font, "   " + item.description(), x + 2, y + 11,
                            MedievalColors.TEXT_DIM);
                }
            }
        };
        bpList.setItems(TaskEditorClientState.getBlueprints());
        bpList.setOnSelect(this::onBlueprintSelected);
        addRenderableWidget(bpList);

        // Param inputs
        rebuildParamInputs();

        // Priority input
        int paramCount = getParamInputCount();
        int priY = topPos + headerHeight + PARAM_AREA_Y
                + paramCount * PARAM_ROW_H + PRIORITY_GAP;
        int priX = leftPos + 10 + LABEL_W;

        priorityInput = new EditBox(font, priX, priY, INPUT_W, INPUT_H,
                Component.literal("priority"));
        priorityInput.setValue(String.valueOf(TaskEditorClientState.getDraftPriority()));
        priorityInput.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        addRenderableWidget(priorityInput);

        // Publish button
        int btnY = priY + PARAM_ROW_H + PUBLISH_GAP;
        publishButton = new MedievalButton(
                leftPos + panelWidth / 2 - 60, btnY, 120, 20,
                Component.literal("Publish"),
                this::onPublish);
        addRenderableWidget(publishButton);
    }

    // ════════════════════════════════════════════════════════════
    //  Blueprint selection
    // ════════════════════════════════════════════════════════════

    private void onBlueprintSelected(int index) {
        List<BlueprintInfo> bps = TaskEditorClientState.getBlueprints();
        if (index >= 0 && index < bps.size()) {
            TaskEditorClientState.setSelectedBlueprint(bps.get(index));
            rebuildParamInputs();
            if (priorityInput != null) {
                priorityInput.setValue(String.valueOf(TaskEditorClientState.getDraftPriority()));
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Dynamic param inputs
    // ════════════════════════════════════════════════════════════

    private int getParamInputCount() {
        BlueprintInfo bp = TaskEditorClientState.getSelectedBlueprint();
        return bp != null && bp.params() != null ? bp.params().size() : 0;
    }

    private void rebuildParamInputs() {
        for (EditBox box : paramInputs) {
            removeWidget(box);
        }
        paramInputs.clear();

        BlueprintInfo bp = TaskEditorClientState.getSelectedBlueprint();
        if (bp == null || bp.params() == null || bp.params().isEmpty()) {
            return;
        }

        int startY = topPos + headerHeight + PARAM_AREA_Y;
        int idx = 0;
        for (var entry : bp.params().entrySet()) {
            String key = entry.getKey();
            ParamTypeInfo type = entry.getValue();

            int y = startY + idx * PARAM_ROW_H;
            String hint = key + " (" + type.name().toLowerCase() + ")";

            EditBox box = new EditBox(font,
                    leftPos + 10 + LABEL_W, y,
                    INPUT_W, INPUT_H,
                    Component.literal(key));
            box.setHint(Component.literal(hint));
            box.setMaxLength(256);

            String currentValue = TaskEditorClientState.getDraftParams().get(key);
            if (currentValue != null) {
                box.setValue(currentValue);
            }

            addRenderableWidget(box);
            paramInputs.add(box);
            idx++;
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Publish
    // ════════════════════════════════════════════════════════════

    private void onPublish() {
        BlueprintInfo bp = TaskEditorClientState.getSelectedBlueprint();
        if (bp == null) {
            sendFeedback("[Wandscape] Select a blueprint first");
            return;
        }

        // Collect params from input fields into a mutable copy
        Map<String, String> draftParams = new java.util.LinkedHashMap<>(
                TaskEditorClientState.getDraftParams());
        int i = 0;
        for (var entry : bp.params().entrySet()) {
            if (i < paramInputs.size()) {
                draftParams.put(entry.getKey(), paramInputs.get(i).getValue());
            }
            i++;
        }

        // Validate required params
        if (bp.params() != null) {
            for (String key : bp.params().keySet()) {
                String val = draftParams.get(key);
                if (val == null || val.isEmpty()) {
                    sendFeedback("[Wandscape] Missing param: " + key);
                    return;
                }
            }
        }

        // Parse priority
        int priority;
        try {
            priority = Integer.parseInt(priorityInput.getValue());
        } catch (NumberFormatException e) {
            priority = TaskEditorClientState.getDraftPriority();
        }
        TaskEditorClientState.setDraftPriority(priority);

        // Send packet
        TaskCreatePacket packet = new TaskCreatePacket(bp.id(), draftParams, priority);
        PacketDistributor.sendToServer(packet);

        sendFeedback("[Wandscape] Publishing '" + bp.id() + "'...");
        closeScreen();
    }

    private void sendFeedback(String msg) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(
                    Component.literal(msg));
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Close
    // ════════════════════════════════════════════════════════════

    private void closeScreen() {
        Minecraft.getInstance().setScreen((Screen) null);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            onClose();
            return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════
    //  Render — labels + loading state
    // ════════════════════════════════════════════════════════════

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        if (!widgetsBuilt) {
            // Loading state
            int cx = leftPos + panelWidth / 2;
            int cy = topPos + headerHeight + BP_LIST_H / 2;
            g.drawCenteredString(font, "Loading blueprints...", cx, cy,
                    MedievalColors.TEXT_MUTED);
            return;
        }

        // Draw param labels and type hints
        BlueprintInfo bp = TaskEditorClientState.getSelectedBlueprint();
        if (bp != null && bp.params() != null && !bp.params().isEmpty()) {
            int startY = topPos + headerHeight + PARAM_AREA_Y;
            int idx = 0;
            for (var entry : bp.params().entrySet()) {
                int y = startY + idx * PARAM_ROW_H + 4;
                g.drawString(font, entry.getKey() + ":", leftPos + 10, y,
                        MedievalColors.TEXT_MUTED);
                g.drawString(font, entry.getValue().name().toLowerCase(),
                        leftPos + 10, y + 8, MedievalColors.TEXT_DIM);
                idx++;
            }
        }

        // Draw priority label
        int paramCount = getParamInputCount();
        int priY = topPos + headerHeight + PARAM_AREA_Y
                + paramCount * PARAM_ROW_H + PRIORITY_GAP;
        g.drawString(font, "Priority:", leftPos + 10, priY + 3,
                MedievalColors.TEXT_MUTED);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
