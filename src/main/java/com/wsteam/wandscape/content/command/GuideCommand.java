package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.content.items.guidebook.network.GuideBookOpenPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 打开模组指南书阅读器。
 *
 * <p>默认打开「命令介绍页」（{@code commands_guide}，对模组 /wandscape 指令做系统介绍），
 * 也可传入页名直达任意指南页（如 {@code /wandscape guide warehouse} 打开仓库指南）。
 *
 * <p>页名经 {@code GuideBookOpenPacket} 交给客户端 {@code DocumentLoader} 按语言解析，
 * 未知页名客户端显示 404 页。玩家无需权限。
 */
public final class GuideCommand {

    /** 默认页：模组命令介绍。 */
    public static final String DEFAULT_PAGE = "commands_guide";

    private GuideCommand() {}

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PAGES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(List.of(
                    "commands_guide", "index_guide", "getting_started_guide", "overview_guide",
                    "road_guide", "warehouse_guide", "npc_guide", "tavern_guide", "tourist_guide",
                    "townhall_guide", "shop_guide", "hotel_guide", "node_guide", "altar_guide",
                    "crafting_guide", "magic_station_guide", "workstation_guide", "mage_hut_guide",
                    "strategy_guide", "scanner_guide", "creative_scanner_guide", "road_replace_guide",
                    "road_fill_guide", "road_spline_guide", "magic_circle_editor_guide", "anomaly_guide"),
                    builder);

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("guide")
                .executes(ctx -> open(ctx, DEFAULT_PAGE))
                .then(Commands.argument("page", StringArgumentType.greedyString())
                        .suggests(SUGGEST_PAGES)
                        .executes(ctx -> open(ctx, StringArgumentType.getString(ctx, "page").trim())))
                .build();
    }

    private static int open(CommandContext<CommandSourceStack> ctx, String page) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(I18n.name(
                    "message.wandscape.command.guide_players_only",
                    "[魔法小镇] 该指令只能由玩家在游戏内执行"));
            return 0;
        }
        PacketDistributor.sendToPlayer(player, new GuideBookOpenPacket(page));
        return Command.SINGLE_SUCCESS;
    }
}
