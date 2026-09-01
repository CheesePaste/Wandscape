package com.wsteam.wandscape.foundation.mixin;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.colony.raid.RaidTownHall;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让原版袭击把小镇当作村庄：{@code ServerLevel.isVillage} 在市政厅
 * {@code raid.villageRange} 内返回 true。
 *
 * <p>原版 {@code Raid.tick()} 每 tick 检查 {@code isVillage(center)}，中心不在村庄则
 * STOP/LOSS。此钩子让袭击中心（=市政厅）始终判定为村庄，袭击因此完整跑完波次。
 * 多个模组对同一方法 {@code @Inject} 会按 priority 全部执行——别的模组 cancel
 * 不会跳过本钩子，故袭击触发不受影响。
 */
@Mixin(ServerLevel.class)
public abstract class MixinServerLevel {

    @Inject(method = "isVillage(Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true)
    private void wandscape$isVillageNearTownHall(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (RaidTownHall.isNearTownHall(pos, Config.RAID_VILLAGE_RANGE.get())) {
            cir.setReturnValue(true);
        }
    }
}
