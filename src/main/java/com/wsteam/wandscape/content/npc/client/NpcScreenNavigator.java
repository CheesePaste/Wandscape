package com.wsteam.wandscape.content.npc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * 解决 NPC 各面板（主信息屏 / 饰品屏 / 背包屏 / 施法策略屏）相互切换时，
 * 原版 openMenu 发送 ClientboundContainerClosePacket 导致 setScreen(null)
 * 进而触发 mouseHandler.grabMouse() 将鼠标指针强行重置到屏幕正中央的问题。
 *
 * <p>工作机制：
 * 1. 客户端发起切换前调用 {@link #prepareTransition(int)}，记录当前鼠标坐标并标记正在切换；
 * 2. 拦截由 closeContainer 触发的单次 {@code setScreen(null)}，避免 1 帧画面闪烁与 grabMouse 重置鼠标；
 * 3. 当新面板打开后，延迟 1 拍确保 GLFW 鼠标坐标恢复为原位置，让面板切换顺滑无感。
 */
public final class NpcScreenNavigator {

    private static boolean registered = false;
    private static boolean transitionActive = false;
    private static boolean nullScreenCanceled = false;
    private static long transitionExpiry = 0;
    private static double savedMouseX = -1;
    private static double savedMouseY = -1;
    private static int lastEntityId = -1;

    private NpcScreenNavigator() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening.class, NpcScreenNavigator::onScreenOpening);
    }

    public static void prepareTransition(int entityId) {
        if (entityId >= 0) {
            lastEntityId = entityId;
        }
        prepareTransition();
    }

    public static void prepareTransition() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.mouseHandler != null) {
            savedMouseX = mc.mouseHandler.xpos();
            savedMouseY = mc.mouseHandler.ypos();
        }
        transitionActive = true;
        nullScreenCanceled = false;
        transitionExpiry = System.currentTimeMillis() + 1500;
    }

    public static int getLastEntityId() {
        return lastEntityId;
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!transitionActive) return;

        // 超时保护（1.5 秒内未收到后续包自动失效）
        if (System.currentTimeMillis() > transitionExpiry) {
            transitionActive = false;
            nullScreenCanceled = false;
            savedMouseX = -1;
            savedMouseY = -1;
            return;
        }

        Screen newScreen = event.getScreen();
        if (newScreen == null) {
            if (!nullScreenCanceled) {
                // 仅拦截由 closeContainer 触发的第 1 次过渡 setScreen(null)，避免画面闪烁与 grabMouse 置中
                nullScreenCanceled = true;
                event.setCanceled(true);
            } else {
                // 若再次收到 null，允许正常关闭
                transitionActive = false;
                nullScreenCanceled = false;
                savedMouseX = -1;
                savedMouseY = -1;
            }
        } else {
            // 新面板已打开，完成切换并恢复鼠标位置
            transitionActive = false;
            nullScreenCanceled = false;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(NpcScreenNavigator::restoreMousePosition);
        }
    }

    public static void restoreMousePosition() {
        if (savedMouseX >= 0 && savedMouseY >= 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getWindow() != null) {
                long window = mc.getWindow().getWindow();
                GLFW.glfwSetCursorPos(window, savedMouseX, savedMouseY);
            }
            savedMouseX = -1;
            savedMouseY = -1;
        }
    }
}
