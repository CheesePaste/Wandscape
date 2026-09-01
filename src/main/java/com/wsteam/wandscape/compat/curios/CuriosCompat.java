package com.wsteam.wandscape.compat.curios;

import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.function.Predicate;

/**
 * Wandscape × Curios API 兼容门面（非 compat 包唯一可引用的 Curios 入口）。
 *
 * <p>为保证未安装 Curios 时不抛 {@code NoClassDefFoundError}，本类【严禁】引用任何
 * {@code top.theillusivec4.curios.*} 类型——所有引用 Curios 类型的代码被隔离到
 * {@link CuriosCompatImpl}，仅在 {@code isLoaded()} 为真时通过门控的静态调用触达。
 * JVM 对 {@code invokestatic} 按执行期解析（而非类验证期），因此 {@code loaded == false}
 * 时提前返回即保证 {@link CuriosCompatImpl} 永不被装载，无 Curios 也能正常启动与运转。
 */
public final class CuriosCompat {

    private static final String TAG = "CuriosCompat";
    public static final String MOD_ID = "curios";

    private static boolean loaded = false;

    private CuriosCompat() {}

    /** 是否已安装并加载 Curios API。 */
    public static boolean isLoaded() {
        return loaded;
    }

    /** 在模组初始化阶段调用（Wandscape 主类）。 */
    public static void init(IEventBus modEventBus) {
        loaded = ModList.get().isLoaded(MOD_ID);
        if (!loaded) {
            Log.info(TAG, "Curios API not detected — mage trinket UI disabled.");
            return;
        }
        Log.info(TAG, "Curios API detected! Initializing compat layer...");
        CuriosCompatImpl.init(modEventBus);
    }

    /** 检查实体是否在 Curios 饰品槽中佩戴了指定物品。未安装 Curios 时返回 false。 */
    public static boolean isEquipped(LivingEntity entity, Item item) {
        if (!loaded || entity == null || item == null) {
            return false;
        }
        return CuriosCompatImpl.isEquipped(entity, item);
    }

    /** 检查实体是否在 Curios 饰品槽中佩戴了满足条件的物品。未安装 Curios 时返回 false。 */
    public static boolean isEquipped(LivingEntity entity, Predicate<ItemStack> filter) {
        if (!loaded || entity == null || filter == null) {
            return false;
        }
        return CuriosCompatImpl.isEquipped(entity, filter);
    }

    /** 客户端：注册法师饰品容器屏幕。仅在 Curios 已加载时委派（无 Curios 静默不注册）。 */
    public static void registerNpcMenuScreens(RegisterMenuScreensEvent event) {
        if (!loaded) {
            return;
        }
        CuriosCompatImpl.registerNpcMenuScreens(event);
    }

    /** 服务端：注册法师饰品栏打开请求 payload。仅在 Curios 已加载时委派（复用主链路同一 registrar）。 */
    public static void registerPayloads(PayloadRegistrar registrar) {
        if (!loaded) {
            return;
        }
        CuriosCompatImpl.registerPayloads(registrar);
    }
}
