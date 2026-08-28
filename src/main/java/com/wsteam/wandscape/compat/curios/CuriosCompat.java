package com.wsteam.wandscape.compat.curios;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;

import com.google.common.collect.ImmutableMap;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.compat.ironspellbooks.IronSpellsAttributes;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.engine.attribute.WandscapeAttributes;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.event.CurioChangeEvent;
import top.theillusivec4.curios.api.type.ISlotType;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;
import top.theillusivec4.curios.common.data.CuriosEntityManager;

/**
 * Wandscape × Curios API 兼容总入口。
 *
 * <p>所有 Curios 相关类均封装在此包内，运行时统一用 {@link #isLoaded()} 门控；未安装 Curios 时
 * 本包逻辑不执行，零硬编码依赖与优雅降级（对齐 {@code compat/ironspellbooks}）。
 *
 * <p>法师（{@code wandscape:wandscape_npc}）饰品槽位采用**运行时镜像**：服务端在数据 reload /
 * 玩家登录同步时，把法师实体类型的槽位映射注入 Curios 的 {@link CuriosEntityManager}，内容 = 玩家
 * 标准槽位集（{@code CuriosApi.getEntitySlots(EntityType.PLAYER)}）。这样铁魔法的法术书槽位等其他模组
 * 给玩家加的槽位，法师初始即有；注入后 Curios 自带的 datapack sync 自动把映射分发给客户端，客户端无须
 * 任何改动。若数据包已显式定义了法师的槽位集，则以数据层为准，镜像让位。
 *
 * <p>本实现**不修改 Curios 源码**：对 {@link CuriosEntityManager} 仅做运行时字段写入（反射），
 * 不复制 / 改写其代码，故不触发 LGPL-3.0 传染，集成进 Wandscape 本体即可。
 */
public final class CuriosCompat {

    private static final String TAG = "CuriosCompat";
    public static final String MOD_ID = "curios";

    private static boolean loaded = false;

    /** 法师饰品容器菜单（独立 DeferredRegister——不可放进 Wandscape.MENUS，否则类加载期无条件引用 Curios 类）。 */
    public static DeferredHolder<MenuType<?>, MenuType<NpcCuriosMenu>> NPC_CURIOS_MENU;
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Wandscape.MODID);

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
        // 扩展菜单：客户端工厂带 RegistryFriendlyByteBuf，服务端经 IPlayerExtension.openMenu(provider, buf)
        // 写入法师 entityId —— 返回按钮据此重新打开法师装备界面
        NPC_CURIOS_MENU = MENUS.register("npc_curios", () ->
                IMenuTypeExtension.create(NpcCuriosMenu::new));
        MENUS.register(modEventBus);
        NeoForge.EVENT_BUS.register(ServerHooks.class);
    }

    /** 服务端钩子：数据 reload 与玩家登录同步把法师槽位镜像为玩家标准槽位集。 */
    public static final class ServerHooks {

        private ServerHooks() {
        }

        @SubscribeEvent
        public static void onServerStarting(ServerStartingEvent evt) {
            mirrorMageSlots();
        }

        /** HIGHEST —— 必须先于 Curios 自己的 onDatapackSync 发包，注入才能进同步包分发到客户端；
         *  数据 reload（全员广播）与玩家加入（个人）都会触发本事件。 */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onDatapackSync(OnDatapackSyncEvent evt) {
            mirrorMageSlots();
        }

        /** 饰品槽变化（换装/摘下/换入/存档读入后首次 tick 的 prevStack 对比）→ 重建铁魔法饰品属性桥。 */
        @SubscribeEvent
        public static void onCurioChange(CurioChangeEvent evt) {
            if (evt.getEntity() instanceof WandscapeNpc npc) {
                syncIronCurioAttributes(npc);
            }
        }
    }

    static void mirrorMageSlots() {
        mirrorMageSlots(false);
    }

    /**
     * 把法师实体类型的槽位映射镜像为玩家标准槽位集，写入 Curios 服务端实体槽位表。
     *
     * <p>规则：
     * <ul>
     *   <li>玩家标准槽位集为空 → 法师也为空（保持"新玩家"语义）。</li>
     *   <li>非强制时，若数据包已显式定义法师槽位（{@code curios/entities/*.json} 提及
     *       {@code wandscape:wandscape_npc}）→ 数据层胜出，本次镜像让位。{@code /wandscape curios mirror}
     *       传 {@code force=true} 强制执行。</li>
     *   <li>否则写入 {@code wandscape:wandscape_npc → 玩家槽位集副本}。</li>
     * </ul>
     *
     * <p>写入后 Curios 的 {@code CuriosEntityManager.getSyncPacket()} 会带上法师条目，随 datapack sync
     * 分发到客户端——客户端（菜单构建、实体饰品 handler）无须任何额外改动。
     */
    static void mirrorMageSlots(boolean force) {
        if (!loaded) {
            return;
        }
        try {
            EntityType<?> mageType = Wandscape.WANDSCAPE_NPC.get();
            Field field = CuriosEntityManager.class.getDeclaredField("entitySlots");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<EntityType<?>, Map<String, ISlotType>> current =
                    (Map<EntityType<?>, Map<String, ISlotType>>) field.get(CuriosEntityManager.SERVER);

            if (!force && current.containsKey(mageType)) {
                return; // 数据包显式定义胜出
            }
            Map<String, ISlotType> playerSlots = CuriosApi.getEntitySlots(EntityType.PLAYER, false);
            ImmutableMap.Builder<EntityType<?>, Map<String, ISlotType>> builder = ImmutableMap.builder();
            builder.putAll(current);
            if (!playerSlots.isEmpty()) {
                builder.put(mageType, ImmutableMap.copyOf(playerSlots));
            }
            field.set(CuriosEntityManager.SERVER, builder.buildKeepingLast());
            Log.info(TAG, "Mage curio slots mirrored from player standard set ({} slot types)",
                    playerSlots.size());
        } catch (Exception e) {
            // 反射字段随 Curios 版本可能变动：失败则法师无饰品槽，功能惰性降级，不影响其余功能
            Log.warn(TAG, "Failed to mirror player curio slots onto the mage: {}", e.getMessage());
        }
    }

    // ============================================================
    // 饰品属性桥接（与 WandscapeNpc.syncIronArmorAttributes 对称）
    // ============================================================

    /** NPC 属性表上铁魔法饰品 transient 修饰符的 id 前缀（区分于护甲桥 {@code iron_armor_*}）。 */
    private static final String IRON_CURIO_MODIFIER_PREFIX = "iron_curio_";

    /** 从 Curios 槽位桥进 NPC 属性表的 Wandscape 属性（与护甲桥同集，见 IronSpellsAttributes）。 */
    private static final AttributeType[] BRIDGED_TYPES = {
            AttributeType.MAX_MANA,
            AttributeType.SPELL_POWER,
            AttributeType.SPELL_SPEED,
            AttributeType.MANA_REGEN
    };

    /**
     * 把法师饰品槽中铁魔法饰品的属性加成桥进 NPC 的 Wandscape 属性表（transient 修饰符），
     * 与护甲桥 {@code WandscapeNpc#syncIronArmorAttributes} 对称。
     *
     * <p>铁魔法饰品（{@code CurioBaseItem}，如 +100 法力戒指）不走原版 {@code ItemAttributeModifiers}，
     * 属性在 Curios API 的 {@code ICurioItem.getAttributeModifiers(SlotContext, id, stack)} 中声明；
     * Curios 应用属性时要求目标属性注册在穿戴者的 AttributeMap（否则静默跳过），而 NPC 属性表没有
     * {@code irons_spellbooks:*} 属性——因此戒指加成在 Wandscape 读魔力上限（{@code wandscape:max_mana}）
     * 时不可见。本方法逐槽收集 Curios 属性并映射为 Wandscape 自有属性，以
     * {@code iron_curio_<槽类型>_<index>_<类型>} 修饰符落下；非铁魔法属性（含 vanilla 属性——
     * 如 max_health，Curios 已直接应用）一律跳过。每次先清后刷，幂等。
     *
     * <p>触发：Curios 在任一槽换装/卸载/加载（含存档读入后首次 tick 的 prevStack 对比）时于服务端
     * 广播 {@link CurioChangeEvent}，{@link ServerHooks#onCurioChange} 收到即全量重建，无空窗。
     */
    static void syncIronCurioAttributes(WandscapeNpc npc) {
        if (!loaded || npc == null || npc.level() == null || npc.level().isClientSide) {
            return;
        }
        // 1) 清掉旧的铁魔法饰品桥修饰符
        for (AttributeType type : BRIDGED_TYPES) {
            Holder<Attribute> vanillaAttr = WandscapeAttributes.toVanilla(type);
            if (vanillaAttr == null) continue;
            AttributeInstance inst = npc.getAttribute(vanillaAttr);
            if (inst == null) continue;
            for (AttributeModifier modifier : inst.getModifiers()) {
                if (modifier.id().getNamespace().equals(Wandscape.MODID)
                        && modifier.id().getPath().startsWith(IRON_CURIO_MODIFIER_PREFIX)) {
                    inst.removeModifier(modifier);
                }
            }
        }
        // 2) 重扫全部饰品槽（真实 Curios 栈，含服务端同步的玩家槽位镜像）
        CuriosApi.getCuriosInventory(npc).ifPresent(handler -> {
            for (ICurioStacksHandler stacksHandler : handler.getCurios().values()) {
                String identifier = stacksHandler.getIdentifier();
                IDynamicStackHandler stacks = stacksHandler.getStacks();
                NonNullList<Boolean> renders = stacksHandler.getRenders();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack stack = stacks.getStackInSlot(i);
                    if (stack.isEmpty()) continue;
                    SlotContext slotContext = new SlotContext(identifier, npc, i, false,
                            renders.size() > i && renders.get(i));
                    var map = CuriosApi.getAttributeModifiers(
                            slotContext, CuriosApi.getSlotId(slotContext), stack);
                    for (var mod : IronSpellsAttributes.modifiersForCurio(map)) {
                        Holder<Attribute> vanillaAttr = WandscapeAttributes.toVanilla(mod.type());
                        if (vanillaAttr == null) continue;
                        AttributeInstance inst = npc.getAttribute(vanillaAttr);
                        if (inst == null) continue;
                        ResourceLocation modId = ResourceLocation.fromNamespaceAndPath(Wandscape.MODID,
                                IRON_CURIO_MODIFIER_PREFIX + identifier + "_" + i + "_"
                                        + mod.type().name().toLowerCase(Locale.ROOT));
                        AttributeModifier.Operation op =
                                (mod.operation() == com.wsteam.wandscape.core.types.ModifierOperation.MULTIPLY_BASE)
                                        ? AttributeModifier.Operation.ADD_MULTIPLIED_BASE
                                        : AttributeModifier.Operation.ADD_VALUE;
                        inst.addOrUpdateTransientModifier(new AttributeModifier(modId, mod.amount(), op));
                    }
                }
            }
        });
    }
}