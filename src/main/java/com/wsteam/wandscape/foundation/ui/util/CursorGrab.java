package com.wsteam.wandscape.foundation.ui.util;

import net.minecraft.client.Minecraft;

/**
 * 光标抓取的唯一出口。
 *
 * <p><b>为什么需要这一层。</b>{@code MixinMouseHandler} 会在 {@code MouseHandler.grabMouse()} /
 * {@code releaseMouse()} 的 HEAD 上取消「外来」的光标争夺。判断「外来」的唯一可靠依据是
 * 「这次调用是不是本仓自己发的」——原版 {@code grabMouse()} 与 {@code releaseMouse()} 都会把
 * OS 光标 {@code glfwSetCursorPos} 到窗口正中并翻转 {@code GLFW_CURSOR}，所以别处每 tick 抢一次
 * （例如 huhutalk 的 {@code PhoneOverlay.updateCursorMode()} 里 {@code else if (mc.screen == null) grabMouse();}）
 * 就会和 V 面板的「持久自由光标」对撞成「光标锁在屏幕正中闪烁」。因此本仓所有直接调用
 * {@code mouseHandler.grabMouse()/releaseMouse()} 的地方都必须走这里，兜底才会放行。
 *
 * <p><b>为什么用 ThreadLocal。</b>这是一次调用的作用域标记，不是全局状态：Ixeris 这类模组会把
 * GLFW 事件轮询放到另一个线程，用静态字段会被别的线程读到脏值。
 */
public final class CursorGrab {

    private static final ThreadLocal<Boolean> SELF = new ThreadLocal<>();

    private CursorGrab() {}

    /** 本仓自己发起的光标抓取（兜底会放行）。 */
    public static void grab(Minecraft mc) {
        if (mc == null || mc.mouseHandler == null) return;
        SELF.set(Boolean.TRUE);
        try {
            mc.mouseHandler.grabMouse();
        } finally {
            SELF.remove();
        }
    }

    /** 本仓自己发起的光标释放（兜底会放行）。 */
    public static void release(Minecraft mc) {
        if (mc == null || mc.mouseHandler == null) return;
        SELF.set(Boolean.TRUE);
        try {
            mc.mouseHandler.releaseMouse();
        } finally {
            SELF.remove();
        }
    }

    public static void grab() {
        grab(Minecraft.getInstance());
    }

    public static void release() {
        release(Minecraft.getInstance());
    }

    /** 当前是否正处于「本仓自己发起的光标调用」里。仅供 {@code MixinMouseHandler} 判断豁免。 */
    public static boolean isSelfCall() {
        return Boolean.TRUE.equals(SELF.get());
    }
}
