package com.wsteam.wandscape.api;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.Predicate;

/**
 * 友军名单外部注册 API：让其它模组/整合包把己方实体登记为「所有殖民地的友军」，从而
 * 殖民地 NPC 不会攻击、不会溅射误伤这些实体（如其它模组的宠物/召唤物/守护单位）。
 *
 * <p>用法（在模组初始化阶段调用一次）：
 * <pre>{@code
 * WandscapeApis.getFriendlyForceApiSilently().ifPresent(api ->
 *     api.registerAlly(e -> e instanceof MyModSummon summon && summon.getOwner() != null));
 * }</pre>
 *
 * <p>注册的谓词在每次 {@link com.wsteam.wandscape.content.npc.entity.WandscapeNpc#isFriendlyForce}
 * 判定时兜底查询，请保持轻量（通常是 instanceof 判断）。**互不侵犯**（你的实体不主动打殖民地单位、
 * 殖民地单位也不打你的实体）会自动生效，无需额外适配。做其它模组兼容时，记住把该模组的召唤物/
 * 宠物按此注册加入盟友，避免误伤。
 */
public interface FriendlyForceApi {

    /**
     * 注册一个友军判定器：实体为 {@code true} 视为所有殖民地的友军（等同本模组宠物/守护召唤）。
     * 幂等，重复注册会累加（任一命中即友军）。
     */
    void registerAlly(Predicate<LivingEntity> isAlly);

    /**
     * 是否命中任一已注册的友军判定器（内部判定兜底用，外部模组一般无需调用）。
     */
    boolean isExternalAlly(LivingEntity entity);
}
