package com.wsteam.wandscape.content.warehouse.client;

import com.wsteam.wandscape.WandscapeClient;
import com.wsteam.wandscape.content.building.projection.client.BuildingDebugClientState;
import com.wsteam.wandscape.content.building.projection.network.BuildingActionPacket;
import com.wsteam.wandscape.content.building.projection.network.BuildingDebugRequestPacket;
import com.wsteam.wandscape.content.building.projection.network.BuildingDebugResponsePacket;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.production.network.RequestRecipeBookPacket;
import com.wsteam.wandscape.content.warehouse.WarehouseMenu;
import com.wsteam.wandscape.content.warehouse.WarehousePager;
import com.wsteam.wandscape.content.warehouse.WarehouseSlot;
import com.wsteam.wandscape.content.warehouse.network.WarehouseActionPacket;
import com.wsteam.wandscape.content.warehouse.network.WarehouseDataPacket;
import com.wsteam.wandscape.content.warehouse.network.WarehouseDataPacket.ItemEntry;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.ReplayProtectedScreen;
import com.wsteam.wandscape.foundation.ui.component.ElementPanel;
import com.wsteam.wandscape.foundation.ui.component.HelpButton;
import com.wsteam.wandscape.foundation.ui.component.MedievalButton;
import com.wsteam.wandscape.foundation.ui.component.MedievalConfirmDialog;
import com.wsteam.wandscape.foundation.ui.component.MedievalScreen;
import com.wsteam.wandscape.foundation.ui.component.ScreenFeedbackHost;
import com.wsteam.wandscape.foundation.ui.component.SearchBox;
import com.wsteam.wandscape.foundation.ui.skin.SkinRender;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import com.wsteam.wandscape.foundation.util.ItemKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Colony warehouse GUI — one page.
 *
 * <p><b>Layout</b>: a real vanilla 6-row chest (the {@code generic_54} texture) with
 * the read-only warehouse slots, and the vanilla player inventory below. The element
 * storage column sits to the <b>left</b> of the chest texture, a search band runs
 * across the panel top (over the texture's own 18px title strip, so the panel is no
 * taller than the chest), and the pager and delete box live in the narrow margin
 * right of the chest.
 *
 * <p>Slot coordinates come from {@link WarehouseMenu} and are relative to the chest
 * texture origin, which is this screen's {@code leftPos/topPos}. The panel chrome
 * (element column / search band / right margin) is therefore drawn relative to
 * {@link #panelX}/{@link #panelY}, which sit a fixed offset left of the chest — that
 * way the vanilla slot grid never has to be shifted.
 *
 * <p>Warehouse interactions go through {@link WarehouseActionPacket}; the player
 * slots are real vanilla {@link Slot}s so all shortcuts and inventory-sorting mods
 * work on them.
 *
 * <p>When opened from the warehouse building itself the panel also carries the
 * building context ({@link WarehouseDataPacket#buildingId()}): a status badge in the
 * toolbar plus the same restore/demolish buttons every other building screen has.
 * The portable terminal and the town-hall shortcut open the warehouse without a
 * building context and show neither.
 */
public class WarehouseScreen extends AbstractContainerScreen<WarehouseMenu>
        implements ReplayProtectedScreen, ScreenFeedbackHost {

    // ── 面板几何 ──
    // 槽位原点（leftPos/topPos）就是原版 6 行箱贴图原点；面板在此之上向左扩出元素列、
    // 向上扩出搜索行。这样 WarehouseMenu 的 GRID_X/GRID_Y 与玩家背包偏移都不用动。
    private static final int CHEST_W = 176;   // 原版 generic_54 贴图宽
    private static final int CHEST_H = 222;
    private static final int PAD = 8;         // 面板内边距
    private static final int ELEM_W = 112;    // 元素列宽
    private static final int COL_GAP = 8;     // 元素列与箱子之间的缝
    private static final int LEFT_EXT = PAD + ELEM_W + COL_GAP;
    private static final int RIGHT_EXT = 48;  // 箱子右侧留白列（翻页键 + 页码 + 销毁格）

    /** 面板顶部那条搜索带：与箱贴图标题条同高（箱贴图 0..17 就是标题条），不额外加高。 */
    private static final int SEARCH_BAND = 18;
    /** 元素列从搜索带下方起画。 */
    private static final int ELEM_TOP = SEARCH_BAND + 2;

    public static final int PANEL_W = LEFT_EXT + CHEST_W + RIGHT_EXT;
    // 高度与原版箱贴图持平（222 + 8 底边距），一点都不比改造前高——再高会顶出矮屏、
    // 把箱内的玩家背包行挤到屏幕外。
    public static final int PANEL_H = CHEST_H + PAD;

    private static final int TOOLBAR_H = 20;
    private static final int BTN_W = 44;      // 与 MedievalScreen 的建筑动作按钮同尺寸
    private static final int BTN_H = 16;
    private static final int BTN_GAP = 4;

    // 销毁格：箱子右侧留白列底部 18×18（照抄创造模式 X，销毁光标上的物品）
    private static final int TRASH_SIZE = 18;

    private static final WarehousePager PAGER = new WarehousePager(54);
    private static final Comparator<ItemEntry> BY_ID = Comparator.comparing(ItemEntry::itemId);
    private static final ResourceLocation CHEST_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    private static final int GLASS_TOP = 0xBB483828;
    private static final int GLASS_BOTTOM = 0xBB1E1410;
    private static final int GLASS_BOX_TOP = 0xBB423020;
    private static final int GLASS_BOX_BOTTOM = 0xBB1C1008;

    // 面板左上角（= 槽位原点向左/向上扩出的那一圈）
    private int panelX, panelY;

    private int page;
    private int totalPages = 1;
    private String query = "";

    // Data
    private List<ItemEntry> allItems = new ArrayList<>();
    private List<ItemEntry> visibleEntries = new ArrayList<>();
    private List<ItemStack> visibleStacks = new ArrayList<>();
    private Map<ElementType, Long> elements = new LinkedHashMap<>();

    // 仓库容量读数（随每次 WarehouseDataPacket 刷新；cap<=0 = 未设上限，不显示）
    private long usedCapacity;
    private long capacity;

    private ElementPanel elementPanel;
    private SearchBox searchInput;

    // ── 顶部工具栏命中区域（computeToolbar 现算，render 与 click 共用）──
    private int toolbarY;
    private int closeX, closeY, closeW, closeH;
    private int helpX, helpY, helpW, helpH;
    private int repairX, repairY, demolishX, demolishY, recipeX, recipeY;
    private int prevX, prevY, prevW, prevH;
    private int nextX, nextY, nextW, nextH;
    private boolean prevActive;
    private boolean nextActive;
    private int trashX, trashY;
    private UUID colonyId;

    // ── 通用皮肤状态 ──
    private String buildingCreator = "";
    private final boolean showCloseButton = true;
    private final boolean showHelpButton = true;
    private final String helpDocumentPath = "warehouse_guide";
    private HelpButton helpButton;
    private Component feedback;
    private int feedbackColor;
    private long feedbackExpireTick;
    private static final long FEEDBACK_DURATION_MS = 3000L;

    // ── 建筑上下文：仅从仓库建筑本体打开时非空 ──
    private boolean isBuildingScreen;
    @Nullable private UUID buildingId;
    @Nullable private BlockPos buildingPos;
    @Nullable private BuildingDebugResponsePacket buildingData;
    private MedievalButton btnRepair;
    private MedievalButton btnDemolish;
    private MedievalButton btnRecipes;
    private final MedievalConfirmDialog confirmDialog = new MedievalConfirmDialog();
    private int badgeX, badgeY, badgeW, badgeH;

    public WarehouseScreen(WarehouseMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    // ── 数据更新 ──

    public void updateItems(WarehouseDataPacket packet) {
        this.colonyId = packet.colonyId();
        if (packet.creator() != null && !packet.creator().isBlank()) {
            setCreator(packet.creator());
        }
        if (packet.buildingId() != null) {
            this.buildingId = packet.buildingId();
            this.buildingPos = packet.buildingPos();
            this.isBuildingScreen = true;
        }
        this.usedCapacity = packet.usedCapacity();
        this.capacity = packet.capacity();
        this.allItems = new ArrayList<>(packet.itemEntries());
        this.allItems.sort(BY_ID);
        this.elements = new LinkedHashMap<>(packet.elementMap());
        if (elementPanel != null) {
            elementPanel.setElements(elements);
        }
        recomputeVisible();
        // 建筑上下文可能比 init 晚到（数据包在屏建好之后才推），补拉一次建筑状态。
        initBuildingContext();
    }

    /** 建筑状态快照：由 WandscapeClient 在收到 BuildingDebugResponsePacket 时下发。 */
    public void setBuildingData(BuildingDebugResponsePacket data) {
        if (!isBuildingScreen || buildingId == null || data == null) return;
        // 严格按 id 匹配：调试请求是屏自己发的，回来的一定是本建筑的状态。
        if (data.buildingId() == null || !buildingId.equals(data.buildingId())) return;
        this.buildingData = data;
        updateActionButtons();
    }

    public void setCreator(String creator) {
        this.buildingCreator = creator != null ? creator : "";
    }

    public void showFeedback(Component message, int color) {
        this.feedback = message;
        this.feedbackColor = color;
        this.feedbackExpireTick = System.currentTimeMillis() + FEEDBACK_DURATION_MS;
    }

    // ── 初始化 ──

    @Override
    protected void init() {
        configureLayout();
        computeToolbar();
        buildWidgets();
        menu.bindSlots(this::getEntryStack, () -> true);
        recomputeVisible();
        initBuildingContext();
    }

    /** 面板整体居中；槽位原点=箱贴图原点，与面板顶齐平，只向右偏移 LEFT_EXT。矮屏贴顶保证工具栏可见。 */
    private void configureLayout() {
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        int px = (this.width - PANEL_W) / 2;
        int py = Math.max((this.height - PANEL_H) / 2, TOOLBAR_H + 4);
        this.panelX = px;
        this.panelY = py;
        this.leftPos = px + LEFT_EXT;
        this.topPos = py;
    }

    /**
     * 面板比槽位原点（箱子贴图）向左/向上都大一圈，点外判定必须按整块面板算——
     * 否则在元素列/搜索行上点击会被当成"点在 GUI 外"，手里的物品直接丢进世界。
     */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop,
                                        int mouseButton) {
        return mouseX < panelX || mouseY < panelY
                || mouseX >= panelX + PANEL_W || mouseY >= panelY + PANEL_H;
    }

    private void buildWidgets() {
        elementPanel = new ElementPanel(panelX + PAD, topPos + ELEM_TOP, ELEM_W);
        elementPanel.setElements(elements);
        addRenderableWidget(elementPanel);

        // 搜索框只占元素列那一栏，右侧不压到箱子贴图。
        searchInput = new SearchBox(font, panelX + PAD + 1,
                panelY + (SEARCH_BAND - font.lineHeight) / 2, ELEM_W - 2,
                I18n.name("gui.wandscape.warehouse.search", "Search items..."));
        searchInput.setResponder(q -> {
            query = q;
            page = 0;
            recomputeVisible();
        });
        searchInput.setValue(query);
        addRenderableWidget(searchInput);

        if (showHelpButton && helpDocumentPath != null) {
            helpButton = new HelpButton(helpX, helpY, helpW, helpH, this::openHelpDocument);
            addRenderableWidget(helpButton);
        }

        btnRecipes = new MedievalButton(recipeX, recipeY, BTN_W, BTN_H,
                I18n.name("gui.wandscape.warehouse.btn_recipes", "配方"), this::openRecipeBook);
        addRenderableWidget(btnRecipes);

        // 建筑动作按钮：无建筑上下文（便携终端/市政厅代开）时整对隐藏。
        btnRepair = new MedievalButton(repairX, repairY, BTN_W, BTN_H,
                I18n.name("gui.wandscape.building_action.repair", "复原"),
                this::onRestoreClicked) {
            @Override
            protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                super.renderWidget(g, mouseX, mouseY, partialTick);
                if (visible && active && buildingData != null && buildingData.needsRepair()) {
                    g.fill(getX() + 2, getY() + height - 3, getX() + width - 2, getY() + height - 2,
                            0xAA2E7D32);
                }
            }
        };
        btnDemolish = new MedievalButton(demolishX, demolishY, BTN_W, BTN_H,
                I18n.name("gui.wandscape.building_action.destroy", "拆除"),
                this::onDemolishClicked);
        btnRepair.visible = false;
        btnDemolish.visible = false;
        addRenderableWidget(btnRepair);
        addRenderableWidget(btnDemolish);
    }

    /** 现算工具栏几何（render 与 click 用同一套坐标）。 */
    private void computeToolbar() {
        toolbarY = panelY - TOOLBAR_H - 2;
        closeW = 18;
        closeH = 14;
        closeX = panelX + PANEL_W - closeW - 4;
        closeY = toolbarY + (TOOLBAR_H - closeH) / 2;
        helpW = 14;
        helpH = 14;
        helpX = closeX - helpW - 4;
        helpY = toolbarY + (TOOLBAR_H - helpH) / 2;

        // 右侧动作按钮自右向左排：配方 → 拆除 → 复原（等宽同高，与其它建筑屏一致）。
        int by = toolbarY + (TOOLBAR_H - BTN_H) / 2;
        recipeX = helpX - BTN_W - 4;
        recipeY = by;
        demolishX = recipeX - BTN_W - BTN_GAP;
        demolishY = by;
        repairX = demolishX - BTN_W - BTN_GAP;
        repairY = by;
    }

    // ── 建筑上下文 ──

    private void initBuildingContext() {
        if (!isBuildingScreen) return;
        if (buildingData == null) {
            var cached = BuildingDebugClientState.getCachedData();
            if (cached != null && buildingId != null && buildingId.equals(cached.buildingId())) {
                buildingData = cached;
            }
        }
        if (buildingData == null && buildingPos != null) {
            Net.toServer(new BuildingDebugRequestPacket(buildingPos));
        }
        updateActionButtons();
    }

    private void updateActionButtons() {
        if (btnRepair == null || btnDemolish == null) return;
        if (buildingData == null) {
            btnRepair.visible = false;
            btnDemolish.visible = false;
            return;
        }
        btnRepair.visible = true;
        btnDemolish.visible = true;

        boolean demolishing = buildingData.demolishing();
        boolean underConstruction = buildingData.underConstruction();
        boolean needsRepair = buildingData.needsRepair();

        if (demolishing) {
            btnDemolish.setMessage(I18n.name("gui.wandscape.building_action.demolishing", "拆除中..."));
            btnDemolish.active = false;
        } else {
            btnDemolish.setMessage(I18n.name("gui.wandscape.building_action.destroy", "拆除"));
            btnDemolish.active = true;
        }

        if (demolishing) {
            btnRepair.setMessage(I18n.name("gui.wandscape.building_action.repair", "复原"));
            btnRepair.active = false;
        } else if (underConstruction) {
            btnRepair.setMessage(I18n.name("gui.wandscape.building_action.cancel", "撤销"));
            btnRepair.active = true;
        } else {
            btnRepair.setMessage(I18n.name("gui.wandscape.building_action.repair", "复原"));
            btnRepair.active = needsRepair;
        }
    }

    private void onRestoreClicked() {
        if (buildingData == null) return;
        UUID targetId = buildingData.buildingId() != null ? buildingData.buildingId() : buildingId;
        if (targetId == null) return;

        if (buildingData.underConstruction()) {
            final UUID cancelId = targetId;
            String name = buildingDisplayName();
            confirmDialog.open(
                    I18n.name("gui.wandscape.confirm.cancel.title", "确认撤销"),
                    I18n.name("gui.wandscape.confirm.cancel.msg",
                            "确定要撤销「%s」的建造吗？已建部分将一并清除，只退还未开工或未建成部分的建材。", name),
                    () -> {
                        Net.toServer(new BuildingActionPacket(cancelId, "cancel"));
                        onClose();
                    });
            return;
        }

        if (buildingData.needsRepair() && !buildingData.demolishing()) {
            Net.toServer(new BuildingActionPacket(targetId, "repair"));
            showFeedback(I18n.name("gui.wandscape.building_action.repair_sent", "已下发复原任务"),
                    MedievalColors.SUCCESS_GREEN);
        }
    }

    private void onDemolishClicked() {
        if (buildingData == null || buildingData.demolishing()) return;
        UUID targetId = buildingData.buildingId() != null ? buildingData.buildingId() : buildingId;
        if (targetId == null) return;

        final UUID destroyId = targetId;
        String name = buildingDisplayName();
        confirmDialog.open(
                I18n.name("gui.wandscape.confirm.demolish.title", "确认拆除"),
                I18n.name("gui.wandscape.confirm.demolish.msg",
                        "确定要拆除「%s」吗？已下发的工作将中断，拆除不再返还任何建材。", name),
                () -> {
                    Net.toServer(new BuildingActionPacket(destroyId, "destroy"));
                    onClose();
                });
    }

    private String buildingDisplayName() {
        if (buildingData != null && buildingData.displayName() != null
                && !buildingData.displayName().isEmpty()) {
            return buildingData.displayName();
        }
        return I18n.name("gui.wandscape.warehouse.title", "Warehouse").getString();
    }

    private void openRecipeBook() {
        if (colonyId != null) {
            Net.toServer(new RequestRecipeBookPacket(colonyId));
        }
    }

    // ── 分页 / 显示数据 ──

    private void recomputeVisible() {
        var result = PAGER.page(allItems, this::matchesQuery, BY_ID, page);
        this.page = result.page();
        this.totalPages = result.totalPages();
        this.visibleEntries = result.entries();
        this.visibleStacks = new ArrayList<>(visibleEntries.size());
        for (ItemEntry entry : visibleEntries) {
            visibleStacks.add(toStack(entry));
        }
        this.prevActive = result.hasPrev();
        this.nextActive = result.hasNext();
    }

    private boolean matchesQuery(ItemEntry entry) {
        return SearchBox.matches(SearchBox.itemSearchText(entry.itemId()), query);
    }

    private ItemStack getEntryStack(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= visibleStacks.size()) {
            return ItemStack.EMPTY;
        }
        return visibleStacks.get(slotIndex);
    }

    private ItemStack toStack(ItemEntry entry) {
        var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entry.itemId()));
        if (registryItem == null || registryItem == Items.AIR) return ItemStack.EMPTY;
        int count = (int) Math.min(Math.max(entry.count(), 1), Integer.MAX_VALUE);
        if (minecraft == null || minecraft.level == null) return new ItemStack(registryItem, count);
        // 账本载荷是完整物品序列化（ItemKey 语义）；用当前 level 的 registry 解码还原全组件。
        return ItemKey.of(entry.itemId(), entry.nbt()).toStack(count, minecraft.level.registryAccess());
    }

    // ── 渲染 ──

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderCreatorFooter(g);
        renderFeedback(g);
        if (!confirmDialog.isOpen()) {
            renderActionTooltips(g, mouseX, mouseY);
            renderTooltip(g, mouseX, mouseY);
            renderTrashTooltip(g, mouseX, mouseY);
        }
        if (confirmDialog.isOpen()) {
            confirmDialog.render(g, width, height, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        renderToolbar(g, mouseX, mouseY);

        // 整块面板（含左侧元素列与顶部搜索带）的玻璃底 + 金边。
        g.fillGradient(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, GLASS_TOP, GLASS_BOTTOM);
        drawGlowBorder(g, panelX, panelY, PANEL_W, PANEL_H, MedievalColors.BORDER_GOLD);

        // 元素列衬底：与箱子贴图区分开。
        g.fillGradient(panelX + PAD - 2, topPos + ELEM_TOP - 2,
                panelX + PAD + ELEM_W + 2, topPos + ELEM_TOP + 7 * 18 + 2,
                GLASS_BOX_TOP, GLASS_BOX_BOTTOM);
        drawGlowBorder(g, panelX + PAD - 2, topPos + ELEM_TOP - 2, ELEM_W + 4, 7 * 18 + 4,
                MedievalColors.BORDER_GOLD_DARK);

        // 原版 6 行箱贴图（槽位原点；仓库格 + 玩家背包）。
        g.blit(CHEST_TEXTURE, leftPos, topPos, 0, 0, CHEST_W, CHEST_H);

        // 搜索带盖在箱贴图自带的标题条上：面板用一条统一的深色带做顶栏，箱内方格
        // （从 topPos+18 起）正好紧贴它下沿，视觉上不额外占高。
        g.fillGradient(panelX, panelY, panelX + PANEL_W, panelY + SEARCH_BAND,
                GLASS_BOX_TOP, GLASS_BOX_BOTTOM);
        g.fill(panelX, panelY + SEARCH_BAND - 1, panelX + PANEL_W, panelY + SEARCH_BAND,
                MedievalColors.BORDER_GOLD_DARK);

        drawCapacity(g);
        renderPager(g, mouseX, mouseY);
        renderTrash(g, mouseX, mouseY);
    }

    private void renderToolbar(GuiGraphics g, int mouseX, int mouseY) {
        computeToolbar();
        g.fillGradient(panelX, toolbarY, panelX + PANEL_W, toolbarY + TOOLBAR_H,
                GLASS_BOX_TOP, GLASS_BOX_BOTTOM);
        drawGlowBorder(g, panelX, toolbarY, PANEL_W, TOOLBAR_H, MedievalColors.BORDER_GOLD);

        String title = I18n.name("gui.wandscape.warehouse.title", "Warehouse").getString();
        g.drawString(font, title, panelX + 8, toolbarY + (TOOLBAR_H - font.lineHeight) / 2,
                MedievalColors.TEXT_WARM_WHITE);
        renderStatusBadge(g, panelX + 8 + font.width(title) + 8);

        if (showCloseButton) {
            int state = isInRect(mouseX, mouseY, closeX, closeY, closeW, closeH) ? 1 : 0;
            SkinRender.drawCloseButton(g, closeX, closeY, closeW, closeH, state);
        }
    }

    /** 建筑状态徽标；无建筑上下文（便携终端/市政厅代开）时不画。 */
    private void renderStatusBadge(GuiGraphics g, int x) {
        badgeW = 0;
        if (buildingData == null) return;
        Component text = MedievalScreen.getStatusBadgeText(buildingData);
        int color = MedievalScreen.getStatusBadgeColor(buildingData);
        int w = font.width(text) + 8;
        int h = 12;
        int y = toolbarY + (TOOLBAR_H - h) / 2;
        int border = (color & 0x00FFFFFF) | 0x88000000;
        g.fill(x, y, x + w, y + h, 0xAA180E14);
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y + h - 1, x + w, y + h, border);
        g.fill(x, y, x + 1, y + h, border);
        g.fill(x + w - 1, y, x + w, y + h, border);
        g.drawString(font, text, x + 4, y + 2, color);
        badgeX = x;
        badgeY = y;
        badgeW = w;
        badgeH = h;
    }

    /** 元素列下方那两行（制作者署名、容量读数）的起始 Y。 */
    private int leftFooterY() {
        return topPos + ELEM_TOP + 7 * 18 + 8;
    }

    /** 容量读数：左下角、制作者署名下面一行（未设上限则隐藏）。 */
    private void drawCapacity(GuiGraphics g) {
        if (capacity <= 0) return;
        g.drawString(font, capacityText(), panelX + PAD,
                leftFooterY() + font.lineHeight + 4, capacityColor());
    }

    private String capacityText() {
        return I18n.name("gui.wandscape.warehouse.capacity", "Space %s/%s",
                usedCapacity, capacity).getString();
    }

    /** 已满（used>=cap>0）时用警示色，否则常规暗色；未设上限返回正常色（不显示）。 */
    private int capacityColor() {
        if (capacity <= 0) return MedievalColors.TEXT_MUTED;
        return usedCapacity >= capacity ? 0xFFFF6B5E : MedievalColors.TEXT_MUTED;
    }

    /** 箱子右侧留白列的翻页键（面板内，让开顶部搜索带里的容量读数）。 */
    private void computePager() {
        int baseX = leftPos + CHEST_W + 8;
        prevW = 18;
        prevH = 12;
        prevY = topPos + SEARCH_BAND + 6;
        prevX = baseX;
        nextW = 18;
        nextH = 12;
        nextY = prevY;
        nextX = baseX + prevW + 2;
    }

    private void renderPager(GuiGraphics g, int mouseX, int mouseY) {
        computePager();
        drawNavButton(g, prevX, prevY, prevW, prevH, "◀", prevActive, mouseX, mouseY);
        drawNavButton(g, nextX, nextY, nextW, nextH, "▶", nextActive, mouseX, mouseY);
        String pageText = I18n.name("gui.wandscape.warehouse.page", "%s / %s",
                page + 1, totalPages).getString();
        g.drawString(font, pageText, prevX + (prevW + nextW + 2 - font.width(pageText)) / 2,
                prevY + prevH + 6, MedievalColors.TEXT_MUTED);
    }

    /** 销毁格几何：箱子右侧留白列底部。 */
    private void computeTrash() {
        trashX = panelX + PANEL_W - PAD - TRASH_SIZE;
        trashY = topPos + CHEST_H - 6 - TRASH_SIZE;
    }

    /** X 销毁格：槽位式小按钮 + 红 ×；光标有物品时点亮（可销毁），否则置灰提示先拾起。 */
    private void renderTrash(GuiGraphics g, int mouseX, int mouseY) {
        computeTrash();
        boolean hovered = isInRect(mouseX, mouseY, trashX, trashY, TRASH_SIZE, TRASH_SIZE);
        drawMinimalBox(g, trashX, trashY, TRASH_SIZE, TRASH_SIZE, false, hovered);
        int iconColor = menu.getCarried().isEmpty()
                ? MedievalColors.TEXT_DIM
                : hovered ? 0xFFFF7A6B : 0xFFE05040;
        drawTrashGlyph(g, iconColor);
    }

    /** 把字体 × 放大居中画进销毁格（18px 格内约居其 2/3）。 */
    private void drawTrashGlyph(GuiGraphics g, int color) {
        String glyph = "×";
        float scale = 1.8F;
        g.pose().pushPose();
        g.pose().translate(trashX + TRASH_SIZE / 2F, trashY + TRASH_SIZE / 2F, 100F);
        g.pose().scale(scale, scale, 1F);
        g.drawString(font, glyph, -font.width(glyph) / 2, -font.lineHeight / 2, color, false);
        g.pose().popPose();
    }

    /** 悬停 X 时提示用途。 */
    private void renderTrashTooltip(GuiGraphics g, int mouseX, int mouseY) {
        computeTrash();
        if (!isInRect(mouseX, mouseY, trashX, trashY, TRASH_SIZE, TRASH_SIZE)) return;
        List<Component> lines = List.of(
                I18n.name("gui.wandscape.warehouse.trash", "Delete item"),
                I18n.name("gui.wandscape.warehouse.trash_hint",
                        "Pick up an item, then click here to delete it"));
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    /** 建筑动作按钮与状态徽标的悬停提示（无建筑上下文时全是空转）。 */
    private void renderActionTooltips(GuiGraphics g, int mouseX, int mouseY) {
        if (buildingData == null) return;
        if (btnRepair != null && btnRepair.visible && btnRepair.isHoveredOrFocused()) {
            if (buildingData.demolishing()) {
                g.renderTooltip(font, I18n.name("gui.wandscape.building_action.repair_demolishing",
                        "建筑正在拆除中"), mouseX, mouseY);
            } else if (buildingData.underConstruction()) {
                g.renderTooltip(font, I18n.name(
                        "gui.wandscape.building_action.repair_cancel_construction",
                        "撤销建造施工并退还尚未建成的建材"), mouseX, mouseY);
            } else if (!btnRepair.active) {
                g.renderTooltip(font, I18n.name("gui.wandscape.building_action.repair_not_needed",
                        "建筑与蓝图一致，无需复原"), mouseX, mouseY);
            } else {
                g.renderTooltip(font, I18n.name("gui.wandscape.building_action.repair_send",
                        "下发复原任务，把建筑还原为蓝图原样"), mouseX, mouseY);
            }
            return;
        }
        if (btnDemolish != null && btnDemolish.visible && btnDemolish.isHoveredOrFocused()) {
            if (buildingData.demolishing()) {
                g.renderTooltip(font, I18n.name("gui.wandscape.building_action.demolish_running",
                        "拆除任务执行中..."), mouseX, mouseY);
            } else {
                g.renderTooltip(font, I18n.name("gui.wandscape.building_action.demolish_send",
                        "拆除该建筑并返还建材（需确认）"), mouseX, mouseY);
            }
            return;
        }
        if (badgeW > 0 && isInRect(mouseX, mouseY, badgeX, badgeY, badgeW, badgeH)) {
            g.renderTooltip(font, MedievalScreen.getStatusTooltip(buildingData), mouseX, mouseY);
        }
    }

    private void renderCreatorFooter(GuiGraphics g) {
        if (buildingCreator.isBlank()) return;
        String text = I18n.name("gui.wandscape.common.creator_label", "Creator").getString()
                + ": " + buildingCreator;
        // 元素列下方仍有空位，署名放这里，不压箱子贴图；容量读数排在它下一行。
        g.drawString(font, text, panelX + PAD, leftFooterY(), MedievalColors.TEXT_DIM);
    }

    private void renderFeedback(GuiGraphics g) {
        if (feedback == null) return;
        if (System.currentTimeMillis() > feedbackExpireTick) {
            feedback = null;
            return;
        }
        int textW = font.width(feedback);
        int pad = 8;
        int w = textW + pad * 2;
        int h = font.lineHeight + 6;
        int x = (this.width - w) / 2;
        int y = Math.max(6, toolbarY - h - 3);

        g.fillGradient(x, y, x + w, y + h, 0xEE2A1C14, 0xEE120804);
        int borderCol = (feedbackColor & 0x00FFFFFF) | 0xDD000000;
        g.fill(x, y, x + w, y + 1, borderCol);
        g.fill(x, y + h - 1, x + w, y + h, borderCol);
        g.fill(x, y, x + 1, y + h, borderCol);
        g.fill(x + w - 1, y, x + w, y + h, borderCol);

        g.drawString(font, feedback, x + pad, y + (h - font.lineHeight) / 2, feedbackColor);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 标题/工具条由皮肤绘制；不画 vanilla 标签。
    }

    @Override
    protected void renderSlot(GuiGraphics g, Slot slot) {
        if (slot instanceof WarehouseSlot) {
            renderWarehouseSlot(g, slot);
        } else {
            super.renderSlot(g, slot);
        }
    }

    /** 原版箱子格样式：不画自绘边框（纹理自带槽框），数量按 RS 式白字描边显示在图标右上。 */
    private void renderWarehouseSlot(GuiGraphics g, Slot slot) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return;
        g.renderItem(stack, slot.x, slot.y, slot.x + slot.y * imageWidth);
        long count = slot.index < visibleEntries.size() ? visibleEntries.get(slot.index).count() : 0;
        if (count > 1) {
            renderAmount(g, slot.x, slot.y, count);
        }
    }

    /** RS 式数量：z 抬到图标之上（避免被贴图盖住）、白字描边、长文本半尺寸。 */
    private void renderAmount(GuiGraphics g, int x, int y, long count) {
        String text = WarehousePager.formatCount(count);
        boolean large = font.width(text) <= 16;
        g.pose().pushPose();
        g.pose().translate(x + (large ? 1D : 0D), y + (large ? 1D : 0D), 300D);
        if (!large) {
            g.pose().scale(0.5F, 0.5F, 1F);
        }
        g.drawString(font, text, (large ? 16 : 30) - font.width(text), large ? 8 : 22, 0xFFFFFF, true);
        g.pose().popPose();
    }

    // ── 输入 ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 确认框打开时吞掉一切点击，挡住下层。
        if (confirmDialog.isOpen()) {
            return confirmDialog.mouseClicked(mouseX, mouseY, button);
        }
        // 工具栏（关闭）优先；按钮是 widget，走最后面的 super。
        if (handleToolbarClick(mouseX, mouseY, button)) {
            return true;
        }
        if (handlePagerClick(mouseX, mouseY, button)) {
            return true;
        }
        if (handleTrashClick(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 || button == 1) {
            Slot slot = findWarehouseSlot(mouseX, mouseY);
            if (slot != null) {
                handleWarehouseSlotClick((WarehouseSlot) slot, button);
                return true;
            }
            // RS 语义：光标带物品时，点击存储区任意位置（含空白）都存入。
            if (!menu.getCarried().isEmpty() && isOverStorageArea(mouseX, mouseY)) {
                sendDepositAction(button);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleToolbarClick(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        computeToolbar();
        if (showCloseButton && isInRect(mouseX, mouseY, closeX, closeY, closeW, closeH)) {
            onClose();
            return true;
        }
        return false;
    }

    /** 箱子右侧留白列的翻页按钮点击。 */
    private boolean handlePagerClick(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        computePager();
        if (prevActive && isInRect(mouseX, mouseY, prevX, prevY, prevW, prevH)) {
            page--;
            recomputeVisible();
            return true;
        }
        if (nextActive && isInRect(mouseX, mouseY, nextX, nextY, nextW, nextH)) {
            page++;
            recomputeVisible();
            return true;
        }
        return false;
    }

    /** X 销毁格点击：左键销毁整叠、右键销毁 1 个（需光标持有物品）。 */
    private boolean handleTrashClick(double mouseX, double mouseY, int button) {
        if (button != 0 && button != 1) return false;
        computeTrash();
        if (!isInRect(mouseX, mouseY, trashX, trashY, TRASH_SIZE, TRASH_SIZE)) return false;
        // 区域命中即吞掉点击；无光标物品时无事发生（服务端同款守卫）。
        if (menu.getCarried().isEmpty()) return true;
        String action = button == 1
                ? WarehouseActionPacket.ACTION_CURSOR_DESTROY_ONE
                : WarehouseActionPacket.ACTION_CURSOR_DESTROY_ALL;
        Net.toServer(new WarehouseActionPacket(
                menu.containerId, action, "", null, 0));
        return true;
    }

    /** 存储区矩形（原版 6 行箱格区，相对槽位原点：8..170 × 18..126）。 */
    private boolean isOverStorageArea(double mouseX, double mouseY) {
        int x0 = leftPos + 8;
        int y0 = topPos + 18;
        return mouseX >= x0 && mouseX < x0 + 9 * 18
                && mouseY >= y0 && mouseY < y0 + 6 * 18;
    }

    /** 空白区存入：左键整叠、右键单个（服务端按光标操作，无需 itemId）。 */
    private void sendDepositAction(int button) {
        String action = button == 1
                ? WarehouseActionPacket.ACTION_CURSOR_DEPOSIT_ONE
                : WarehouseActionPacket.ACTION_CURSOR_DEPOSIT_ALL;
        Net.toServer(new WarehouseActionPacket(
                menu.containerId, action, "", null, 0));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (confirmDialog.isOpen()) {
            return confirmDialog.keyPressed(keyCode, scanCode, modifiers);
        }
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (showHelpButton && helpDocumentPath != null
                && !(getFocused() instanceof EditBox box && box.canConsumeInput())
                && WandscapeClient.GUIDEBOOK_TOGGLE.matches(keyCode, scanCode)) {
            openHelpDocument();
            return true;
        }
        return false;
    }

    public void openHelpDocument() {
        if (helpDocumentPath != null && minecraft != null) {
            com.wsteam.wandscape.foundation.ui.guidebook.GuideFacade.open(this, helpDocumentPath);
        }
    }

    private Slot findWarehouseSlot(double mouseX, double mouseY) {
        for (Slot slot : menu.slots) {
            if (slot instanceof WarehouseSlot && slot.isActive() && slot.hasItem()
                    && isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                return slot;
            }
        }
        return null;
    }

    private void handleWarehouseSlotClick(WarehouseSlot slot, int button) {
        if (slot.index >= visibleEntries.size()) return;
        ItemEntry entry = visibleEntries.get(slot.index);

        String action;
        if (button == 0) {
            if (hasShiftDown()) {
                action = WarehouseActionPacket.ACTION_TAKE_TO_INVENTORY;
            } else if (menu.getCarried().isEmpty()) {
                action = WarehouseActionPacket.ACTION_CURSOR_TAKE_ALL;
            } else {
                action = WarehouseActionPacket.ACTION_CURSOR_DEPOSIT_ALL;
            }
        } else {
            action = menu.getCarried().isEmpty()
                    ? WarehouseActionPacket.ACTION_CURSOR_TAKE_HALF
                    : WarehouseActionPacket.ACTION_CURSOR_DEPOSIT_ONE;
        }
        sendAction(entry, action, 0);
    }

    private void sendAction(ItemEntry entry, String action, int param) {
        Net.toServer(new WarehouseActionPacket(
                menu.containerId, action, entry.itemId(), entry.nbt(), param));
    }

    /** RS 式滚轮转移：网格区 Shift+上滚=背包→仓库、Shift+下滚=仓库→背包、Ctrl+下滚=仓库→光标；玩家槽区 Shift 滚轮同理。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // MC 语义：scrollY > 0 = 上滚（MouseHandler 直接传 GLFW yOffset）
        double delta = scrollX != 0 ? scrollX : scrollY;
        boolean up = delta > 0;
        // 无修饰滚轮：翻页（Shift/Ctrl 滚轮保留转移功能）
        if (!hasShiftDown() && !Screen.hasControlDown()) {
            page += up ? -1 : 1;
            recomputeVisible(); // PAGER 自动 clamp 到有效页
            return true;
        }
        if (hoveredSlot instanceof WarehouseSlot ws && ws.index < visibleEntries.size()) {
            ItemEntry entry = visibleEntries.get(ws.index);
            if (up && hasShiftDown()) {
                sendAction(entry, WarehouseActionPacket.ACTION_DEPOSIT_INVENTORY_TYPE, 0);
                return true;
            }
            if (!up) {
                if (hasShiftDown()) {
                    sendAction(entry, WarehouseActionPacket.ACTION_TAKE_TO_INVENTORY, 0);
                    return true;
                }
                if (Screen.hasControlDown()) {
                    sendAction(entry, WarehouseActionPacket.ACTION_CURSOR_TAKE_ALL, 0);
                    return true;
                }
            }
        } else if (hasShiftDown() && hoveredSlot != null && hoveredSlot.hasItem()
                && !(hoveredSlot instanceof WarehouseSlot)) {
            int slotIndex = hoveredSlot.getContainerSlot();
            if (up) {
                Net.toServer(new WarehouseActionPacket(menu.containerId,
                        WarehouseActionPacket.ACTION_DEPOSIT_SLOT, "", null, slotIndex));
                return true;
            }
            ItemStack stack = hoveredSlot.getItem();
            var rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (rl != null) {
                // 发送完整物品键（含全部组件），服务端据此与账本条目精确匹配。
                CompoundTag nbt = (minecraft != null && minecraft.level != null && !stack.isEmpty())
                        ? ItemKey.fromStack(stack, minecraft.level.registryAccess()).nbt()
                        : null;
                Net.toServer(new WarehouseActionPacket(menu.containerId,
                        WarehouseActionPacket.ACTION_TAKE_TO_SLOT, rl.toString(), nbt, slotIndex));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ── 皮肤绘制工具 ──

    private void drawNavButton(GuiGraphics g, int x, int y, int w, int h, String label,
                               boolean active, int mouseX, int mouseY) {
        boolean hovered = isInRect(mouseX, mouseY, x, y, w, h);
        drawMinimalBox(g, x, y, w, h, active && hovered, !active && hovered);
        int color = active ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_DIM;
        g.drawString(font, label, x + (w - font.width(label)) / 2,
                y + (h - font.lineHeight) / 2, color);
    }

    private static void drawGlowBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        int c0 = color;
        int c1 = (color & 0x00FFFFFF) | 0x66000000;
        g.fill(x, y, x + w, y + 1, c0);
        g.fill(x, y + h - 1, x + w, y + h, c0);
        g.fill(x, y, x + 1, y + h, c0);
        g.fill(x + w - 1, y, x + w, y + h, c0);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, c1);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, c1);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, c1);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, c1);
    }

    private static void drawMinimalBox(GuiGraphics g, int x, int y, int w, int h,
                                       boolean active, boolean hovered) {
        if (active) {
            g.fillGradient(x, y, x + w, y + h, GLASS_BOX_TOP, GLASS_BOX_BOTTOM);
            drawGlowBorder(g, x, y, w, h, MedievalColors.BORDER_GOLD);
        } else if (hovered) {
            g.fillGradient(x, y, x + w, y + h,
                    MedievalColors.BUTTON_BG_HOVER, MedievalColors.PANEL_TITLE_BG);
            drawGlowBorder(g, x, y, w, h, MedievalColors.BORDER_GOLD_DARK);
        } else {
            g.fillGradient(x, y, x + w, y + h, 0x992A1E18, 0x991A0E08);
            g.fill(x, y, x + w, y + 1, MedievalColors.BORDER_GOLD_DARK);
            g.fill(x, y + h - 1, x + w, y + h, MedievalColors.BORDER_GOLD_DARK);
            g.fill(x, y, x + 1, y + h, MedievalColors.BORDER_GOLD_DARK);
            g.fill(x + w - 1, y, x + w, y + h, MedievalColors.BORDER_GOLD_DARK);
        }
    }

    private static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
