package com.wsteam.wandscape.content.colony.ui;

import com.wsteam.wandscape.content.colony.network.ColonyMemberActionPacket;
import com.wsteam.wandscape.content.colony.network.ColonyPanelClientState;
import com.wsteam.wandscape.content.colony.network.ColonyPanelClientState.ColonyEntry;
import com.wsteam.wandscape.content.colony.network.ColonyPanelClientState.InviteEntry;
import com.wsteam.wandscape.content.colony.network.ColonyPanelClientState.MemberEntry;
import com.wsteam.wandscape.content.colony.overview.client.OverviewClientState;
import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;
import com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Full-screen modern RTS Colony Management Hub overlay for the Wandscape panel.
 * Accessed via pressing 3 or clicking the Colony icon on the panel sidebar.
 */
public final class ColonyManagementOverlay {

    private static final String TAG = "ColonyManagementOverlay";

    private static final int HEADER_H = 34;
    private static final int TOOLBAR_H = 26;
    private static final int CARD_H = 46;
    private static final int CARD_GAP = 6;
    private static final int TITLE_GAP = 16;
    private static final int[] TAB_PADS = {20, 14, 10};
    private static final int[] TAB_GAPS = {8, 6, 4};

    private static final int BG_BACKDROP = 0xAA080B10;
    private static final int HEADER_BG = 0xEE11151D;
    private static final int TOOLBAR_BG = 0xCC161B24;
    private static final int BORDER_GOLD = 0xFFC8A040;
    private static final int CARD_BG = 0xDD181D26;
    private static final int CARD_BG_HOVER = 0xF2222834;

    public enum Tab {
        COLONIES,
        MEMBERS,
        INVITES;

        public String getDisplayName() {
            return switch (this) {
                case COLONIES -> {
                    int pendingCount = ColonyPanelClientState.getPendingInvites().size();
                    yield pendingCount > 0
                            ? I18n.string("gui.wandscape.colony.tab.colonies_pending", "我的小镇 (%s)", String.valueOf(pendingCount))
                            : I18n.string("gui.wandscape.colony.tab.colonies", "我的小镇");
                }
                case MEMBERS -> I18n.string("gui.wandscape.colony.tab.members", "成员名册");
                case INVITES -> I18n.string("gui.wandscape.colony.tab.invites", "招募玩家");
            };
        }
    }

    private static Tab activeTab = Tab.COLONIES;
    private static final int[] scrollOffsets = new int[Tab.values().length];
    private static String toastMessage = "";
    private static long toastExpiryTime = 0;

    private static boolean transferMode = false;
    @Nullable
    private static UUID transferModeColony = null;

    private static final ColonyRole[] INVITE_ROLE_CYCLE = { ColonyRole.MEMBER, ColonyRole.ALLY, ColonyRole.MANAGER };
    private static int inviteRoleIndex = 0;

    private ColonyManagementOverlay() {}

    public static boolean isActive() {
        return WandscapePanelState.isPanelOpen()
                && !WandscapePanelState.isPanelHidden()
                && WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.COLONY;
    }

    public static Tab getActiveTab() {
        return activeTab;
    }

    public static void setActiveTab(Tab tab) {
        activeTab = tab;
    }

    public static void showToast(String message) {
        toastMessage = message;
        toastExpiryTime = System.currentTimeMillis() + 2500;
    }

    public static void playClickSound() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }

    public static void collapseToPrevious() {
        exitTransferMode();
        WandscapePanelState.exitCurrentSubMode();
        if (!OverviewClientState.isActive()) {
            WandscapePanelState.setSubMode(WandscapePanelState.SubMode.NONE);
            WandscapePanelState.syncCursorToState();
        }
    }

    // ── Permissions and State Helpers ──

    @Nullable
    public static UUID currentColonyId() {
        return WandscapePanelState.getColonyId();
    }

    @Nullable
    public static ColonyRole currentRole() {
        return ColonyPanelClientState.roleOf(currentColonyId());
    }

    public static boolean canSwitch(@Nullable ColonyRole role) {
        return role != null && role.atLeast(ColonyRole.MEMBER);
    }

    public static boolean canGovern() {
        ColonyRole role = currentRole();
        return role != null && role.canGovern();
    }

    public static boolean canInvite() {
        ColonyRole role = currentRole();
        return role != null && role.atLeast(ColonyRole.MANAGER);
    }

    public static boolean isTransferMode() {
        return transferMode;
    }

    public static void toggleTransferMode() {
        if (transferMode) {
            exitTransferMode();
            showToast(I18n.string("gui.wandscape.colony_members.transfer.cancel", "取消转让"));
            return;
        }
        if (!canGovern()) return;
        transferMode = true;
        transferModeColony = currentColonyId();
        showToast(I18n.string("gui.wandscape.colony.transfer_mode_banner", "处于【转让镇长】模式：点击下方任意成员的「转让镇长」即可移交职位"));
    }

    public static void exitTransferMode() {
        transferMode = false;
        transferModeColony = null;
    }

    public static void syncTransferMode(@Nullable UUID current) {
        if (transferMode && (!canGovern() || !Objects.equals(transferModeColony, current))) {
            exitTransferMode();
        }
    }

    public static ColonyRole inviteRole() {
        List<ColonyRole> options = inviteRoleOptions();
        if (options.isEmpty()) return ColonyRole.MEMBER;
        if (inviteRoleIndex < 0 || inviteRoleIndex >= options.size()) inviteRoleIndex = 0;
        return options.get(inviteRoleIndex);
    }

    public static void cycleInviteRole() {
        List<ColonyRole> options = inviteRoleOptions();
        if (options.size() <= 1) return;
        inviteRoleIndex = (inviteRoleIndex + 1) % options.size();
    }

    public static List<ColonyRole> inviteRoleOptions() {
        ColonyRole mine = currentRole();
        if (mine == null) return List.of();
        List<ColonyRole> out = new ArrayList<>(INVITE_ROLE_CYCLE.length);
        for (ColonyRole role : INVITE_ROLE_CYCLE) {
            if (role.rank() < mine.rank()) out.add(role);
        }
        return out;
    }

    public static ColonyRole nextManageableRole(ColonyRole current) {
        if (current == null) return ColonyRole.MEMBER;
        return switch (current) {
            case ALLY -> ColonyRole.MEMBER;
            case MEMBER -> ColonyRole.MANAGER;
            case MANAGER -> ColonyRole.ALLY;
            case OWNER -> ColonyRole.MANAGER;
        };
    }

    public static String roleName(@Nullable ColonyRole role) {
        if (role == null) return I18n.string("gui.wandscape.colony_network.role.none", "非成员");
        return switch (role) {
            case OWNER -> I18n.string("gui.wandscape.colony_network.role.owner", "镇长");
            case MANAGER -> I18n.string("gui.wandscape.colony_network.role.manager", "管事");
            case MEMBER -> I18n.string("gui.wandscape.colony_network.role.member", "成员");
            case ALLY -> I18n.string("gui.wandscape.colony_network.role.ally", "盟友");
        };
    }

    public record OnlinePlayer(UUID id, String name) {}

    public static List<OnlinePlayer> getOnlinePlayers() {
        Minecraft mc = Minecraft.getInstance();
        var connection = mc.getConnection();
        if (connection == null || mc.player == null) return List.of();

        Set<UUID> known = new HashSet<>();
        for (MemberEntry member : ColonyPanelClientState.membersOf(currentColonyId())) {
            if (member.id() != null) known.add(member.id());
        }
        known.add(mc.player.getUUID());

        List<OnlinePlayer> out = new ArrayList<>();
        for (var info : connection.getOnlinePlayers()) {
            var profile = info.getProfile();
            if (profile == null || profile.getId() == null) continue;
            if (known.contains(profile.getId())) continue;
            String name = profile.getName();
            out.add(new OnlinePlayer(profile.getId(), (name == null || name.isEmpty()) ? "?" : name));
        }
        out.sort(Comparator.comparing(OnlinePlayer::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // ── Row Item Hierarchy ──

    public sealed interface RowItem {
        int height();

        record SectionHeader(String title) implements RowItem {
            @Override public int height() { return 22; }
        }

        record InviteCard(InviteEntry invite) implements RowItem {
            @Override public int height() { return CARD_H; }
        }

        record ColonyCard(ColonyEntry colony, boolean isCurrent, boolean isOwned) implements RowItem {
            @Override public int height() { return CARD_H; }
        }

        record MemberCard(MemberEntry member, boolean isSelf, boolean isOwnerMember) implements RowItem {
            @Override public int height() { return CARD_H; }
        }

        record OnlinePlayerCard(OnlinePlayer player) implements RowItem {
            @Override public int height() { return CARD_H; }
        }

        record BannerCard(String text) implements RowItem {
            @Override public int height() { return 28; }
        }

        record MessageCard(String title, String subtitle, boolean isWarning) implements RowItem {
            @Override public int height() { return CARD_H; }
        }
    }

    public static List<RowItem> getRows(Tab tab) {
        return switch (tab) {
            case COLONIES -> getColoniesRows();
            case MEMBERS -> getMembersRows();
            case INVITES -> getInvitesRows();
        };
    }

    private static List<RowItem> getColoniesRows() {
        List<RowItem> list = new ArrayList<>();
        List<InviteEntry> pending = ColonyPanelClientState.getPendingInvites();
        if (!pending.isEmpty()) {
            list.add(new RowItem.SectionHeader(I18n.string("gui.wandscape.colony.section.invites", "待处理邀请 (%s)", String.valueOf(pending.size()))));
            for (InviteEntry invite : pending) {
                list.add(new RowItem.InviteCard(invite));
            }
        }

        List<ColonyEntry> all = ColonyPanelClientState.getColonies();
        List<ColonyEntry> owned = new ArrayList<>();
        List<ColonyEntry> joined = new ArrayList<>();
        UUID currentId = currentColonyId();
        for (ColonyEntry c : all) {
            if (c.myRole() == ColonyRole.OWNER) {
                owned.add(c);
            } else {
                joined.add(c);
            }
        }

        list.add(new RowItem.SectionHeader(I18n.string("gui.wandscape.colony.section.owned", "我拥有的小镇 (%s)", String.valueOf(owned.size()))));
        if (owned.isEmpty()) {
            list.add(new RowItem.MessageCard(
                    I18n.string("gui.wandscape.colony_switch.section.owned", "我拥有的"),
                    I18n.string("gui.wandscape.colony.no_owned_colonies", "尚未创立属于自己的小镇：在世界中建造市政厅并右键命名即可创立"),
                    false));
        } else {
            for (ColonyEntry c : owned) {
                list.add(new RowItem.ColonyCard(c, c.colonyId() != null && c.colonyId().equals(currentId), true));
            }
        }

        if (!joined.isEmpty()) {
            list.add(new RowItem.SectionHeader(I18n.string("gui.wandscape.colony.section.joined", "我参与的小镇 (%s)", String.valueOf(joined.size()))));
            for (ColonyEntry c : joined) {
                list.add(new RowItem.ColonyCard(c, c.colonyId() != null && c.colonyId().equals(currentId), false));
            }
        }

        if (pending.isEmpty() && all.isEmpty()) {
            list.add(new RowItem.MessageCard(
                    I18n.string("gui.wandscape.colony_switch.empty", "尚未加入任何小镇"),
                    I18n.string("gui.wandscape.colony_switch.guide", "新建自己的小镇：建造市政厅后右键命名"),
                    false));
        }
        return list;
    }

    private static List<RowItem> getMembersRows() {
        List<RowItem> list = new ArrayList<>();
        UUID currentId = currentColonyId();
        if (currentId == null) {
            list.add(new RowItem.MessageCard(
                    I18n.string("gui.wandscape.colony_members.hint.select", "尚未选择或加入小镇"),
                    I18n.string("gui.wandscape.colony.hint.no_colony", "尚未选择或加入小镇：请先在「我的小镇」页选择或创建小镇"),
                    false));
            return list;
        }

        if (transferMode && canGovern()) {
            list.add(new RowItem.BannerCard(
                    I18n.string("gui.wandscape.colony.transfer_mode_banner", "处于【转让镇长】模式：点击下方任意成员的「转让镇长」即可移交职位")));
        }

        List<MemberEntry> members = ColonyPanelClientState.membersOf(currentId);
        if (members.isEmpty()) {
            list.add(new RowItem.MessageCard(
                    I18n.string("gui.wandscape.colony_members.empty.members", "暂无成员"),
                    "", false));
        } else {
            Minecraft mc = Minecraft.getInstance();
            UUID selfUuid = mc.player != null ? mc.player.getUUID() : null;
            for (MemberEntry m : members) {
                boolean isSelf = m.id() != null && m.id().equals(selfUuid);
                boolean isOwner = m.role() == ColonyRole.OWNER;
                list.add(new RowItem.MemberCard(m, isSelf, isOwner));
            }
        }
        return list;
    }

    private static List<RowItem> getInvitesRows() {
        List<RowItem> list = new ArrayList<>();
        UUID currentId = currentColonyId();
        if (currentId == null) {
            list.add(new RowItem.MessageCard(
                    I18n.string("gui.wandscape.colony_members.hint.select", "尚未选择或加入小镇"),
                    I18n.string("gui.wandscape.colony.hint.no_colony", "尚未选择或加入小镇：请先在「我的小镇」页选择或创建小镇"),
                    false));
            return list;
        }
        if (!canInvite()) {
            list.add(new RowItem.MessageCard(
                    I18n.string("gui.wandscape.colony_members.hint.no_permission", "需管理员及以上档位"),
                    I18n.string("gui.wandscape.colony.hint.invites_no_perm", "权限不足：需管事及以上档位才可邀请玩家"),
                    true));
            return list;
        }

        List<OnlinePlayer> players = getOnlinePlayers();
        if (players.isEmpty()) {
            list.add(new RowItem.MessageCard(
                    I18n.string("gui.wandscape.colony_members.empty.online", "没有可邀请的在线玩家"),
                    I18n.string("gui.wandscape.colony.no_online_players", "当前没有可邀请的在线玩家（其他玩家已全在本镇或处于离线）"),
                    false));
        } else {
            for (OnlinePlayer p : players) {
                list.add(new RowItem.OnlinePlayerCard(p));
            }
        }
        return list;
    }

    // ── Header Layout Engine ──

    public record HeaderLayout(
            String titleDraw,
            int titleX,
            int titleY,
            String[] tabLabels,
            int[] tabX,
            int[] tabW,
            int closeX,
            int closeY,
            int closeW,
            int closeH
    ) {
        public int getTabAt(double mx, double my) {
            int btnY = 6;
            int btnH = 22;
            if (my < btnY || my > btnY + btnH) return -1;
            for (int i = 0; i < tabX.length; i++) {
                if (mx >= tabX[i] && mx <= tabX[i] + tabW[i]) {
                    return i;
                }
            }
            return -1;
        }

        public boolean isCloseHovered(double mx, double my) {
            return mx >= closeX && mx <= closeX + closeW && my >= closeY && my <= closeY + closeH;
        }
    }

    public static HeaderLayout layoutHeader(Font font, int screenW) {
        String closeText = I18n.string("gui.wandscape.settings.close", "返回 (ESC)");
        int closeW = Math.max(76, strWidth(font, closeText) + 16);
        int closeX = screenW - closeW - 12;
        int closeY = 6;
        int closeH = 22;
        int contentRight = closeX - 10;
        int leftMargin = 16;
        int avail = Math.max(50, contentRight - leftMargin);

        String colony = WandscapePanelState.getColonyName();
        String fullTitle = I18n.string("gui.wandscape.colony.title", "%s 小镇管理",
                (colony != null && !colony.isEmpty())
                        ? colony
                        : I18n.string("gui.wandscape.settings.default_town_name", "魔法小镇"));
        String shortTitle = I18n.string("gui.wandscape.colony.title_short", "小镇管理");

        Tab[] tabs = Tab.values();
        int tabCount = tabs.length;
        String[] labels = new String[tabCount];
        int[] rawW = new int[tabCount];
        for (int i = 0; i < tabCount; i++) {
            labels[i] = tabs[i].getDisplayName();
            rawW[i] = strWidth(font, labels[i]);
        }

        int pad = TAB_PADS[TAB_PADS.length - 1];
        int gap = TAB_GAPS[TAB_GAPS.length - 1];
        for (int tier = 0; tier < TAB_PADS.length; tier++) {
            if (sumWidths(rawW, TAB_PADS[tier], TAB_GAPS[tier]) <= avail) {
                pad = TAB_PADS[tier];
                gap = TAB_GAPS[tier];
                break;
            }
        }
        if (sumWidths(rawW, pad, gap) > avail) {
            int cellW = Math.max(12, (avail - gap * (tabCount - 1)) / tabCount - pad);
            for (int i = 0; i < tabCount; i++) {
                labels[i] = font.plainSubstrByWidth(labels[i], cellW);
                rawW[i] = strWidth(font, labels[i]);
            }
        }
        int totalTabsW = sumWidths(rawW, pad, gap);

        String chosenTitle = "";
        int titleBudget = avail - totalTabsW - TITLE_GAP;
        if (titleBudget >= strWidth(font, shortTitle)) {
            chosenTitle = (strWidth(font, fullTitle) <= titleBudget) ? fullTitle : shortTitle;
        }

        int titleEnd = chosenTitle.isEmpty() ? leftMargin : (leftMargin + strWidth(font, chosenTitle) + TITLE_GAP);

        int startX;
        if (!chosenTitle.isEmpty()) {
            int remaining = contentRight - titleEnd;
            startX = titleEnd + Math.max(0, (remaining - totalTabsW) / 2);
        } else {
            startX = leftMargin + Math.max(0, (avail - totalTabsW) / 2);
        }
        startX = Math.max(titleEnd, Math.min(startX, contentRight - totalTabsW));

        int[] tabX = new int[tabCount];
        int[] tabW = new int[tabCount];
        int curX = startX;
        for (int i = 0; i < tabCount; i++) {
            tabX[i] = curX;
            tabW[i] = rawW[i] + pad;
            curX += tabW[i] + gap;
        }

        return new HeaderLayout(chosenTitle, leftMargin, 12, labels, tabX, tabW, closeX, closeY, closeW, closeH);
    }

    private static int strWidth(Font font, String str) {
        if (str == null || str.isEmpty()) return 0;
        return (font != null) ? font.width(str) : str.length() * 6;
    }

    private static int sumWidths(int[] rawW, int pad, int gap) {
        int sum = 0;
        for (int w : rawW) {
            sum += w + pad;
        }
        sum += gap * (rawW.length - 1);
        return sum;
    }

    // ── Render Pipeline ──

    public static void render(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        if (!isActive()) return;

        try {
            syncTransferMode(currentColonyId());

            // 1. Semi-transparent backdrop over game world
            g.fill(RenderType.guiOverlay(), 0, 0, screenW, screenH, 0, BG_BACKDROP);

            // 2. Top Header Bar
            renderHeader(g, font, screenW, mx, my);

            // 3. Sub-Header Toolbar
            renderToolbar(g, font, screenW, mx, my);

            // 4. Main Colony Card List
            renderCardList(g, font, screenW, screenH, mx, my);

            // 5. Toast feedback message
            renderToast(g, font, screenW, screenH);
        } catch (Throwable t) {
            Log.warn(TAG, "[Colony] 界面渲染异常: {}", t.toString());
        }
    }

    private static void renderHeader(GuiGraphics g, Font font, int screenW, double mx, double my) {
        g.fill(RenderType.guiOverlay(), 0, 0, screenW, HEADER_H, 0, HEADER_BG);
        g.fill(RenderType.guiOverlay(), 0, HEADER_H - 1, screenW, HEADER_H, 0, BORDER_GOLD);

        HeaderLayout lo = layoutHeader(font, screenW);

        // Title on the left
        if (!lo.titleDraw().isEmpty()) {
            g.drawString(font, lo.titleDraw(), lo.titleX(), lo.titleY(), WandscapeTheme.COLOR_TEXT_ACTIVE, false);
        }

        int btnY = lo.closeY();
        int btnH = lo.closeH();

        // Tabs
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab tab = tabs[i];
            String label = lo.tabLabels()[i];
            int tabX = lo.tabX()[i];
            int tabW = lo.tabW()[i];
            boolean active = (tab == activeTab);
            boolean hover = mx >= tabX && mx <= tabX + tabW && my >= btnY && my <= btnY + btnH;

            int bg = active ? 0xFF2A3240 : (hover ? 0x883E4A5E : 0x441E242E);
            g.fill(RenderType.guiOverlay(), tabX, btnY, tabX + tabW, btnY + btnH, 0, bg);
            if (active) {
                g.fill(RenderType.guiOverlay(), tabX, btnY + btnH - 2, tabX + tabW, btnY + btnH, 0, BORDER_GOLD);
            }
            int textColor = active ? WandscapeTheme.COLOR_TEXT_ACTIVE : (hover ? 0xFFFFFFFF : WandscapeTheme.COLOR_TEXT_NORMAL);
            g.drawString(font, label, tabX + (tabW - font.width(label)) / 2, btnY + 7, textColor, false);
        }

        // Close / Exit Button [返回 (ESC)]
        boolean closeHover = lo.isCloseHovered(mx, my);
        int closeBg = closeHover ? 0xCCE53935 : 0x883A2020;
        g.fill(RenderType.guiOverlay(), lo.closeX(), lo.closeY(), lo.closeX() + lo.closeW(), lo.closeY() + lo.closeH(), 0, closeBg);
        String closeText = I18n.string("gui.wandscape.settings.close", "返回 (ESC)");
        g.drawString(font, closeText, lo.closeX() + (lo.closeW() - font.width(closeText)) / 2, lo.closeY() + 7, 0xFFFFFFFF, false);
    }

    private static void renderToolbar(GuiGraphics g, Font font, int screenW, double mx, double my) {
        int y = HEADER_H;
        g.fill(RenderType.guiOverlay(), 0, y, screenW, y + TOOLBAR_H, 0, TOOLBAR_BG);

        int rBtnW = 0;
        int rBtnH = 18;
        int rBtnX = 0;
        int rBtnY = y + 4;

        if (activeTab == Tab.MEMBERS && canGovern()) {
            rBtnW = 86;
            rBtnX = screenW - rBtnW - 20;
            boolean rHover = mx >= rBtnX && mx <= rBtnX + rBtnW && my >= rBtnY && my <= rBtnY + rBtnH;
            int rBg = transferMode ? (rHover ? 0xFFE53935 : 0xCCB71C1C) : (rHover ? 0xFFC8A040 : 0x44262E3B);
            int rTextColor = transferMode ? 0xFFFFFFFF : (rHover ? 0xFF111214 : WandscapeTheme.COLOR_TEXT_NORMAL);
            g.fill(RenderType.guiOverlay(), rBtnX, rBtnY, rBtnX + rBtnW, rBtnY + rBtnH, 0, rBg);
            String rText = transferMode
                    ? I18n.string("gui.wandscape.colony_members.transfer.cancel", "取消转让")
                    : I18n.string("gui.wandscape.colony_members.transfer.start", "转让镇长");
            g.drawString(font, rText, rBtnX + (rBtnW - font.width(rText)) / 2, rBtnY + 5, rTextColor, false);
        } else if (activeTab == Tab.INVITES && canInvite()) {
            rBtnW = 106;
            rBtnX = screenW - rBtnW - 20;
            boolean rHover = mx >= rBtnX && mx <= rBtnX + rBtnW && my >= rBtnY && my <= rBtnY + rBtnH;
            int rBg = rHover ? 0xFFC8A040 : 0x44262E3B;
            int rTextColor = rHover ? 0xFF111214 : WandscapeTheme.COLOR_TEXT_NORMAL;
            g.fill(RenderType.guiOverlay(), rBtnX, rBtnY, rBtnX + rBtnW, rBtnY + rBtnH, 0, rBg);
            String rText = I18n.string("gui.wandscape.colony_members.invite_role", "邀请档位: %s", roleName(inviteRole()));
            g.drawString(font, rText, rBtnX + (rBtnW - font.width(rText)) / 2, rBtnY + 5, rTextColor, false);
        }

        String hint;
        int hintColor = WandscapeTheme.COLOR_TEXT_DIM;
        UUID cid = currentColonyId();
        String cName = WandscapePanelState.getColonyName();
        int cLevel = WandscapePanelState.getColonyLevel();

        if (activeTab == Tab.COLONIES) {
            hint = I18n.string("gui.wandscape.colony.hint.colonies", "管理小镇归属、切换当前镇，或处理入镇邀请");
        } else if (activeTab == Tab.MEMBERS) {
            if (cid != null) {
                int memberCount = ColonyPanelClientState.membersOf(cid).size();
                hint = I18n.string("gui.wandscape.colony.hint.members", "当前小镇：%s (Lv.%s) · 身份: %s · 成员: %s人",
                        (cName != null && !cName.isEmpty()) ? cName : "魔法小镇",
                        String.valueOf(cLevel),
                        roleName(currentRole()),
                        String.valueOf(memberCount));
            } else {
                hint = I18n.string("gui.wandscape.colony.hint.no_colony", "尚未选择或加入小镇：请先在「我的小镇」页选择或创建小镇");
                hintColor = 0xFFFFB74D;
            }
        } else {
            if (cid == null) {
                hint = I18n.string("gui.wandscape.colony.hint.no_colony", "尚未选择或加入小镇：请先在「我的小镇」页选择或创建小镇");
                hintColor = 0xFFFFB74D;
            } else if (!canInvite()) {
                hint = I18n.string("gui.wandscape.colony.hint.invites_no_perm", "权限不足：需管事及以上档位才可邀请玩家");
                hintColor = 0xFFFFB74D;
            } else {
                hint = I18n.string("gui.wandscape.colony.hint.invites", "邀请在线玩家加入当前小镇 (%s)",
                        (cName != null && !cName.isEmpty()) ? cName : "魔法小镇");
            }
        }

        int maxHintW = rBtnW > 0 ? (rBtnX - 20 - 8) : (screenW - 40);
        g.drawString(font, font.plainSubstrByWidth(hint, Math.max(0, maxHintW)), 20, y + 8, hintColor, false);
    }

    private static void renderCardList(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        int listY = HEADER_H + TOOLBAR_H + 8;
        int listH = screenH - listY - 10;
        int listW = Math.min(screenW - 40, 780);
        int padX = (screenW - listW) / 2;

        List<RowItem> items = getRows(activeTab);
        int totalContentH = 0;
        for (int i = 0; i < items.size(); i++) {
            totalContentH += items.get(i).height();
            if (i < items.size() - 1) totalContentH += CARD_GAP;
        }

        int maxScroll = Math.max(0, totalContentH - listH);
        int offset = Math.max(0, Math.min(maxScroll, scrollOffsets[activeTab.ordinal()]));
        scrollOffsets[activeTab.ordinal()] = offset;

        g.enableScissor(padX - 2, listY, padX + listW + 16, listY + listH);

        int curY = listY - offset;
        for (RowItem item : items) {
            int h = item.height();
            if (curY + h >= listY && curY <= listY + listH) {
                renderItem(g, font, padX, curY, listW, h, item, mx, my);
            }
            curY += h + CARD_GAP;
        }

        g.disableScissor();

        // Scrollbar if content overflows
        if (maxScroll > 0) {
            int sbX = padX + listW + 4;
            int sbW = 4;
            g.fill(RenderType.guiOverlay(), sbX, listY, sbX + sbW, listY + listH, 0, 0x33FFFFFF);
            int thumbH = Math.max(20, (int) ((float) listH / totalContentH * listH));
            int thumbY = listY + (int) ((float) offset / maxScroll * (listH - thumbH));
            g.fill(RenderType.guiOverlay(), sbX, thumbY, sbX + sbW, thumbY + thumbH, 0, BORDER_GOLD);
        }
    }

    private static void renderItem(GuiGraphics g, Font font, int x, int y, int w, int h,
                                   RowItem item, double mx, double my) {
        switch (item) {
            case RowItem.SectionHeader sec -> {
                g.drawString(font, sec.title(), x + 2, y + 6, WandscapeTheme.COLOR_TEXT_ACTIVE, false);
                int titleW = font.width(sec.title());
                g.fill(RenderType.guiOverlay(), x + titleW + 10, y + 10, x + w, y + 11, 0, 0x44C8A040);
            }
            case RowItem.BannerCard banner -> {
                g.fill(RenderType.guiOverlay(), x, y, x + w, y + h, 0, 0x33FFB74D);
                g.fill(RenderType.guiOverlay(), x, y, x + w, y + 1, 0, 0xFFFFB74D);
                g.fill(RenderType.guiOverlay(), x, y + h - 1, x + w, y + h, 0, 0xFFFFB74D);
                g.drawString(font, banner.text(), x + 10, y + (h - font.lineHeight) / 2 + 1, 0xFFFFD54F, false);
            }
            case RowItem.MessageCard msg -> {
                g.fill(RenderType.guiOverlay(), x, y, x + w, y + h, 0, 0x88181D26);
                g.fill(RenderType.guiOverlay(), x, y, x + w, y + 1, 0, WandscapeTheme.COLOR_BORDER_NORMAL);
                g.fill(RenderType.guiOverlay(), x, y + h - 1, x + w, y + h, 0, WandscapeTheme.COLOR_BORDER_NORMAL);
                int badgeColor = msg.isWarning() ? 0xFFFFB74D : 0xFF81C784;
                int badgeBg = msg.isWarning() ? 0x33FFB74D : 0x3381C784;
                String badgeText = msg.isWarning() ? "[提示]" : "[信息]";
                drawBadge(g, font, x + 10, y + 6, badgeText, badgeColor, badgeBg);
                g.drawString(font, msg.title(), x + 10 + font.width(badgeText) + 8, y + 8, 0xFFDDDDDD, false);
                if (!msg.subtitle().isEmpty()) {
                    g.drawString(font, msg.subtitle(), x + 10, y + 25, 0xFF888888, false);
                }
            }
            case RowItem.InviteCard inv -> {
                boolean cardHover = mx >= x && mx <= x + w && my >= y && my <= y + h;
                g.fill(RenderType.guiOverlay(), x, y, x + w, y + h, 0, cardHover ? CARD_BG_HOVER : CARD_BG);
                g.fill(RenderType.guiOverlay(), x, y, x + w, y + 1, 0, cardHover ? BORDER_GOLD : WandscapeTheme.COLOR_BORDER_NORMAL);
                g.fill(RenderType.guiOverlay(), x, y + h - 1, x + w, y + h, 0, WandscapeTheme.COLOR_BORDER_NORMAL);

                String badge = I18n.string("gui.wandscape.colony.badge.invite", "[邀请函]");
                drawBadge(g, font, x + 10, y + 6, badge, 0xFFFFD54F, 0x33FFD54F);
                g.drawString(font, inv.invite().colonyName(), x + 10 + font.width(badge) + 8, y + 8, 0xFFFFFFFF, false);

                String info = I18n.string("gui.wandscape.colony_members.invite_row", "%s 由 %s 邀请（%s）",
                        inv.invite().colonyName(), inv.invite().inviterName(), roleName(inv.invite().role()));
                g.drawString(font, info, x + 10, y + 25, 0xFFAAAAAA, false);

                // Right Buttons: [接受] [拒绝]
                int rightEdge = x + w - 10;
                int btnDeclineW = 46;
                int btnDeclineX = rightEdge - btnDeclineW;
                int btnAcceptW = 46;
                int btnAcceptX = btnDeclineX - btnAcceptW - 6;
                int btnY = y + 12;
                int btnH = 22;

                boolean aHover = mx >= btnAcceptX && mx <= btnAcceptX + btnAcceptW && my >= btnY && my <= btnY + btnH;
                boolean dHover = mx >= btnDeclineX && mx <= btnDeclineX + btnDeclineW && my >= btnY && my <= btnY + btnH;

                drawButton(g, font, btnAcceptX, btnY, btnAcceptW, btnH,
                        I18n.string("gui.wandscape.colony_members.accept", "接受"),
                        0x442E7D32, 0xFF4CAF50, 0xFFFFFFFF, 0xFFFFFFFF, aHover);
                drawButton(g, font, btnDeclineX, btnY, btnDeclineW, btnH,
                        I18n.string("gui.wandscape.colony_members.decline", "拒绝"),
                        0x44C62828, 0xFFE53935, 0xFFFFFFFF, 0xFFFFFFFF, dHover);
            }
            case RowItem.ColonyCard col -> {
                boolean cardHover = mx >= x && mx <= x + w && my >= y && my <= y + h;
                int bg = col.isCurrent() ? 0xDD1E2532 : (cardHover ? CARD_BG_HOVER : CARD_BG);
                g.fill(RenderType.guiOverlay(), x, y, x + w, y + h, 0, bg);
                g.fill(RenderType.guiOverlay(), x, y, x + w, y + 1, 0, (col.isCurrent() || cardHover) ? BORDER_GOLD : WandscapeTheme.COLOR_BORDER_NORMAL);
                g.fill(RenderType.guiOverlay(), x, y + h - 1, x + w, y + h, 0, WandscapeTheme.COLOR_BORDER_NORMAL);

                int badgeX = x + 10;
                String rBadge = "[" + roleName(col.colony().myRole()) + "]";
                int rColor = roleColor(col.colony().myRole());
                int rBg = roleBg(col.colony().myRole());
                drawBadge(g, font, badgeX, y + 6, rBadge, rColor, rBg);
                badgeX += font.width(rBadge) + 6;

                if (col.isCurrent()) {
                    String curBadge = I18n.string("gui.wandscape.colony.badge.current", "[当前]");
                    drawBadge(g, font, badgeX, y + 6, curBadge, 0xFF81C784, 0x3381C784);
                    badgeX += font.width(curBadge) + 6;
                }

                String cName = (col.colony().name() == null || col.colony().name().isEmpty())
                        ? shortId(col.colony().colonyId()) : col.colony().name();
                int titleColor = col.isCurrent() ? WandscapeTheme.COLOR_TEXT_ACTIVE : (cardHover ? 0xFFFFFFFF : 0xFFDDDDDD);
                g.drawString(font, cName, badgeX + 4, y + 8, titleColor, false);

                String lvlStr = I18n.string("gui.wandscape.colony_switch.entry", "%s  Lv.%s", "", String.valueOf(col.colony().level())).trim();
                g.drawString(font, lvlStr, x + 10, y + 25, 0xFFAAAAAA, false);

                // Right Button / Status
                int rightEdge = x + w - 10;
                if (col.isCurrent()) {
                    String selectedText = I18n.string("gui.wandscape.colony.btn.selected", "当前小镇");
                    g.drawString(font, selectedText, rightEdge - font.width(selectedText), y + 18, WandscapeTheme.COLOR_TEXT_ACTIVE, false);
                } else if (canSwitch(col.colony().myRole())) {
                    int btnW = 68;
                    int btnH = 22;
                    int btnX = rightEdge - btnW;
                    int btnY = y + 12;
                    boolean bHover = mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH;
                    drawButton(g, font, btnX, btnY, btnW, btnH,
                            I18n.string("gui.wandscape.colony.btn.switch", "切换小镇"),
                            0x44262E3B, 0xFFC8A040, WandscapeTheme.COLOR_TEXT_NORMAL, 0xFF111214, bHover);
                } else {
                    String disabledText = I18n.string("gui.wandscape.colony.btn.ally_disabled", "盟友不可切换");
                    g.drawString(font, disabledText, rightEdge - font.width(disabledText), y + 18, 0xFF666666, false);
                }
            }
            case RowItem.MemberCard mem -> {
                boolean cardHover = mx >= x && mx <= x + w && my >= y && my <= y + h;
                g.fill(RenderType.guiOverlay(), x, y, x + w, y + h, 0, cardHover ? CARD_BG_HOVER : CARD_BG);
                g.fill(RenderType.guiOverlay(), x, y, x + w, y + 1, 0, cardHover ? BORDER_GOLD : WandscapeTheme.COLOR_BORDER_NORMAL);
                g.fill(RenderType.guiOverlay(), x, y + h - 1, x + w, y + h, 0, WandscapeTheme.COLOR_BORDER_NORMAL);

                int badgeX = x + 10;
                String rBadge = "[" + roleName(mem.member().role()) + "]";
                int rColor = roleColor(mem.member().role());
                int rBg = roleBg(mem.member().role());
                drawBadge(g, font, badgeX, y + 6, rBadge, rColor, rBg);
                badgeX += font.width(rBadge) + 6;

                if (mem.isSelf()) {
                    String selfBadge = I18n.string("gui.wandscape.colony.badge.self", "[我自己]");
                    drawBadge(g, font, badgeX, y + 6, selfBadge, 0xFFAAAAAA, 0x22AAAAAA);
                    badgeX += font.width(selfBadge) + 6;
                }

                int nameColor = cardHover ? WandscapeTheme.COLOR_TEXT_ACTIVE : 0xFFFFFFFF;
                g.drawString(font, mem.member().name(), badgeX + 4, y + 8, nameColor, false);

                // Line 2: Role explanation
                String roleDesc = roleDescription(mem.member().role());
                g.drawString(font, roleDesc, x + 10, y + 25, 0xFF888888, false);

                // Right controls (if governor)
                int rightEdge = x + w - 10;
                if (canGovern()) {
                    if (mem.isOwnerMember()) {
                        String leaderText = I18n.string("gui.wandscape.colony_network.role.owner", "镇长");
                        g.drawString(font, leaderText, rightEdge - font.width(leaderText), y + 18, WandscapeTheme.COLOR_TEXT_ACTIVE, false);
                    } else if (transferMode) {
                        int btnW = 76;
                        int btnH = 22;
                        int btnX = rightEdge - btnW;
                        int btnY = y + 12;
                        boolean bHover = mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH;
                        drawButton(g, font, btnX, btnY, btnW, btnH,
                                I18n.string("gui.wandscape.colony.btn.transfer_to", "转让镇长"),
                                0xCCB71C1C, 0xFFE53935, 0xFFFFFFFF, 0xFFFFFFFF, bHover);
                    } else {
                        int btnRemoveW = 46;
                        int btnRemoveX = rightEdge - btnRemoveW;
                        int btnRoleW = 82;
                        int btnRoleX = btnRemoveX - btnRoleW - 6;
                        int btnY = y + 12;
                        int btnH = 22;

                        boolean rHover = mx >= btnRoleX && mx <= btnRoleX + btnRoleW && my >= btnY && my <= btnY + btnH;
                        boolean rmHover = mx >= btnRemoveX && mx <= btnRemoveX + btnRemoveW && my >= btnY && my <= btnY + btnH;

                        String roleBtnText = I18n.string("gui.wandscape.colony.btn.role_set", "档位: %s", roleName(mem.member().role()));
                        drawButton(g, font, btnRoleX, btnY, btnRoleW, btnH, roleBtnText,
                                0x44262E3B, 0xFFC8A040, WandscapeTheme.COLOR_TEXT_NORMAL, 0xFF111214, rHover);
                        drawButton(g, font, btnRemoveX, btnY, btnRemoveW, btnH,
                                I18n.string("gui.wandscape.colony.btn.remove", "移除"),
                                0x44C62828, 0xFFE53935, 0xFFFFFFFF, 0xFFFFFFFF, rmHover);
                    }
                }
            }
            case RowItem.OnlinePlayerCard ply -> {
                boolean cardHover = mx >= x && mx <= x + w && my >= y && my <= y + h;
                g.fill(RenderType.guiOverlay(), x, y, x + w, y + h, 0, cardHover ? CARD_BG_HOVER : CARD_BG);
                g.fill(RenderType.guiOverlay(), x, y, x + w, y + 1, 0, cardHover ? BORDER_GOLD : WandscapeTheme.COLOR_BORDER_NORMAL);
                g.fill(RenderType.guiOverlay(), x, y + h - 1, x + w, y + h, 0, WandscapeTheme.COLOR_BORDER_NORMAL);

                int badgeX = x + 10;
                String onBadge = I18n.string("gui.wandscape.colony.badge.online", "[在线]");
                drawBadge(g, font, badgeX, y + 6, onBadge, 0xFF4CAF50, 0x334CAF50);
                badgeX += font.width(onBadge) + 6;

                int nameColor = cardHover ? WandscapeTheme.COLOR_TEXT_ACTIVE : 0xFFFFFFFF;
                g.drawString(font, ply.player().name(), badgeX + 4, y + 8, nameColor, false);

                String sub = I18n.string("gui.wandscape.colony_members.invite_role", "拟授予: %s", roleName(inviteRole()));
                g.drawString(font, sub, x + 10, y + 25, 0xFFAAAAAA, false);

                // Right Button: [发送邀请]
                int rightEdge = x + w - 10;
                int btnW = 72;
                int btnH = 22;
                int btnX = rightEdge - btnW;
                int btnY = y + 12;
                boolean bHover = mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH;
                drawButton(g, font, btnX, btnY, btnW, btnH,
                        I18n.string("gui.wandscape.colony.btn.invite_send", "发送邀请"),
                        0x442E7D32, 0xFF4CAF50, 0xFFFFFFFF, 0xFFFFFFFF, bHover);
            }
        }
    }

    private static void drawBadge(GuiGraphics g, Font font, int x, int y, String text, int textColor, int bgColor) {
        int w = font.width(text) + 6;
        int h = font.lineHeight + 2;
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + h, 0, bgColor);
        g.drawString(font, text, x + 3, y + 2, textColor, false);
    }

    private static void drawButton(GuiGraphics g, Font font, int x, int y, int w, int h, String text,
                                   int normalBg, int hoverBg, int normalText, int hoverText, boolean hovered) {
        int bg = hovered ? hoverBg : normalBg;
        int txtColor = hovered ? hoverText : normalText;
        int border = hovered ? BORDER_GOLD : WandscapeTheme.COLOR_BORDER_NORMAL;
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + h, 0, bg);
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + 1, 0, border);
        g.fill(RenderType.guiOverlay(), x, y + h - 1, x + w, y + h, 0, border);
        g.fill(RenderType.guiOverlay(), x, y, x + 1, y + h, 0, border);
        g.fill(RenderType.guiOverlay(), x + w - 1, y, x + w, y + h, 0, border);
        g.drawString(font, text, x + (w - font.width(text)) / 2, y + (h - font.lineHeight) / 2 + 1, txtColor, false);
    }

    private static void renderToast(GuiGraphics g, Font font, int screenW, int screenH) {
        if (System.currentTimeMillis() > toastExpiryTime || toastMessage.isEmpty()) return;

        int toastW = font.width(toastMessage) + 24;
        int toastH = 22;
        int tx = (screenW - toastW) / 2;
        int ty = screenH - toastH - 16;

        g.fill(RenderType.guiOverlay(), tx, ty, tx + toastW, ty + toastH, 0, 0xEE1E242E);
        g.fill(RenderType.guiOverlay(), tx, ty, tx + toastW, ty + 1, 0, BORDER_GOLD);
        g.drawString(font, toastMessage, tx + 12, ty + 7, WandscapeTheme.COLOR_TEXT_ACTIVE, false);
    }

    // ── Mouse Click & Scroll Handlers ──

    public static boolean handleMouseClick(double mx, double my, int screenW, int screenH) {
        if (!isActive()) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;

        // 1. Header Clicks (Close button & Tabs)
        if (my <= HEADER_H) {
            HeaderLayout lo = layoutHeader(mc.font, screenW);

            if (lo.isCloseHovered(mx, my)) {
                collapseToPrevious();
                playClickSound();
                return true;
            }

            int clickedTab = lo.getTabAt(mx, my);
            if (clickedTab >= 0 && clickedTab < Tab.values().length) {
                setActiveTab(Tab.values()[clickedTab]);
                playClickSound();
                return true;
            }
            return true;
        }

        // 2. Toolbar Clicks
        if (my >= HEADER_H && my <= HEADER_H + TOOLBAR_H) {
            if (activeTab == Tab.MEMBERS && canGovern()) {
                int rBtnW = 86;
                int rBtnH = 18;
                int rBtnX = screenW - rBtnW - 20;
                int rBtnY = HEADER_H + 4;
                if (mx >= rBtnX && mx <= rBtnX + rBtnW && my >= rBtnY && my <= rBtnY + rBtnH) {
                    toggleTransferMode();
                    playClickSound();
                    return true;
                }
            } else if (activeTab == Tab.INVITES && canInvite()) {
                int rBtnW = 106;
                int rBtnH = 18;
                int rBtnX = screenW - rBtnW - 20;
                int rBtnY = HEADER_H + 4;
                if (mx >= rBtnX && mx <= rBtnX + rBtnW && my >= rBtnY && my <= rBtnY + rBtnH) {
                    cycleInviteRole();
                    playClickSound();
                    showToast(I18n.string("gui.wandscape.colony.toast.role_selected", "邀请档位已设为: %s", roleName(inviteRole())));
                    return true;
                }
            }
            return true;
        }

        // 3. Main List Clicks
        int listY = HEADER_H + TOOLBAR_H + 8;
        int listH = screenH - listY - 10;
        int listW = Math.min(screenW - 40, 780);
        int padX = (screenW - listW) / 2;

        if (my >= listY && my <= listY + listH && mx >= padX && mx <= padX + listW) {
            List<RowItem> items = getRows(activeTab);
            int offset = scrollOffsets[activeTab.ordinal()];
            int curY = listY - offset;

            for (RowItem item : items) {
                int h = item.height();
                if (my >= curY && my <= curY + h) {
                    if (handleClickRow(item, padX, curY, listW, h, mx, my, mc)) {
                        return true;
                    }
                }
                curY += h + CARD_GAP;
            }
            return true;
        }

        return true;
    }

    private static boolean handleClickRow(RowItem item, int x, int y, int w, int h,
                                          double mx, double my, Minecraft mc) {
        int rightEdge = x + w - 10;
        int btnY = y + 12;
        int btnH = 22;

        switch (item) {
            case RowItem.InviteCard inv -> {
                int btnDeclineW = 46;
                int btnDeclineX = rightEdge - btnDeclineW;
                int btnAcceptW = 46;
                int btnAcceptX = btnDeclineX - btnAcceptW - 6;

                if (mx >= btnAcceptX && mx <= btnAcceptX + btnAcceptW && my >= btnY && my <= btnY + btnH) {
                    Net.toServer(new ColonyMemberActionPacket(
                            ColonyMemberActionPacket.Action.ACCEPT,
                            inv.invite().colonyId(), mc.player.getUUID(), inv.invite().role()));
                    playClickSound();
                    showToast(I18n.string("gui.wandscape.colony.toast.accepted", "已接受入镇邀请"));
                    return true;
                }
                if (mx >= btnDeclineX && mx <= btnDeclineX + btnDeclineW && my >= btnY && my <= btnY + btnH) {
                    Net.toServer(new ColonyMemberActionPacket(
                            ColonyMemberActionPacket.Action.DECLINE,
                            inv.invite().colonyId(), mc.player.getUUID(), inv.invite().role()));
                    playClickSound();
                    showToast(I18n.string("gui.wandscape.colony.toast.declined", "已拒绝入镇邀请"));
                    return true;
                }
            }
            case RowItem.ColonyCard col -> {
                if (!col.isCurrent() && canSwitch(col.colony().myRole())) {
                    int btnW = 68;
                    int btnX = rightEdge - btnW;
                    if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                        Net.toServer(new ColonyMemberActionPacket(
                                ColonyMemberActionPacket.Action.SELECT,
                                col.colony().colonyId(), null, null));
                        playClickSound();
                        showToast(I18n.string("gui.wandscape.colony.toast.switched", "正在切换当前小镇..."));
                        return true;
                    }
                }
            }
            case RowItem.MemberCard mem -> {
                UUID cid = currentColonyId();
                if (canGovern() && cid != null && !mem.isOwnerMember()) {
                    if (transferMode) {
                        int btnW = 76;
                        int btnX = rightEdge - btnW;
                        if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                            Net.toServer(new ColonyMemberActionPacket(
                                    ColonyMemberActionPacket.Action.TRANSFER,
                                    cid, mem.member().id(), mem.member().role()));
                            exitTransferMode();
                            playClickSound();
                            showToast(I18n.string("gui.wandscape.colony.toast.transfer_sent", "已向 %s 发起镇长转让", mem.member().name()));
                            return true;
                        }
                    } else {
                        int btnRemoveW = 46;
                        int btnRemoveX = rightEdge - btnRemoveW;
                        int btnRoleW = 82;
                        int btnRoleX = btnRemoveX - btnRoleW - 6;

                        if (mx >= btnRoleX && mx <= btnRoleX + btnRoleW && my >= btnY && my <= btnY + btnH) {
                            ColonyRole nextRole = nextManageableRole(mem.member().role());
                            Net.toServer(new ColonyMemberActionPacket(
                                    ColonyMemberActionPacket.Action.SET_ROLE,
                                    cid, mem.member().id(), nextRole));
                            playClickSound();
                            showToast(I18n.string("gui.wandscape.colony.toast.role_changed", "已将 %s 的档位调整为: %s",
                                    mem.member().name(), roleName(nextRole)));
                            return true;
                        }
                        if (mx >= btnRemoveX && mx <= btnRemoveX + btnRemoveW && my >= btnY && my <= btnY + btnH) {
                            Net.toServer(new ColonyMemberActionPacket(
                                    ColonyMemberActionPacket.Action.REMOVE,
                                    cid, mem.member().id(), mem.member().role()));
                            playClickSound();
                            showToast(I18n.string("gui.wandscape.colony.toast.removed", "已将 %s 移出小镇", mem.member().name()));
                            return true;
                        }
                    }
                }
            }
            case RowItem.OnlinePlayerCard ply -> {
                UUID cid = currentColonyId();
                if (cid != null && canInvite()) {
                    int btnW = 72;
                    int btnX = rightEdge - btnW;
                    if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                        ColonyRole role = inviteRole();
                        Net.toServer(new ColonyMemberActionPacket(
                                ColonyMemberActionPacket.Action.INVITE,
                                cid, ply.player().id(), role));
                        playClickSound();
                        showToast(I18n.string("gui.wandscape.colony.toast.invite_sent", "已向 %s 发送入镇邀请 (%s)",
                                ply.player().name(), roleName(role)));
                        return true;
                    }
                }
            }
            default -> {}
        }
        return false;
    }

    public static boolean handleMouseScroll(double deltaY) {
        if (!isActive()) return false;
        List<RowItem> items = getRows(activeTab);
        Minecraft mc = Minecraft.getInstance();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int listY = HEADER_H + TOOLBAR_H + 8;
        int listH = screenH - listY - 10;

        int totalContentH = 0;
        for (int i = 0; i < items.size(); i++) {
            totalContentH += items.get(i).height();
            if (i < items.size() - 1) totalContentH += CARD_GAP;
        }

        int maxScroll = Math.max(0, totalContentH - listH);
        int cur = scrollOffsets[activeTab.ordinal()];
        int delta = deltaY > 0 ? -28 : 28;
        scrollOffsets[activeTab.ordinal()] = Math.max(0, Math.min(maxScroll, cur + delta));
        return true;
    }

    // ── Visual Helper Methods ──

    private static int roleColor(ColonyRole role) {
        if (role == null) return 0xFF90A4AE;
        return switch (role) {
            case OWNER -> 0xFFFFD54F;
            case MANAGER -> 0xFF64B5F6;
            case MEMBER -> 0xFF81C784;
            case ALLY -> 0xFF90A4AE;
        };
    }

    private static int roleBg(ColonyRole role) {
        if (role == null) return 0x3390A4AE;
        return switch (role) {
            case OWNER -> 0x33FFD54F;
            case MANAGER -> 0x3364B5F6;
            case MEMBER -> 0x3381C784;
            case ALLY -> 0x3390A4AE;
        };
    }

    private static String roleDescription(ColonyRole role) {
        if (role == null) return "";
        return switch (role) {
            case OWNER -> "小镇创立者与最高领袖，享有全部管理与治理权限";
            case MANAGER -> "负责小镇的日常运转、法师调度、建筑管理与人员邀请";
            case MEMBER -> "正式城镇居民，拥有工坊下单与仓库存取权限";
            case ALLY -> "受小镇魔法守卫保护的友好同盟人员";
        };
    }

    private static String shortId(@Nullable UUID id) {
        if (id == null) return "Unknown";
        return id.toString().substring(0, Math.min(8, id.toString().length()));
    }
}
