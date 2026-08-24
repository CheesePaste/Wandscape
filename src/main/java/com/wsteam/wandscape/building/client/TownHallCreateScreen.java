package com.wsteam.wandscape.building.client;

import com.wsteam.wandscape.shared.network.ColonyCreateRequestPacket;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * "Name your colony" screen shown when an intact town hall is right-clicked
 * but no colony exists yet. Entering a name and confirming sends a
 * {@link ColonyCreateRequestPacket} to the server, which creates the colony
 * (reusing the {@code /wandscape colony create} logic) and links the town hall.
 */
public class TownHallCreateScreen extends MedievalScreen {

    private static final int PW = 300;
    private static final int PH = 150;

    private final BlockPos townHallAnchor;

    private EditBox nameBox;
    private String pendingName = "";
    private boolean confirmed = false;

    public TownHallCreateScreen(BlockPos townHallAnchor, String creator) {
        super(Component.literal("Create Town"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.townhall_create.title", "创建魔法小镇"));
        this.showCloseButton = true;
        this.townHallAnchor = townHallAnchor;
        setCreator(creator);
    }

    @Override
    protected void init() {
        super.init();
        int cx = leftPos + PW / 2;
        int ebY = topPos + headerHeight + 26;

        nameBox = new EditBox(font, cx - 90, ebY, 180, font.lineHeight + 4,
                I18n.name("gui.wandscape.townhall_create.name_label", "魔法小镇名称"));
        nameBox.setMaxLength(30);
        nameBox.setBordered(false);
        nameBox.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        nameBox.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        nameBox.setCanLoseFocus(false);

        String defaultName = I18n.name("gui.wandscape.townhall_create.default_name", "魔法小镇").getString();
        nameBox.setValue(defaultName);
        nameBox.setHighlightPos(0);
        pendingName = defaultName;

        addRenderableWidget(nameBox);
        setFocused(nameBox);
    }

    private void confirm() {
        if (confirmed) return;
        String name = pendingName != null ? pendingName.trim() : "";
        if (name.isEmpty()) {
            name = I18n.name("gui.wandscape.townhall_create.default_name", "魔法小镇").getString();
        }
        confirmed = true;
        PacketDistributor.sendToServer(new ColonyCreateRequestPacket(townHallAnchor, name));
        this.onClose();
    }

    @Override
    public void onClose() {
        if (!confirmed) {
            String name = pendingName != null ? pendingName.trim() : "";
            if (name.isEmpty()) {
                name = I18n.name("gui.wandscape.townhall_create.default_name", "魔法小镇").getString();
            }
            confirmed = true;
            PacketDistributor.sendToServer(new ColonyCreateRequestPacket(townHallAnchor, name));
        }
        super.onClose();
        // 确保清理建造栏和子模式，防止关闭弹窗后因光标抬起与建造栏开启导致 WASD / 视角冻结
        com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.closeBuildingBar();
        com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.exitCurrentSubMode();
        if (com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isPanelOpen()) {
            com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.setSubMode(
                    com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.SubMode.NONE);
        }
        com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.syncCursorToState();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.mouseHandler != null && !com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.isCursorLifted()) {
            mc.mouseHandler.grabMouse();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isConfirmHit(mouseX, mouseY)) {
            confirm();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isConfirmHit(double mx, double my) {
        int cx = leftPos + PW / 2;
        int bw = 120, bh = 24;
        int bx = cx - bw / 2;
        int by = topPos + headerHeight + 74;
        return isInRect(mx, my, bx, by, bw, bh);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        renderMinimalHeader(g);
        renderCloseButton(g, mouseX, mouseY);
        renderContent(g, mouseX, mouseY);

        for (Renderable r : this.renderables) {
            r.render(g, mouseX, mouseY, partialTick);
        }

        renderCreatorFooter(g);
    }

    private void renderContent(GuiGraphics g, int mouseX, int mouseY) {
        int cx = leftPos + PW / 2;

        // Title / instruction
        String title = I18n.name("gui.wandscape.townhall_create.prompt",
                "你的市政厅已建成，但还没有魔法小镇").getString();
        g.drawString(font, title, cx - font.width(title) / 2,
                topPos + headerHeight + 10, MedievalColors.TEXT_WARM_WHITE);

        // Name input field (inset)
        int ebX = cx - 92;
        int ebY = topPos + headerHeight + 24;
        drawInsetField(g, ebX, ebY, 184, font.lineHeight + 6);

        // Hint
        String hint = I18n.name("gui.wandscape.townhall_create.name_hint", "输入魔法小镇名称").getString();
        g.drawString(font, hint, cx - font.width(hint) / 2, ebY + font.lineHeight + 14,
                MedievalColors.TEXT_MUTED);

        // Confirm button
        int bw = 120, bh = 24;
        int bx = cx - bw / 2;
        int by = topPos + headerHeight + 72;
        boolean hover = isInRect(mouseX, mouseY, bx, by, bw, bh);
        drawMinimalBox(g, bx, by, bw, bh, pendingName.trim().isEmpty() ? false : hover, hover);
        String label = I18n.name("gui.wandscape.townhall_create.create", "创建").getString();
        g.drawString(font, label, cx - font.width(label) / 2,
                by + (bh - font.lineHeight) / 2, MedievalColors.TEXT_WARM_WHITE);
    }

    @Override
    public void tick() {
        super.tick();
        if (nameBox != null) {
            String current = nameBox.getValue();
            if (!current.equals(pendingName)) {
                pendingName = current;
            }
        }
    }
}
