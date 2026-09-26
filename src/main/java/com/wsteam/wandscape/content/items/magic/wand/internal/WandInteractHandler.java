package com.wsteam.wandscape.content.items.magic.wand.internal;

import com.wsteam.wandscape.content.items.magic.wand.item.WandItem;
import com.wsteam.wandscape.content.items.magic.wand.item.WandMode;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 玩家右键非法师生物时的法杖命令入口（EntityInteract 事件）。
 *
 * <p>与权杖的 {@code ScepterInteractHandler} 同构，只换物品判据。已核实源码：事件确定先于
 * {@code mobInteract}/{@code item.interactLivingEntity}，{@code setCanceled(true)} 会同时跳过
 * 喂牛/驯狼/使用等所有原版交互；两端一致 {@code setCancellationResult(SUCCESS)} 保留挥手动画、
 * 预测一致。
 *
 * <p>只接管「以生物为落点」的两条模式（{@link WandMode#affectsCreatures()}：庇护/敌对）与潜行切模式；
 * 集合/鉴定对着生物右键时一律放行原版（喂牛、交易、上鞍照常）。玩家与自己殖民地的法师（走
 * {@code NpcInteractHook}→{@code mobInteract}）与非生物目标同样放行，避免屏蔽其它交互。
 * 非殖民地法师（含敌对测试法师 EvilMage）也由本类处理，使庇护/敌对能指定任何生物。
 */
public final class WandInteractHandler {

    private WandInteractHandler() {}

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Entity target = event.getTarget();
        if (target instanceof Player) return;
        if (target instanceof WandscapeNpc npc && npc.isColonyNpc()) return; // 本镇法师走 mobInteract
        if (!(target instanceof LivingEntity living)) return;

        ItemStack held = event.getEntity().getItemInHand(event.getHand());
        if (!(held.getItem() instanceof WandItem)) return;

        WandMode mode = WandItem.getMode(held);
        boolean sneak = event.getEntity().isShiftKeyDown();
        if (!sneak && !mode.affectsCreatures()) return;

        // 两端一致取消：服务端执行业务，客户端保证预测表现同步
        Level level = event.getLevel();
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (!level.isClientSide && event.getEntity() instanceof ServerPlayer sp) {
            if (sneak) {
                WandItem.cycleMode(sp, held, event.getHand());
            } else {
                WandModeService.onInteractCreature(sp, living, mode);
            }
        }
    }
}
