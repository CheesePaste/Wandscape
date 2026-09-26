package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.production.ProductionRecipeManager;
import com.wsteam.wandscape.content.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.content.production.network.RecipeBookDataPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 殖民地合成配方指令（配方解锁、锁定、查询）。
 *
 * <p>用法：
 * <pre>
 *   /wandscape recipe unlock_all [target]  — 一键解锁所有配方（target 可为 all 或指定小镇，缺省为当前小镇）
 *   /wandscape recipe lock_all [target]    — 一键重置/锁定所有配方
 *   /wandscape recipe unlock <id> [target] — 解锁指定配方
 *   /wandscape recipe lock <id> [target]   — 锁定指定配方
 *   /wandscape recipe list [target]        — 查看配方解锁统计
 * </pre>
 */
public final class RecipeCommand {

    private RecipeCommand() {}

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_RECIPES = (ctx, builder) -> {
        var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
        if (loader != null) {
            List<String> ids = loader.getAllSynthesizeRecipes().stream()
                    .map(SynthesizeRecipe::id)
                    .toList();
            return SharedSuggestionProvider.suggest(ids, builder);
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TARGETS = (ctx, builder) -> {
        List<String> list = new ArrayList<>();
        list.add("all");
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi != null) {
            for (UUID id : colonyApi.getAllColonyIds()) {
                list.add(id.toString());
                list.add(CommandUtil.shortId(id));
            }
        }
        return SharedSuggestionProvider.suggest(list, builder);
    };

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("recipe")
                .then(Commands.literal("unlock_all")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> unlockAll(ctx, null))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(SUGGEST_TARGETS)
                                .executes(ctx -> unlockAll(ctx, StringArgumentType.getString(ctx, "target")))))
                .then(Commands.literal("unlockall")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> unlockAll(ctx, null))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(SUGGEST_TARGETS)
                                .executes(ctx -> unlockAll(ctx, StringArgumentType.getString(ctx, "target")))))
                .then(Commands.literal("lock_all")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> lockAll(ctx, null))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(SUGGEST_TARGETS)
                                .executes(ctx -> lockAll(ctx, StringArgumentType.getString(ctx, "target")))))
                .then(Commands.literal("lockall")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> lockAll(ctx, null))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(SUGGEST_TARGETS)
                                .executes(ctx -> lockAll(ctx, StringArgumentType.getString(ctx, "target")))))
                .then(Commands.literal("unlock")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("recipe", StringArgumentType.string())
                                .suggests(SUGGEST_RECIPES)
                                .executes(ctx -> unlockOne(ctx, null))
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .suggests(SUGGEST_TARGETS)
                                        .executes(ctx -> unlockOne(ctx, StringArgumentType.getString(ctx, "target"))))))
                .then(Commands.literal("lock")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("recipe", StringArgumentType.string())
                                .suggests(SUGGEST_RECIPES)
                                .executes(ctx -> lockOne(ctx, null))
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .suggests(SUGGEST_TARGETS)
                                        .executes(ctx -> lockOne(ctx, StringArgumentType.getString(ctx, "target"))))))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx, null))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(SUGGEST_TARGETS)
                                .executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "target")))))
                .then(Commands.literal("view")
                        .executes(ctx -> list(ctx, null))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(SUGGEST_TARGETS)
                                .executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "target")))))
                .build();
    }

    private static int unlockAll(CommandContext<CommandSourceStack> ctx, @Nullable String target) {
        CommandSourceStack src = ctx.getSource();
        ColonyResolveResult resolved = resolveTargetColonies(src, target);
        if (resolved.error() != null) {
            src.sendFailure(resolved.error());
            return 0;
        }

        int totalRecipes = ProductionRecipeManager.getTotalSynthesizeRecipeCount();
        int totalNewlyUnlocked = 0;
        for (UUID cid : resolved.colonies()) {
            totalNewlyUnlocked += ProductionRecipeManager.unlockAllSynthesize(cid, ProductionRecipeManager.SOURCE_COMMAND);
        }

        ServerPlayer player = CommandUtil.player(src);
        if (player != null && resolved.colonies().size() == 1) {
            RecipeBookDataPacket.sendTo(player, resolved.colonies().get(0));
        }

        if (resolved.isAll()) {
            int finalNewlyUnlocked = totalNewlyUnlocked;
            src.sendSuccess(() -> I18n.name("message.wandscape.command.recipe_unlock_all_colonies_success",
                    "[魔法小镇] 已为全部 %s 个小镇解锁所有合成配方（共新解锁 %s 个配方条目）",
                    resolved.colonies().size(), finalNewlyUnlocked), true);
        } else {
            UUID cid = resolved.colonies().get(0);
            String shortId = CommandUtil.shortId(cid);
            int finalUnlocked = totalNewlyUnlocked;
            src.sendSuccess(() -> I18n.name("message.wandscape.command.recipe_unlock_all_success",
                    "[魔法小镇] 已为小镇 %s 解锁全部 %s 个合成配方（本次新解锁 %s 个）",
                    shortId, totalRecipes, finalUnlocked), true);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int lockAll(CommandContext<CommandSourceStack> ctx, @Nullable String target) {
        CommandSourceStack src = ctx.getSource();
        ColonyResolveResult resolved = resolveTargetColonies(src, target);
        if (resolved.error() != null) {
            src.sendFailure(resolved.error());
            return 0;
        }

        int totalLocked = 0;
        for (UUID cid : resolved.colonies()) {
            totalLocked += ProductionRecipeManager.lockAllSynthesize(cid);
        }

        ServerPlayer player = CommandUtil.player(src);
        if (player != null && resolved.colonies().size() == 1) {
            RecipeBookDataPacket.sendTo(player, resolved.colonies().get(0));
        }

        if (resolved.isAll()) {
            src.sendSuccess(() -> I18n.name("message.wandscape.command.recipe_lock_all_colonies_success",
                    "[魔法小镇] 已重置并锁定全部 %s 个小镇的合成配方",
                    resolved.colonies().size()), true);
        } else {
            UUID cid = resolved.colonies().get(0);
            String shortId = CommandUtil.shortId(cid);
            int finalLocked = totalLocked;
            src.sendSuccess(() -> I18n.name("message.wandscape.command.recipe_lock_all_success",
                    "[魔法小镇] 已重置并锁定小镇 %s 的所有合成配方（已锁定 %s 个）",
                    shortId, finalLocked), true);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int unlockOne(CommandContext<CommandSourceStack> ctx, @Nullable String target) {
        CommandSourceStack src = ctx.getSource();
        String recipeArg = StringArgumentType.getString(ctx, "recipe");
        var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
        if (loader == null) return 0;

        SynthesizeRecipe recipe = loader.getSynthesizeRecipe(recipeArg);
        if (recipe == null) {
            src.sendFailure(I18n.name("message.wandscape.command.recipe_unknown",
                    "[魔法小镇] 未知合成配方：%s", recipeArg));
            return 0;
        }

        ColonyResolveResult resolved = resolveTargetColonies(src, target);
        if (resolved.error() != null) {
            src.sendFailure(resolved.error());
            return 0;
        }

        String recipeId = recipe.id();
        int newlyUnlockedCount = 0;
        for (UUID cid : resolved.colonies()) {
            if (ProductionRecipeManager.unlockSynthesize(cid, recipeId, ProductionRecipeManager.SOURCE_COMMAND)) {
                newlyUnlockedCount++;
            }
        }

        ServerPlayer player = CommandUtil.player(src);
        if (player != null && resolved.colonies().size() == 1) {
            RecipeBookDataPacket.sendTo(player, resolved.colonies().get(0));
        }

        if (resolved.isAll()) {
            src.sendSuccess(() -> I18n.name("message.wandscape.command.recipe_unlock_one_colonies_success",
                    "[魔法小镇] 已为全部 %s 个小镇解锁配方：%s",
                    resolved.colonies().size(), recipeId), true);
        } else {
            UUID cid = resolved.colonies().get(0);
            String shortId = CommandUtil.shortId(cid);
            if (newlyUnlockedCount > 0) {
                src.sendSuccess(() -> I18n.name("message.wandscape.command.recipe_unlock_one_success",
                        "[魔法小镇] 已为小镇 %s 解锁配方：%s",
                        shortId, recipeId), true);
            } else {
                src.sendSuccess(() -> I18n.name("message.wandscape.command.recipe_already_unlocked",
                        "[魔法小镇] 小镇 %s 的配方「%s」之前已处于解锁状态",
                        shortId, recipeId), false);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int lockOne(CommandContext<CommandSourceStack> ctx, @Nullable String target) {
        CommandSourceStack src = ctx.getSource();
        String recipeArg = StringArgumentType.getString(ctx, "recipe");
        var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
        if (loader == null) return 0;

        SynthesizeRecipe recipe = loader.getSynthesizeRecipe(recipeArg);
        if (recipe == null) {
            src.sendFailure(I18n.name("message.wandscape.command.recipe_unknown",
                    "[魔法小镇] 未知合成配方：%s", recipeArg));
            return 0;
        }

        ColonyResolveResult resolved = resolveTargetColonies(src, target);
        if (resolved.error() != null) {
            src.sendFailure(resolved.error());
            return 0;
        }

        String recipeId = recipe.id();
        int lockedCount = 0;
        for (UUID cid : resolved.colonies()) {
            if (ProductionRecipeManager.lockSynthesize(cid, recipeId)) {
                lockedCount++;
            }
        }

        ServerPlayer player = CommandUtil.player(src);
        if (player != null && resolved.colonies().size() == 1) {
            RecipeBookDataPacket.sendTo(player, resolved.colonies().get(0));
        }

        if (resolved.isAll()) {
            src.sendSuccess(() -> I18n.name("message.wandscape.command.recipe_lock_one_colonies_success",
                    "[魔法小镇] 已为全部 %s 个小镇锁定配方：%s",
                    resolved.colonies().size(), recipeId), true);
        } else {
            UUID cid = resolved.colonies().get(0);
            String shortId = CommandUtil.shortId(cid);
            if (lockedCount > 0) {
                src.sendSuccess(() -> I18n.name("message.wandscape.command.recipe_lock_one_success",
                        "[魔法小镇] 已为小镇 %s 锁定配方：%s",
                        shortId, recipeId), true);
            } else {
                src.sendSuccess(() -> I18n.name("message.wandscape.command.recipe_not_unlocked",
                        "[魔法小镇] 小镇 %s 的配方「%s」未处于解锁状态",
                        shortId, recipeId), false);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int list(CommandContext<CommandSourceStack> ctx, @Nullable String target) {
        CommandSourceStack src = ctx.getSource();
        ColonyResolveResult resolved = resolveTargetColonies(src, target);
        if (resolved.error() != null) {
            src.sendFailure(resolved.error());
            return 0;
        }

        int totalRecipes = ProductionRecipeManager.getTotalSynthesizeRecipeCount();
        for (UUID cid : resolved.colonies()) {
            int unlocked = ProductionRecipeManager.getUnlockedRecipes(cid).size();
            String shortId = CommandUtil.shortId(cid);
            src.sendSuccess(() -> I18n.name("message.wandscape.command.recipe_list_info",
                    "[魔法小镇] 小镇 %s 合成配方：已解锁 %s / %s 种",
                    shortId, unlocked, totalRecipes), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private record ColonyResolveResult(List<UUID> colonies, boolean isAll, @Nullable Component error) {}

    private static ColonyResolveResult resolveTargetColonies(CommandSourceStack src, @Nullable String target) {
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) {
            return new ColonyResolveResult(List.of(), false,
                    Component.literal("[魔法小镇] 殖民地系统未就绪"));
        }

        if (target != null && target.equalsIgnoreCase("all")) {
            var allIds = colonyApi.getAllColonyIds();
            if (allIds.isEmpty()) {
                return new ColonyResolveResult(List.of(), true,
                        I18n.name("message.wandscape.command.recipe_no_colony",
                                "[魔法小镇] 未检测到小镇：请在小镇范围内使用，或先创建小镇"));
            }
            return new ColonyResolveResult(new ArrayList<>(allIds), true, null);
        }

        if (target != null) {
            UUID found = null;
            try {
                UUID direct = UUID.fromString(target);
                if (colonyApi.getAllColonyIds().contains(direct)) {
                    found = direct;
                }
            } catch (IllegalArgumentException ignored) {}

            if (found == null) {
                for (UUID id : colonyApi.getAllColonyIds()) {
                    if (id.toString().startsWith(target) || CommandUtil.shortId(id).equalsIgnoreCase(target)) {
                        found = id;
                        break;
                    }
                }
            }

            if (found == null) {
                return new ColonyResolveResult(List.of(), false,
                        I18n.name("message.wandscape.command.recipe_colony_not_found",
                                "[魔法小镇] 未找到小镇：%s", target));
            }
            return new ColonyResolveResult(List.of(found), false, null);
        }

        // target is null: resolve default
        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId != null) {
            return new ColonyResolveResult(List.of(colonyId), false, null);
        }

        var allIds = colonyApi.getAllColonyIds();
        if (allIds.isEmpty()) {
            return new ColonyResolveResult(List.of(), false,
                    I18n.name("message.wandscape.command.recipe_no_colony",
                            "[魔法小镇] 未检测到小镇：请在小镇范围内使用，或先创建小镇"));
        }
        if (allIds.size() == 1) {
            return new ColonyResolveResult(List.of(allIds.iterator().next()), false, null);
        }

        return new ColonyResolveResult(List.of(), false,
                I18n.name("message.wandscape.command.recipe_ambiguous_colony",
                        "[魔法小镇] 检测到多个小镇：请在对应小镇范围内使用，或指定小镇 ID / all"));
    }
}
