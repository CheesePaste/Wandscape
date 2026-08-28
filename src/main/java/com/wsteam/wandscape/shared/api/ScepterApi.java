package com.wsteam.wandscape.shared.api;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * 玩家权杖（庇护/敌对）的殖民地标记查询接口。
 *
 * <p>庇护：被庇护生物是「盟友」，该殖民地法师不主动攻击、不误伤（并入
 * {@code WandscapeNpc.isFriendlyForce}）。敌对：单槽强制仇恨目标，该殖民地 128 格内法师优先
 * 集火。数据持久化于 {@code ScepterMarksSavedData}（按殖民地名下，退出重进依然生效）。
 *
 * <p>实现方在 {@code scepter/internal/}，经 {@code WandscapeApis.getScepterApi()} 装配——npc/、
 * guard/ 等模块跨模块读取一律走 {@code WandscapeApis.getScepterApiSilently()}（未装配/客户端返回
 * 安全的假/空值），不跨包直接引用 scepter 具体类。
 */
public interface ScepterApi {

    /** 目标实体是否被指定殖民地庇护（法师视其为盟友）。客户端/未就绪返回 false。 */
    boolean isSheltered(UUID colonyId, UUID entityUuid, Level level);

    /** 目标是否被任意殖民地庇护（守卫触发扫描用——庇护生物不构成对任何小镇的威胁）。 */
    boolean isShelteredForAny(UUID entityUuid, Level level);

    /** 指定殖民地当前的强制仇恨目标实体（存活且存在）；无标记/实体未加载返回 null。 */
    @Nullable
    LivingEntity forcedHostile(ServerLevel level, UUID colonyId);
}