package com.wsteam.wandscape.compat.curios;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.component.NpcInventory;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.server.command.CurioArgumentType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 法师饰品槽管理指令（挂于 {@code /wandscape} 根下）——
 * 供模组作者 / 整合包作者 / 服主精细调整法师饰品槽位。
 *
 * <pre>
 *   /wandscape curios list [target]                 — 列出目标法师的槽位类型与数量（默认全部殖民地法师）
 *   /wandscape curios set|add|remove <slot> <count> [target] — 实例级调整（持久化在实体，/reload 不清）
 * </pre>
 *
 * <p>实体类型级默认槽位由数据包 {@code data/curios/curios/entities/wandscape_npc.json} 声明
 * （与玩家标准槽位集一致），随 Curios 自带 datapack reload 与 sync 生效。本命令只做<b>实例级</b>
 * 调整：set/add/remove 增减只作用于目标法师自身的栈大小，持久化在实体，区块重载/重启不清除。
 */
public final class CuriosCommand {

    private static final String TAG = "CuriosCommand";

    private CuriosCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("curios")
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource(), null))
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(ctx -> list(ctx.getSource(),
                                        EntityArgument.getEntity(ctx, "target")))))
                .then(adjustNode("set"))
                .then(adjustNode("add"))
                .then(adjustNode("remove"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> adjustNode(String op) {
        return Commands.literal(op)
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("slot", CurioArgumentType.slot())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0, 256))
                                .executes(ctx -> adjust(ctx.getSource(), op,
                                        CurioArgumentType.getSlot(ctx, "slot"),
                                        IntegerArgumentType.getInteger(ctx, "amount"),
                                        null))
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(ctx -> adjust(ctx.getSource(), op,
                                                CurioArgumentType.getSlot(ctx, "slot"),
                                                IntegerArgumentType.getInteger(ctx, "amount"),
                                                EntityArgument.getEntity(ctx, "target"))))));
    }

    private static int list(CommandSourceStack src, @Nullable Entity target) {
        List<WandscapeNpc> mages = resolveMages(src, target);
        if (mages.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[Wandscape] No target mages."), false);
            return 0;
        }
        for (WandscapeNpc npc : mages) {
            String label = npc.getName().getString() + " (" + npc.getId() + ")";
            CuriosApi.getCuriosInventory(npc).ifPresentOrElse(handler -> {
                StringBuilder sb = new StringBuilder(label).append(":");
                handler.getCurios().forEach(
                        (id, sh) -> sb.append(" ").append(id).append("x").append(sh.getSlots()));
                src.sendSuccess(() -> Component.literal(sb.toString()), false);
            }, () -> src.sendSuccess(() -> Component.literal(label + ": (no curio inventory)"), false));
        }
        return mages.size();
    }

    @SuppressWarnings("removal") // ICuriosItemHandler.grow/shrinkSlotType 计划 1.22 移除，1.21 可用
    private static int adjust(CommandSourceStack src, String op, String slot, int amount,
                              @Nullable Entity target) {
        List<WandscapeNpc> mages = resolveMages(src, target);
        if (mages.isEmpty()) {
            src.sendFailure(Component.literal("[Wandscape] No target mages."));
            return 0;
        }
        int touched = 0;
        for (WandscapeNpc npc : mages) {
            CuriosApi.getCuriosInventory(npc).ifPresent(handler -> {
                switch (op) {
                    case "set" -> {
                        int current = handler.getStacksHandler(slot)
                                .map(sh -> sh.getSlots()).orElse(0);
                        int diff = amount - current;
                        if (diff > 0) {
                            handler.growSlotType(slot, diff);
                        } else if (diff < 0) {
                            handler.shrinkSlotType(slot, -diff);
                        }
                    }
                    case "add" -> handler.growSlotType(slot, amount);
                    case "remove" -> handler.shrinkSlotType(slot, amount);
                    default -> {
                    }
                }
            });
            touched++;
        }
        Log.info(TAG, "/wandscape curios {} '{}' x{} on {} mage(s)", op, slot, amount, touched);
        final int affected = touched;
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] " + op + " '" + slot + "' x" + amount + " on " + affected + " mage(s)."),
                true);
        return touched;
    }

    /** 目标实体为法师 → 单个；否则收集所有已加载法师（含战区守卫等全部法师）。 */
    private static List<WandscapeNpc> resolveMages(CommandSourceStack src, @Nullable Entity target) {
        if (target instanceof WandscapeNpc npc) {
            return List.of(npc);
        }
        net.minecraft.world.phys.AABB all = new net.minecraft.world.phys.AABB(
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        List<WandscapeNpc> mages = new ArrayList<>();
        for (ServerLevel level : src.getServer().getAllLevels()) {
            mages.addAll(level.getEntitiesOfClass(WandscapeNpc.class, all, e -> true));
        }
        return mages;
    }
}