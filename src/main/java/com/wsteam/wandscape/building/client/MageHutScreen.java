package com.wsteam.wandscape.building.client;

import java.util.List;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.network.MageHutActionPacket;
import com.wsteam.wandscape.building.network.MageHutDataPacket;
import com.wsteam.wandscape.building.network.MageHutDataPacket.MageCandidate;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.data.MageHutAttributes;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Mage Hut panel — manage the single resident mage of a mage hut.
 *
 * <p>Shows a 3D preview, per-attribute progression rows
 * ({@code base/upper + level + equipment}), a right-hand attribute-train
 * selector, and action buttons (equip / strategy / level up / rest). When the
 * hut is empty it shows the assignable colony mages; when the mage is dead the
 * action buttons are disabled (the hut stays occupied).
 */
public class MageHutScreen extends MedievalScreen {

    private static final int PW = 380;
    private static final int PH = 254;

    // ── Packet snapshot (mutated on apply — the same screen handles multiple huts) ──
    private BlockPos buildingPos;
    private java.util.UUID colonyId;
    private int colonyLevel;
    private boolean hasResident;
    private boolean alive;
    private boolean resting;
    private String mageName;
    private int mageLevel;
    private int skinVariant;
    private float[] base = new float[7];
    private float[] equip = new float[7];
    private List<MageCandidate> candidates;

    private int selectedCandidate = 0;
    private int selectedTrain = 0;

    private WandscapeNpc previewNpc;

    public MageHutScreen(MageHutDataPacket packet) {
        super(Component.literal("Mage Hut"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.mage_hut.title", "Mage Hut"));
        this.showCloseButton = true;
        this.candidates = packet.candidates();
        applyData(packet);
    }

    /** Smoothly update data from a fresh server sync packet without reopening. */
    public void apply(MageHutDataPacket packet) {
        applyData(packet);
        clearWidgets();
        init();
    }

    private void applyData(MageHutDataPacket packet) {
        this.buildingPos = packet.buildingPos();
        this.colonyId = packet.colonyId();
        this.colonyLevel = packet.colonyLevel();
        this.hasResident = packet.hasResident();
        this.alive = packet.alive();
        this.resting = packet.resting();
        this.mageName = packet.mageName();
        this.mageLevel = packet.mageLevel();
        this.skinVariant = packet.skinVariant();
        this.base = packet.base() != null ? packet.base() : new float[7];
        this.equip = packet.equipBonus() != null ? packet.equipBonus() : new float[7];
        this.candidates = packet.candidates();
        setCreator(packet.creator());
        if (selectedTrain >= MageHutAttributes.ORDER.size()) selectedTrain = 0;
        if (selectedCandidate >= candidates.size()) selectedCandidate = Math.max(0, candidates.size() - 1);
        rebuildPreviewNpc();
    }

    private void rebuildPreviewNpc() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !hasResident || !alive) {
            previewNpc = null;
            return;
        }
        previewNpc = new WandscapeNpc(Wandscape.WANDSCAPE_NPC.get(), mc.level);
        previewNpc.guiDisplayMode = true;
        previewNpc.setSkinVariant(skinVariant >= 0 ? skinVariant : 0);
        previewNpc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Wandscape.WAND.get()));
    }

    private boolean canOperate() {
        return hasResident && alive;
    }

    @Override
    protected void init() {
        super.init();
        rebuildPreviewNpc();

        int contentTop = topPos + headerHeight + 6;

        if (!hasResident) {
            initEmpty(contentTop);
        } else {
            initOccupied(contentTop);
        }

        // Close button at bottom right
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 20, 44, 16,
                I18n.name("gui.wandscape.common.close", "Close"), this::onClose));
    }

    private void initEmpty(int contentTop) {
        // Assignable colony mage list
        int lx = leftPos + 12;
        int ly = contentTop + 8;
        for (int i = 0; i < candidates.size(); i++) {
            MageCandidate c = candidates.get(i);
            int by = ly + i * 20;
            MedievalButton assign = new MedievalButton(lx, by, 168, 18,
                    Component.literal(c.name() + (c.idle() ? "" : " (忙)")),
                    () -> onAssign(c));
            addRenderableWidget(assign);
        }
    }

    private void initOccupied(int contentTop) {
        // ── Action buttons (2×2, bottom-right) ──
        int bx = leftPos + 214;
        int by = topPos + PH - 66;
        boolean operate = canOperate();

        MedievalButton equip = new MedievalButton(bx, by, 76, 18,
                I18n.name("gui.wandscape.mage_hut.equip", "装备"), () -> sendAction("open_equip"));
        equip.active = operate;
        addRenderableWidget(equip);

        MedievalButton strategy = new MedievalButton(bx + 80, by, 76, 18,
                I18n.name("gui.wandscape.mage_hut.strategy", "策略"), () -> sendAction("open_strategy"));
        strategy.active = operate;
        addRenderableWidget(strategy);

        MedievalButton upgrade = new MedievalButton(bx, by + 21, 76, 18,
                I18n.name("gui.wandscape.mage_hut.upgrade", "升级法师 (Lv.%d)", mageLevel),
                () -> sendAction("upgrade"));
        upgrade.active = operate && MageHutAttributes.canLevelUp(mageLevel, colonyLevel);
        addRenderableWidget(upgrade);

        MedievalButton rest = new MedievalButton(bx + 80, by + 21, 76, 18,
                I18n.name("gui.wandscape.mage_hut.rest", "休息"), () -> sendAction("rest"));
        rest.active = operate && !resting;
        addRenderableWidget(rest);

        // ── Train selector + button (right column, above action buttons) ──
        int tx = leftPos + 214;
        int ty = contentTop + 8;
        for (int i = 0; i < MageHutAttributes.ORDER.size(); i++) {
            AttributeType type = MageHutAttributes.ORDER.get(i);
            boolean trainable = canOperate() && MageHutAttributes.canTrain(type, base[type.ordinal()]);
            boolean sel = (i == selectedTrain);
            String label = attrKeyLabel(type) + " " + fmt(base[type.ordinal()]);
            int ry = ty + i * 17;
            int idx = i;
            MedievalButton b = new MedievalButton(tx, ry, 156, 16,
                    Component.literal((sel ? "▶ " : "") + label),
                    () -> {
                        selectedTrain = idx;
                        clearWidgets();
                        init();
                        Minecraft.getInstance().getSoundManager().play(
                                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                    });
            b.active = trainable;
            addRenderableWidget(b);
        }
        // Train button
        AttributeType selTrain = MageHutAttributes.ORDER.get(selectedTrain);
        MedievalButton train = new MedievalButton(tx, ty + 7 * 17 + 6, 156, 18,
                I18n.name("gui.wandscape.mage_hut.train_btn", "训练 (+%s)", costDur()),
                () -> sendAction("train:" + selTrain.name()));
        train.active = canOperate()
                && MageHutAttributes.canTrain(selTrain, base[selTrain.ordinal()]);
        addRenderableWidget(train);
    }

    private String attrKeyLabel(AttributeType type) {
        return I18n.name("gui.wandscape.mage_hut." + attrKey(type), fallbackLabel(type)).getString();
    }

    private String costDur() {
        return String.valueOf(com.wsteam.wandscape.shared.registry.WandscapeConstants.MAGE_HUT_COST_PER_ELEMENT);
    }

    private void onAssign(MageCandidate c) {
        sendAction("assign:" + c.npcId());
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.VILLAGER_YES, 1.0f));
    }

    private void sendAction(String action) {
        PacketDistributor.sendToServer(new MageHutActionPacket(buildingPos, action));
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        var font = Minecraft.getInstance().font;
        int contentTop = topPos + headerHeight + 6;

        if (hasResident) {
            renderOccupied(g, font, mouseX, mouseY, contentTop);
        } else {
            renderEmpty(g, font, contentTop);
        }

        renderCreatorFooter(g);
    }

    private void renderEmpty(GuiGraphics g, net.minecraft.client.gui.Font font, int contentTop) {
        int cx = leftPos + PW / 2;
        g.drawCenteredString(font, I18n.name("gui.wandscape.mage_hut.empty_hint", "尚无法师入住").getString(),
                cx, contentTop + 20, MedievalColors.ACCENT_GOLD);
        g.drawCenteredString(font, I18n.name("gui.wandscape.mage_hut.empty_sub",
                "选择下方一位法师指派入舍").getString(),
                cx, contentTop + 36, MedievalColors.TEXT_DIM);
    }

    private void renderOccupied(GuiGraphics g, net.minecraft.client.gui.Font font,
                                int mouseX, int mouseY, int contentTop) {
        // ── 3D preview + identity (top-left) ──
        int px = leftPos + 12;
        int py = contentTop;
        int pw = 52;
        int ph = 66;
        g.fill(px, py, px + pw, py + ph, MedievalColors.PARCHMENT_DEEPEST);
        drawGlowBorder(g, px, py, pw, ph, MedievalColors.BORDER_GOLD);
        if (previewNpc != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    g, px + 2, py + 2, px + pw - 2, py + ph - 2, 24, 0.0625f,
                    mouseX, mouseY, previewNpc);
        }

        int hx = px + pw + 6;
        g.drawString(font, mageName, hx, py + 2, MedievalColors.ACCENT_GOLD);
        g.drawString(font, I18n.name("gui.wandscape.mage_hut.level", "等级 Lv.%d", mageLevel).getString(),
                hx, py + 16, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, I18n.name("gui.wandscape.mage_hut.colony_level", "城镇 Lv.%d", colonyLevel).getString(),
                hx, py + 30, MedievalColors.TEXT_MUTED);
        String status = !alive
                ? I18n.name("gui.wandscape.mage_hut.status_dead", "🧟 已死亡").getString()
                : resting ? I18n.name("gui.wandscape.mage_hut.status_resting", "💤 休息中").getString()
                : I18n.name("gui.wandscape.mage_hut.status_idle", "空闲").getString();
        g.drawString(font, status, hx, py + 44, MedievalColors.TEXT_WARM_WHITE);

        // ── Attribute rows (left column) ──
        int ay = contentTop + 74;
        int ax = leftPos + 12;
        for (AttributeType type : MageHutAttributes.ORDER) {
            int i = type.ordinal();
            float b = base[i];
            float upper = MageHutAttributes.upper(type);
            float lvl = MageHutAttributes.perLevel(type) * Math.max(0, mageLevel - 1);
            float eq = equip[i];
            String row = I18n.name("gui.wandscape.mage_hut." + attrKey(type), fallbackLabel(type)).getString()
                    + ": " + fmt(b) + "/" + fmt(upper) + "  +" + fmt(lvl) + "  +" + fmt(eq);
            g.drawString(font, row, ax, ay, MedievalColors.TEXT_WARM_WHITE);
            ay += 14;
        }
    }

    private static String attrKey(AttributeType type) {
        return switch (type) {
            case MAX_HP -> "attr_hp";
            case MOVE_SPEED -> "attr_speed";
            case SPELL_POWER -> "attr_power";
            case WORK_SPEED -> "attr_work";
            case SPELL_SPEED -> "attr_cast";
            case ARMOR_VALUE -> "attr_armor";
            case MAX_MANA -> "attr_mana";
        };
    }

    private static String fallbackLabel(AttributeType type) {
        return switch (type) {
            case MAX_HP -> "生命";
            case MOVE_SPEED -> "速度";
            case SPELL_POWER -> "法强";
            case WORK_SPEED -> "工速";
            case SPELL_SPEED -> "施速";
            case ARMOR_VALUE -> "护甲";
            case MAX_MANA -> "魔力";
        };
    }

    private static String fmt(float v) {
        if (Math.abs(v - Math.floor(v)) < 0.001f) {
            return String.valueOf((int) v);
        }
        return String.format("%.1f", v);
    }
}
