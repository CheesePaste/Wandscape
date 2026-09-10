package com.wsteam.wandscape.content.building.client;

import com.wsteam.wandscape.content.building.network.TownHallNameStylePacket;
import com.wsteam.wandscape.content.building.network.TownHallReviveRequestPacket;
import com.wsteam.wandscape.content.building.network.TownHallTouristSpawnPacket;
import com.wsteam.wandscape.content.building.network.TownHallWarehouseRequestPacket;
import com.wsteam.wandscape.foundation.util.NameStyle;
import com.wsteam.wandscape.content.colony.network.ColonyNameUpdatePacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.component.MedievalButton;
import com.wsteam.wandscape.foundation.ui.component.MedievalScreen;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Town Hall info screen — colony name (editable), level, experience bar, progression
 * and the character naming rule switcher (western fantasy / Chinese / English).
 * The bottom row also carries the 「生成游客」 toggle, an optional warehouse-access
 * button, and the 「复活法师」 anti-deadlock bootstrap revive (only pressable when every
 * wizard in the colony is dead and the per-colony cooldown has elapsed).
 * Uses {@link MedievalScreen} MINIMAL theme with {@link MedievalColors}.
 */
public class TownHallScreen extends MedievalScreen {

    private static final int PW = 300;
    private static final int PH = 230;
    private static final int EXP_BAR_W = 200;
    private static final int EXP_BAR_H = 12;

    private final BlockPos buildingPos;
    private final UUID colonyId;
    private String colonyName;
    private final int level;
    private final int experience;
    private final int expToNext;
    private final String founderName;
    /** True when the colony has no storage building — show the warehouse access button. */
    private final boolean canUseWarehouse;
    /** Colony's town hall 「生成游客」 toggle (default enabled server-side). */
    private boolean touristSpawning;
    /** Living wizards in this colony — the bootstrap revive button only lights up at 0. */
    private int aliveNpcCount;
    /** Wizards awaiting revival in this colony (death records) — must be > 0 for the button. */
    private int deadNpcCount;
    /** Server-authoritative bootstrap-revive cooldown, counted down locally while the screen is open. */
    private int reviveCooldownSeconds;
    /** Ticks accumulated toward the next local cooldown countdown step. */
    private int cooldownTickAccum;

    private EditBox nameBox;
    private NameStyle namingStyle;
    private final MedievalButton[] styleButtons = new MedievalButton[NameStyle.values().length];
    private MedievalButton touristButton;
    private MedievalButton reviveButton;

    public TownHallScreen(BlockPos buildingPos, UUID colonyId,
                          String colonyName, int level, int experience, int expToNext,
                          String founderName, boolean canUseWarehouse, int namingStyleOrdinal,
                          String creator, boolean touristSpawning,
                          int aliveNpcCount, int deadNpcCount, int reviveCooldownSeconds) {
        super(I18n.name("gui.wandscape.townhall.title", "Town Hall"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.townhall.title", "市政厅"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "townhall_guide";
        this.buildingPos = buildingPos;
        this.colonyId = colonyId;
        this.colonyName = colonyName != null ? colonyName : "";
        this.level = level;
        this.experience = experience;
        this.expToNext = expToNext;
        this.founderName = founderName;
        this.canUseWarehouse = canUseWarehouse;
        this.namingStyle = ordinalToStyle(namingStyleOrdinal);
        this.touristSpawning = touristSpawning;
        this.aliveNpcCount = aliveNpcCount;
        this.deadNpcCount = deadNpcCount;
        this.reviveCooldownSeconds = reviveCooldownSeconds;
        setCreator(creator);
        setBuildingContext(null, buildingPos);
    }

    /** Colony this panel is showing — lets the revive-state push find the right open screen. */
    public UUID colonyId() {
        return colonyId;
    }

    /**
     * Apply a server-side revive-state push: refresh population/cooldown and re-evaluate the button.
     * Called by the {@code TownHallReviveStatePacket} client handler when this colony's panel is open.
     */
    public void applyReviveState(int aliveNpcCount, int deadNpcCount, int reviveCooldownSeconds) {
        this.aliveNpcCount = aliveNpcCount;
        this.deadNpcCount = deadNpcCount;
        this.reviveCooldownSeconds = reviveCooldownSeconds;
        this.cooldownTickAccum = 0;
        refreshReviveButton();
    }

    /** Advance the local cooldown readout once per second; the server value stays authoritative. */
    @Override
    public void tick() {
        super.tick();
        if (reviveCooldownSeconds <= 0 || ++cooldownTickAccum < 20) return;
        cooldownTickAccum = 0;
        reviveCooldownSeconds--;
        refreshReviveButton();
    }

    private static NameStyle ordinalToStyle(int ordinal) {
        NameStyle[] values = NameStyle.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NameStyle.FANTASY;
    }

    @Override
    protected void init() {
        setActionButtonsOffset(PW - 14 - 92, PH - 20);
        super.init();

        int cx = leftPos + PW / 2;
        int ebY = topPos + headerHeight + 11;

        nameBox = new EditBox(font, cx - 80, ebY, 160, font.lineHeight + 2,
                I18n.name("gui.wandscape.townhall.name_hint", "魔法小镇名称"));
        nameBox.setValue(colonyName);
        nameBox.setMaxLength(30);
        nameBox.setBordered(false);
        nameBox.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        nameBox.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        nameBox.setCanLoseFocus(true);
        nameBox.setResponder(this::onNameChanged);
        addRenderableWidget(nameBox);

        // Character naming rule switcher (fantasy / chinese / english)
        int sbW = 64;
        int sbH = 14;
        int sbGap = 6;
        int sbTotal = sbW * styleButtons.length + sbGap * (styleButtons.length - 1);
        int sbX = leftPos + (PW - sbTotal) / 2;
        int sbY = ebY + 2 * font.lineHeight + 16;
        for (int i = 0; i < styleButtons.length; i++) {
            NameStyle style = NameStyle.values()[i];
            int x = sbX + i * (sbW + sbGap);
            styleButtons[i] = new MedievalButton(x, sbY, sbW, sbH,
                    styleButtonLabel(style), () -> switchNamingStyle(style));
            addRenderableWidget(styleButtons[i]);
        }

        // Bottom row: 「生成游客」 / optional 「仓库存取」 / 「复活法师」 (anti-deadlock bootstrap).
        // 左上留创建者页脚 → 从 leftPos+96 起排，避免盖住「创建者：…」；按钮等分剩余宽度。
        int bh = 16;
        int gap = 6;
        int by = topPos + PH - bh - 12;
        int buttonCount = canUseWarehouse ? 3 : 2;
        int bw = (PW - 96 - 8 - gap * (buttonCount - 1)) / buttonCount;
        int startX = leftPos + 96;
        // 三按钮时每个仅 ~61px，标签必须短；「生成游客」的开关态由金色描边表达（见 renderTouristToggleHighlight）。
        touristButton = new MedievalButton(startX, by, bw, bh, spawnTouristsLabel(), this::toggleTouristSpawning);
        addRenderableWidget(touristButton);
        int nextX = startX + bw + gap;
        if (canUseWarehouse) {
            addRenderableWidget(new MedievalButton(nextX, by, bw, bh,
                    I18n.name("gui.wandscape.townhall.warehouse", "仓库存取"),
                    this::onWarehouseAccess));
            nextX += bw + gap;
        }
        reviveButton = new MedievalButton(nextX, by, bw, bh, reviveLabel(), this::onReviveRequested);
        addRenderableWidget(reviveButton);
        refreshReviveButton();
    }

    private static Component styleButtonLabel(NameStyle style) {
        return switch (style) {
            case FANTASY -> I18n.name("gui.wandscape.townhall.style_fantasy", "西幻");
            case CHINESE -> I18n.name("gui.wandscape.townhall.style_chinese", "中文");
            case ENGLISH -> I18n.name("gui.wandscape.townhall.style_english", "英文");
        };
    }

    private void switchNamingStyle(NameStyle style) {
        if (style == namingStyle) return;
        namingStyle = style;
        PacketDistributor.sendToServer(new TownHallNameStylePacket(colonyId, style.ordinal()));
    }

    private void onWarehouseAccess() {
        PacketDistributor.sendToServer(new TownHallWarehouseRequestPacket(buildingPos, colonyId));
    }

    /** 开关态由金色描边表达（窄按钮放不下「：已开启」后缀），故文案固定。 */
    private Component spawnTouristsLabel() {
        return I18n.name("gui.wandscape.townhall.spawn_tourists", "生成游客");
    }

    private void toggleTouristSpawning() {
        touristSpawning = !touristSpawning;
        touristButton.setMessage(spawnTouristsLabel());
        PacketDistributor.sendToServer(new TownHallTouristSpawnPacket(colonyId, touristSpawning));
    }

    /** 「复活法师」按钮文案：冷却中显示倒计时，其余情况用固定短文案（窄按钮放不下长句）。 */
    private Component reviveLabel() {
        if (reviveCooldownSeconds > 0) {
            int seconds = reviveCooldownSeconds % 60;
            return I18n.name("gui.wandscape.townhall.revive_cooldown_label", "复活 %s",
                    reviveCooldownSeconds / 60 + ":" + (seconds < 10 ? "0" : "") + seconds);
        }
        return I18n.name("gui.wandscape.townhall.revive", "复活法师");
    }

    /** 全灭且不在冷却时按钮才可按——这是全员阵亡后小镇唯一能重新运转的自举出口。 */
    private void refreshReviveButton() {
        if (reviveButton == null) return;
        reviveButton.setMessage(reviveLabel());
        reviveButton.active = aliveNpcCount == 0 && deadNpcCount > 0 && reviveCooldownSeconds <= 0;
    }

    private void onReviveRequested() {
        PacketDistributor.sendToServer(new TownHallReviveRequestPacket(buildingPos, colonyId));
    }

    private void onNameChanged(String newName) {
        String trimmed = newName.trim();
        if (!trimmed.isEmpty() && !trimmed.equals(colonyName)) {
            colonyName = trimmed;
            PacketDistributor.sendToServer(new ColonyNameUpdatePacket(colonyId, trimmed));
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderContent(g);
        renderSelectedStyleHighlight(g);
        renderTouristToggleHighlight(g);
        renderReviveReadyHighlight(g);
    }

    /** Gold border while the bootstrap revive is actually available — draws the eye to the escape hatch. */
    private void renderReviveReadyHighlight(GuiGraphics g) {
        if (reviveButton == null || !reviveButton.active) return;
        renderButtonGoldBorder(g, reviveButton);
    }

    /** Gold border around the 「生成游客」 button while it is enabled — mirror of the style selector. */
    private void renderTouristToggleHighlight(GuiGraphics g) {
        if (!touristSpawning || touristButton == null || !touristButton.visible) return;
        renderButtonGoldBorder(g, touristButton);
    }

    private void renderButtonGoldBorder(GuiGraphics g, MedievalButton btn) {
        int bx = btn.getX();
        int by = btn.getY();
        int bw = btn.getWidth();
        int bh = btn.getHeight();
        g.fill(bx, by, bx + bw, by + 1, MedievalColors.BORDER_GOLD);
        g.fill(bx, by + bh - 1, bx + bw, by + bh, MedievalColors.BORDER_GOLD);
        g.fill(bx, by, bx + 1, by + bh, MedievalColors.BORDER_GOLD);
        g.fill(bx + bw - 1, by, bx + bw, by + bh, MedievalColors.BORDER_GOLD);
    }

    /** Gold border around the currently active naming-rule button. */
    private void renderSelectedStyleHighlight(GuiGraphics g) {
        MedievalButton sel = styleButtons[namingStyle.ordinal()];
        if (sel == null || !sel.visible) return;
        int bx = sel.getX();
        int by = sel.getY();
        int bw = sel.getWidth();
        int bh = sel.getHeight();
        g.fill(bx, by, bx + bw, by + 1, MedievalColors.BORDER_GOLD);
        g.fill(bx, by + bh - 1, bx + bw, by + bh, MedievalColors.BORDER_GOLD);
        g.fill(bx, by, bx + 1, by + bh, MedievalColors.BORDER_GOLD);
        g.fill(bx + bw - 1, by, bx + bw, by + bh, MedievalColors.BORDER_GOLD);
    }

    private void renderContent(GuiGraphics g) {
        int cx = leftPos + PW / 2;
        int leftX = leftPos + 16;

        // Edit box background
        int ebX = cx - 82;
        int ebY = topPos + headerHeight + 11;
        int ebW = 164;
        int ebH = font.lineHeight + 6;
        drawInsetField(g, ebX, ebY, ebW, ebH);

        // Naming rule label (buttons are renderables drawn after this)
        int styleLabelY = ebY + ebH + 6;
        Component styleLabel = I18n.name("gui.wandscape.townhall.naming_style", "命名风格");
        g.drawString(font, styleLabel, cx - font.width(styleLabel) / 2, styleLabelY,
                MedievalColors.TEXT_MUTED);

        // Colony founder
        int y = styleLabelY + font.lineHeight + 4 + 14 + 8;
        Component founderText = I18n.name("gui.wandscape.townhall.founder", "创建者：%s",
                founderName != null && !founderName.isEmpty() ? founderName : "—");
        g.drawString(font, founderText, cx - font.width(founderText) / 2, y,
                MedievalColors.TEXT_WARM_WHITE);
        y += font.lineHeight + 6;

        // Colony level
        Component levelText = I18n.name("gui.wandscape.townhall.level", "魔法小镇等级 %s", level);
        g.drawString(font, levelText, cx - font.width(levelText) / 2, y,
                MedievalColors.BORDER_GOLD);
        y += font.lineHeight + 8;

        // Experience bar
        renderExpBar(g, y);
        y += EXP_BAR_H + 10;

        // Experience source info
        g.drawString(font, I18n.name("gui.wandscape.townhall.exp_source", "经验来源（游客满意度100%时）："),
                leftX, y, MedievalColors.TEXT_MUTED);
        y += font.lineHeight + 2;

        Component[] expLines = {
            I18n.name("gui.wandscape.townhall.exp_lt", "游客等级 < 魔法小镇等级 → 0 经验"),
            I18n.name("gui.wandscape.townhall.exp_eq", "游客等级 = 魔法小镇等级 → 200 经验"),
            I18n.name("gui.wandscape.townhall.exp_gt", "游客等级 > 魔法小镇等级 → 500 经验")
        };
        for (Component line : expLines) {
            g.drawString(font, Component.literal("  ").copy().append(line), leftX + 4, y,
                    MedievalColors.TEXT_MUTED);
            y += font.lineHeight + 1;
        }

        Component hint = I18n.name("gui.wandscape.townhall.hint",
                "点击名称框修改魔法小镇名称，输入完成自动保存");
        g.drawString(font, hint, cx - font.width(hint) / 2, y, MedievalColors.TEXT_MUTED);
    }

    private void renderExpBar(GuiGraphics g, int barY) {
        int barX = leftPos + (PW - EXP_BAR_W) / 2;
        int cx = leftPos + PW / 2;
        float ratio = expToNext > 0 ? (float) experience / expToNext : 0;
        int fillW = (int) (EXP_BAR_W * Math.min(1.0f, ratio));

        g.fill(barX, barY, barX + EXP_BAR_W, barY + EXP_BAR_H, 0x28000000);
        if (fillW > 0) {
            g.fill(barX, barY, barX + fillW, barY + EXP_BAR_H, MedievalColors.BORDER_GOLD);
        }
        int border = MedievalColors.BORDER_GOLD_DARK;
        g.fill(barX, barY, barX + EXP_BAR_W, barY + 1, border);
        g.fill(barX, barY + EXP_BAR_H - 1, barX + EXP_BAR_W, barY + EXP_BAR_H, border);
        g.fill(barX, barY, barX + 1, barY + EXP_BAR_H, border);
        g.fill(barX + EXP_BAR_W - 1, barY, barX + EXP_BAR_W, barY + EXP_BAR_H, border);

        String expText = experience + " / " + expToNext;
        g.drawString(font, expText,
                cx - font.width(expText) / 2,
                barY + (EXP_BAR_H - font.lineHeight) / 2,
                MedievalColors.TEXT_WARM_WHITE);
    }
}
