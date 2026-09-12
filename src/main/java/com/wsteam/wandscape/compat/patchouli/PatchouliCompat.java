package com.wsteam.wandscape.compat.patchouli;

import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

/**
 * Wandscape x Patchouli 兼容门面。
 *
 * <p>为确保未安装 Patchouli 模组时不抛 {@link NoClassDefFoundError}，本类严禁直接引用
 * 任何 {@code vazkii.patchouli.*} 类型。实际调用均隔离在 {@link PatchouliCompatImpl} 中，
 * 仅在 {@link #isLoaded()} 为真时才会被类加载器载入。
 */
public final class PatchouliCompat {

    private static final String TAG = "PatchouliCompat";
    public static final String MOD_ID = "patchouli";
    public static final ResourceLocation BOOK_ID = ResourceLocation.fromNamespaceAndPath("wandscape", "guide");

    private static boolean loaded = false;

    private PatchouliCompat() {}

    /**
     * 是否已加载 Patchouli 模组。
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 在模组初始化阶段调用（Wandscape 主类）。
     */
    public static void init(IEventBus modEventBus) {
        loaded = ModList.get().isLoaded(MOD_ID);
        if (!loaded) {
            Log.info(TAG, "Patchouli not detected — markdown guidebook fallback active.");
            return;
        }
        Log.info(TAG, "Patchouli detected! Guide routing enabled.");
    }

    /**
     * 在客户端初始化阶段调用（注册原生代码绘制渲染器等）。
     */
    public static void initClient() {
        if (!loaded) return;
        PatchouliCompatImpl.initClient();
    }

    /**
     * 打开指定文档对应的帕秋莉手册条目；若 docPath 为空或 index 则打开手册主页。
     */
    public static void openBook(String docPath) {
        if (!loaded) return;
        PatchouliCompatImpl.openBook(docPath);
    }

    /**
     * 检查当前界面是否为本模组的帕秋莉手册。
     */
    public static boolean isBookOpen() {
        if (!loaded) return false;
        return PatchouliCompatImpl.isBookOpen();
    }
}
