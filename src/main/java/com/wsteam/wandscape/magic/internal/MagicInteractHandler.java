package com.wsteam.wandscape.magic.internal;

import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.ReviveHandler;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * shift+右键 NPC → 对附近最近的死亡留存记录施放**复活魔法**（dead_ally 目标）。
 * 服务端拦截交互（避免同时打开 NPC 信息界面）；客户端放行让交互包到达服务端。
 *
 * <p>原"shift+右键放光束"演示入口已移除（光束只剩守卫/自防御自动施法与玩家法杖/命令）。
 */
public final class MagicInteractHandler {

    private static final String TAG = "MagicInteractHandler";

    private MagicInteractHandler() {}

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getTarget() instanceof WandscapeNpc npc)) return;
        if (!event.getEntity().isShiftKeyDown()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        boolean ok = ReviveHandler.castRevive((ServerLevel) npc.level(), npc, player);
        if (!ok) {
            Log.info(TAG, "复活施法未触发：npc={} player={}",
                    npc.getUUID().toString().substring(0, 8), player.getName().getString());
        }
    }
}
