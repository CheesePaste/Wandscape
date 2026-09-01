package com.wsteam.wandscape.mixin;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.building.BuildPlacementGuard;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 建造放置瞬间丢弃 scheduled tick。
 *
 * <p>原版 {@code onPlace} 与邻居 {@code updateShape} 在方块变化时无条件执行（与
 * setBlock 的 flag 无关），水的流动、侦测器脉冲、比较器/中继器重算都靠
 * {@code LevelTicks.schedule} 排队。这些 tick 在施工中被排队时，会因周围还没建完
 * 而提前反应（水在容器未封口时流走）。守卫开启时直接取消排队，方块落地即为其
 * 最终状态；守卫关闭时是纯透传，不影响任何原版行为。
 */
@Mixin(LevelTicks.class)
public abstract class MixinLevelTicks {

    @Inject(method = "schedule(Lnet/minecraft/world/ticks/ScheduledTick;)V",
            at = @At("HEAD"), cancellable = true)
    private void wandscape$dropTicksDuringBuildPlacement(ScheduledTick<?> tick, CallbackInfo ci) {
        if (BuildPlacementGuard.isActive()) {
            ci.cancel();
        }
    }
}
