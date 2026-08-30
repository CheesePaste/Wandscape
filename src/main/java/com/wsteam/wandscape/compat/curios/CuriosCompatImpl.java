package com.wsteam.wandscape.compat.curios;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.compass.CompassService;
import com.wsteam.wandscape.compat.curios.client.NpcCuriosScreen;
import com.wsteam.wandscape.compat.ironspellbooks.IronSpellsAttributes;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.engine.attribute.WandscapeAttributes;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.CuriosCapability;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.event.CurioChangeEvent;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Wandscape × Curios API 兼容实现。
 *
 * <p>本类是【唯一】真正引用 {@code top.theillusivec4.curios.*} 类型的类，且只能被
 * {@link CuriosCompat}（Curios 门面，无任何 Curios 类型引用）在确认 Curios 已加载后静态调用。
 * 因此本类在未安装 Curios 时永远不会被 JVM 装载——由 CuriosCompat 对静态调用的执行期解析 +
 * {@code loaded == false} 提前返回共同保证。
 */
public final class CuriosCompatImpl {

    /** 法师饰品容器菜单（独立 DeferredRegister——不可放进 Wandscape.MENUS，否则类加载期无条件引用 Curios 类）。 */
    public static DeferredHolder<MenuType<?>, MenuType<NpcCuriosMenu>> NPC_CURIOS_MENU;
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Wandscape.MODID);

    private CuriosCompatImpl() {}

    /** 注册法师饰品菜单、护符 ICurio capability 与服务端钩子。仅在 Curios 已加载时被调用。 */
    public static void init(IEventBus modEventBus) {
        // 扩展菜单：客户端工厂带 RegistryFriendlyByteBuf，服务端经 IPlayerExtension.openMenu(provider, buf)
        // 写入法师 entityId —— 返回按钮据此重新打开法师装备界面
        NPC_CURIOS_MENU = MENUS.register("npc_curios", () ->
                IMenuTypeExtension.create(NpcCuriosMenu::new));
        MENUS.register(modEventBus);
        modEventBus.addListener(CuriosCompatImpl::onRegisterCapabilities);
        NeoForge.EVENT_BUS.register(ServerHooks.class);
    }

    /** 注册法师饰品栏打开请求 payload。仅在 Curios 已加载时被调用（复用主链路同一 registrar）。 */
    public static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToServer(
                NpcOpenCuriosPacket.TYPE,
                NpcOpenCuriosPacket.STREAM_CODEC,
                NpcOpenCuriosPacket::handleServer);
    }

    /** 客户端：注册法师饰品容器屏幕。仅在 Curios 已加载时被调用。 */
    public static void registerNpcMenuScreens(RegisterMenuScreensEvent event) {
        event.register(NPC_CURIOS_MENU.get(), NpcCuriosScreen::new);
    }

    /** 检查实体是否在 Curios 饰品槽中佩戴了指定物品。 */
    public static boolean isEquipped(LivingEntity entity, Item item) {
        return CuriosApi.getCuriosInventory(entity)
                .map(handler -> handler.isEquipped(item))
                .orElse(false);
    }

    /** 检查实体是否在 Curios 饰品槽中佩戴了满足条件的物品。 */
    public static boolean isEquipped(LivingEntity entity, Predicate<ItemStack> filter) {
        return CuriosApi.getCuriosInventory(entity)
                .map(handler -> handler.isEquipped(filter))
                .orElse(false);
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        // 为魔法指南针注册 ICurio capability：戴在护符槽时服务端每 100 tick 自动重同步市政厅坐标
        event.registerItem(
                CuriosCapability.ITEM,
                (stack, context) -> new ICurio() {
                    @Override
                    public ItemStack getStack() {
                        return stack;
                    }

                    @Override
                    public void curioTick(SlotContext slotContext) {
                        if (slotContext.entity() instanceof ServerPlayer sp && sp.level().getGameTime() % 100 == 0) {
                            CompassService.syncFor(sp);
                        }
                    }
                },
                Wandscape.MAGIC_COMPASS.get(),
                Wandscape.ADVANCED_MAGIC_COMPASS.get(),
                Wandscape.ULTIMATE_MAGIC_COMPASS.get()
        );
    }

    /** 服务端钩子：饰品槽变化 → 重建铁魔法饰品属性桥。
     *  法师槽位映射由数据包 {@code data/curios/curios/entities/wandscape_npc.json} 声明，
     *  Curios 自带 datapack reload 与 sync 分发，无须任何运行时镜像。 */
    public static final class ServerHooks {

        private ServerHooks() {
        }

        /** 饰品槽变化（换装/摘下/换入/存档读入后首次 tick 的 prevStack 对比）→ 重建铁魔法饰品属性桥。 */
        @SubscribeEvent
        public static void onCurioChange(CurioChangeEvent evt) {
            if (evt.getEntity() instanceof WandscapeNpc npc) {
                syncIronCurioAttributes(npc);
            }
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
        if (npc == null || npc.level() == null || npc.level().isClientSide) {
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
