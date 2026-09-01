package com.wsteam.wandscape.scepter.internal;

import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.scepter.OmniScepterItem;
import com.wsteam.wandscape.scepter.ScepterItem;
import com.wsteam.wandscape.scepter.ScepterKind;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 玩家右键非法师/非本殖民地生物时的权杖命令入口（EntityInteract 事件）。
 *
 * <p>已核实源码：事件确定先于 {@code mobInteract}/{@code item.interactLivingEntity}，
 * {@code setCanceled(true)} 会同时跳过喂牛/驯狼/使用等所有原版交互；两端一致
 * {@code setCancellationResult(SUCCESS)} 保留挥手动画、预测一致。只对庇护/敌对权杖处理；
 * 玩家与自己殖民地的法师（走 {@code MageWandItem}→{@code mobInteract}）与非生物目标一律
 * 放行（不 cancel），避免屏蔽其它交互。非殖民地法师（含敌对测试法师 EvilMage）也由本类处理，
 * 使庇护/敌对能指定任何生物。
 */
public final class ScepterInteractHandler {

    private ScepterInteractHandler() {}

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Entity target = event.getTarget();
        if (target instanceof Player) return;
        if (target instanceof WandscapeNpc npc && npc.isColonyNpc()) return; // 本殖民地法师走 mobInteract
        if (!(target instanceof LivingEntity living)) return;

        ItemStack held = event.getEntity().getItemInHand(event.getHand());
        Item heldItem = held.getItem();

        boolean omni = heldItem instanceof OmniScepterItem;
        if (!omni && !(heldItem instanceof ScepterItem)) return;

        ScepterKind kind = omni ? OmniScepterItem.getMode(held) : ((ScepterItem) heldItem).kind();
        boolean sneak = event.getEntity().isShiftKeyDown();

        // 基础权杖：和平/跟随只对法师目标（onInteractNpc 路径），对生物无效 → 放行 vanilla。
        // 万能权杖：非潜行且当前模式为和平/跟随时同样放行；潜行（切模式）与庇护/敌对模式均接管。
        if ((!omni && kind != ScepterKind.SHELTER && kind != ScepterKind.HOSTILE)
                || (omni && !sneak && kind != ScepterKind.SHELTER && kind != ScepterKind.HOSTILE)) {
            return;
        }

        // 两端一致取消：服务端执行业务，客户端保证预测表现同步
        Level level = event.getLevel();
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (!level.isClientSide && event.getEntity() instanceof ServerPlayer sp) {
            if (omni && sneak) {
                OmniScepterItem.cycleMode(sp, held, event.getHand());
            } else {
                ScepterService.onInteractCreature(sp, living, kind);
            }
        }
    }
}