package com.wsteam.wandscape.magic.internal;

import com.wsteam.wandscape.magic.data.MagicCircleSpec;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * shift+右键 NPC → NPC 举起法杖、沿其当前朝向向前施放魔法阵（观察/演示入口）。
 * 服务端拦截交互（避免同时打开 NPC 信息界面）；客户端放行让交互包到达服务端。
 */
public final class MagicInteractHandler {

    private static final String TAG = "MagicInteractHandler";

    private MagicInteractHandler() {}

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getTarget() instanceof WandscapeNpc npc)) return;
        if (!event.getEntity().isShiftKeyDown()) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        MagicCircleSpec spec = MagicCircleLoader.getSpec(MagicCaster.beamCircleId());
        if (spec == null) return;

        if (MagicCaster.castNpc((ServerLevel) npc.level(), npc, MagicCaster.beamCircleId(), null)) {
            // 举杖窗口 = 法阵动画时长，光束在其后由 MagicCastManager 生成
            npc.startManualCast(spec.durationTicks);
        } else {
            Log.warn(TAG, "NPC {} 施法被拒（施法互斥锁占用/光束CD未过/正在施法中）",
                    npc.getUUID().toString().substring(0, 8));
        }
    }
}
