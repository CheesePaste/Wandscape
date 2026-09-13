package com.wsteam.wandscape.compat.tlm;

import com.wsteam.wandscape.Wandscape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 本模组挂在女仆实体上的数据附件（NeoForge Data Attachment）。
 *
 * <p>**刻意不引用任何 TLM 类型**：附件类型是本模组的全局注册项，注册不该依赖 TLM 是否加载
 * （否则 TLM 缺席时这个类一加载就炸）。真正碰 {@code EntityMaid} 的代码在
 * {@link TlmCompatImpl} / {@link MaidColonyWorker} 里，只有 TLM 在场时才会被链接。
 *
 * <p>附件随实体 NBT 持久化：NeoForge 在 {@code Entity#saveWithoutId} 内写出
 * {@code neoforge:attachments}、在 {@code Entity#load} 内读回，两处都在
 * {@code addAdditionalSaveData} **之外**，所以 {@code EntityMaid} 这类第三方实体同样生效，
 * 子类绕不过去。
 */
public final class MaidColonyAttachments {

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Wandscape.MODID);

    /** 女仆的殖民地工作者状态（等级 + 7 项 base 属性；阶段二的魔力/冷却也挂这里）。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<MaidColonyState>> MAID_COLONY_STATE =
            ATTACHMENTS.register("maid_colony_state",
                    () -> AttachmentType.serializable(MaidColonyState::new).build());

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    private MaidColonyAttachments() {}
}
