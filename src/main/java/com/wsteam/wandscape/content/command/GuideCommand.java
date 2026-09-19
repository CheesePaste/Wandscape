package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.content.items.guidebook.network.GuideBookOpenPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.guidebook.GuideManifest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Set;

/**
 * 打开模组指南书。
 *
 * <p>不带页名打开手册首页（着陆页）；也可传入页名直达：条目 id（{@code townhall_guide}）、
 * 去掉词尾 {@code _guide} 的别名（{@code warehouse}），或分类 {@code category:buildings}。
 *
 * <p>页名经 {@code GuideBookOpenPacket} 交给客户端：装了 Patchouli 开手册条目，
 * 没装走兜底阅读器，两边认同一套页名。未知页名在帕秋莉侧落到手册首页、在兜底侧显示 404 页。
 * 玩家无需权限。
 */
public final class GuideCommand {

    /** 默认页：空串＝手册首页（着陆页），由 {@link GuideManifest#ROOT_PAGE} 解析。 */
    public static final String DEFAULT_PAGE = "";

    private GuideCommand() {}

    /**
     * 补全列表取自运行期清单（页 id 与语言无关，所以服务端直接读它）。
     * 以前这里是一串手写的页名，删了几篇文档就有 13 项指向不存在的页。
     */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PAGES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(suggestedPages(), builder);

    private static List<String> suggestedPages() {
        GuideManifest manifest = GuideManifest.anyLocale();
        if (manifest == null) {
            return List.of(GuideManifest.ROOT_PAGE);
        }
        List<String> pages = new java.util.ArrayList<>();
        for (GuideManifest.Category category : manifest.categories()) {
            pages.add(GuideManifest.CATEGORY_PREFIX + category.id());
        }
        Set<String> seen = new java.util.HashSet<>();
        for (GuideManifest.Entry entry : manifest.entries()) {
            if (seen.add(entry.doc())) {
                pages.add(entry.doc());
                if (entry.doc().endsWith("_guide")) {
                    pages.add(entry.doc().substring(0, entry.doc().length() - "_guide".length()));
                }
            }
        }
        return pages;
    }

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
