package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.api.NpcApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.npc.data.NpcData;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.UUID;

/**
 * 殖民地法师指令：查看法师名单。
 *
 * <p>{@code /wandscape npc list [idle]} —— 列出小镇全部法师（或仅空闲者），
 * 含等级、是否空闲/当前任务、血/蓝、已装备法术。玩家无需权限（只读）。
 *
 * <p>法师的生成走酒馆 {@code /wandscape tavern recruit}；招募/训练/升级在酒馆/法师小屋界面完成。
 */
public final class NpcCommand {

    private NpcCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("npc")
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx, false))
                        .then(Commands.literal("idle")
                                .executes(ctx -> list(ctx, true))))
                .build();
    }

    private static int list(CommandContext<CommandSourceStack> ctx, boolean idleOnly) {
        CommandSourceStack src = ctx.getSource();
        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId == null) {
            src.sendFailure(Component.literal(
                    "[魔法小镇] 未检测到小镇：请在小镇范围内使用，或先创建小镇"));
            return 0;
        }

        NpcApi npcApi = WandscapeApis.getNpcApiSilently();
        if (npcApi == null) {
            src.sendFailure(Component.literal("[魔法小镇] 法师系统未就绪"));
            return 0;
        }

        List<NpcData> npcs = idleOnly ? npcApi.getIdleNpcs(colonyId) : npcApi.getColonyNpcs(colonyId);
        String cid = CommandUtil.shortId(colonyId);
        MutableComponent header = I18n.name("message.wandscape.command.npc_list_header",
                "[魔法小镇] 小镇 %s 法师 (%s，共 %d 名)：",
                cid, idleOnly ? "空闲" : "全部", npcs.size());
        src.sendSuccess(() -> header, false);

        if (npcs.isEmpty()) {
            src.sendSuccess(() -> Component.literal("  （无法师）"), false);
            return Command.SINGLE_SUCCESS;
        }

        for (NpcData n : npcs) {
            String status = n.isIdle()
                    ? "空闲"
                    : "任务中";
            String spells = n.spells().isEmpty() ? "" : "  法术:" + String.join(",", n.spells());
            src.sendSuccess(() -> Component.literal(
                    String.format("  %s (Lv.%d) | %s | HP %d | 蓝 %.0f%s",
                            n.name(), n.level(), status,
                            n.currentHealth(), n.mana(), spells)), false);
        }
        return Command.SINGLE_SUCCESS;
    }
}
