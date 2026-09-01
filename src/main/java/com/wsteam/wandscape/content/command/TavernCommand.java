package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes;
import com.wsteam.wandscape.content.npc.data.MageResume;
import com.wsteam.wandscape.content.npc.data.RecruitmentCandidate;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.content.tourist.entity.TouristEntity;
import com.wsteam.wandscape.content.tourist.internal.TouristSpawnSystem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Debug commands for Tavern recruitment testing.
 *
 * <pre>
 * /wandscape tavern recruit [level]      — 生成三值全满法师并录入酒馆简历
 * /wandscape tavern add_resume [level]   — 直接向酒馆添加一份满条法师简历
 * /wandscape tavern list                 — 查看当前酒馆中的所有法师简历
 * </pre>
 */
public final class TavernCommand {

    private static final String TAG = "TavernCommand";

    private TavernCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("tavern")
                .then(Commands.literal("recruit")
                        .executes(ctx -> TouristCommand.spawnRecruitableMage(ctx, 1))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 10))
                                .executes(ctx -> TouristCommand.spawnRecruitableMage(ctx, IntegerArgumentType.getInteger(ctx, "level")))))
                .then(Commands.literal("spawn_mage")
                        .executes(ctx -> TouristCommand.spawnRecruitableMage(ctx, 1))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 10))
                                .executes(ctx -> TouristCommand.spawnRecruitableMage(ctx, IntegerArgumentType.getInteger(ctx, "level")))))
                .then(Commands.literal("add_resume")
                        .executes(ctx -> addResumeDirectly(ctx, 1))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 10))
                                .executes(ctx -> addResumeDirectly(ctx, IntegerArgumentType.getInteger(ctx, "level")))))
                .then(Commands.literal("list")
                        .executes(TavernCommand::listResumes))
                .build();
    }

    private static int addResumeDirectly(CommandContext<CommandSourceStack> ctx, int level) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel serverLevel = src.getLevel();
        Vec3 pos = src.getPosition();

        UUID colonyId = resolveColonyId(pos);
        if (colonyId == null) {
            src.sendFailure(I18n.name("message.wandscape.command.tavern_no_colony_detailed",
                    "[Wandscape] 未检测到小镇，请在小镇范围内使用或先创建小镇"));
            return 0;
        }

        int safeLevel = Math.clamp(level, 1, 10);
        RecruitmentCandidate candidate = NpcAttributes.roll(safeLevel,
                new java.util.Random(serverLevel.random.nextLong()));
        String name = TouristSpawnSystem.generateRandomTouristName(colonyId);
        int variant = serverLevel.random.nextInt(TouristEntity.WIZARD_SKIN_COUNT);

        var recruitStorage = com.wsteam.wandscape.content.tourist.internal.TavernRecruitStorage.getOrCreate(serverLevel);
        recruitStorage.addResume(colonyId, new com.wsteam.wandscape.content.npc.data.MageResume(
                name, safeLevel, candidate.maxHp(), candidate.moveSpeed(), candidate.spellPower(),
                candidate.workSpeed(), candidate.spellSpeed(), candidate.armorValue(),
                candidate.maxMana(), variant, System.currentTimeMillis()));

        Component msg = I18n.name("message.wandscape.command.tavern_resume_added",
                "[Tavern] 已向小镇 %s 酒馆添加法师简历：%s (Lv.%s)\n"
                + "  属性: 生命 %s, 魔力 %s, 强度 %s, 工速 %s, 施速 %s, 护甲 %s\n"
                + "  可直接前往酒馆打开「法师简历」标签页招聘",
                colonyId.toString().substring(0, 8), name, safeLevel,
                String.format(Locale.ROOT, "%.0f", candidate.maxHp()),
                String.format(Locale.ROOT, "%.0f", candidate.maxMana()),
                String.format(Locale.ROOT, "%.2f", candidate.spellPower()),
                String.format(Locale.ROOT, "%.2f", candidate.workSpeed()),
                String.format(Locale.ROOT, "%.2f", candidate.spellSpeed()),
                String.format(Locale.ROOT, "%.1f", candidate.armorValue()));
        src.sendSuccess(() -> msg, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int listResumes(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Vec3 pos = src.getPosition();

        UUID colonyId = resolveColonyId(pos);
        if (colonyId == null) {
            src.sendFailure(I18n.name("message.wandscape.command.tavern_no_colony",
                    "[Wandscape] 未检测到小镇"));
            return 0;
        }

        try {
            var tavernApi = WandscapeApis.getTavernApi();
            List<MageResume> resumes = tavernApi.getMageResumes(colonyId);
            if (resumes.isEmpty()) {
                Component emptyMsg = I18n.name("message.wandscape.command.tavern_no_resumes",
                        "[Tavern] 小镇 %s 当前无待招聘法师简历", colonyId.toString().substring(0, 8));
                src.sendSuccess(() -> emptyMsg, false);
                return Command.SINGLE_SUCCESS;
            }

            MutableComponent listMsg = I18n.name("message.wandscape.command.tavern_resume_header",
                    "=== 酒馆法师简历 (%s 份) ===\n", resumes.size());
            for (int i = 0; i < resumes.size(); i++) {
                MageResume r = resumes.get(i);
                listMsg.append(I18n.name("message.wandscape.command.tavern_resume_line",
                        "[%s] %s (Lv.%s) - 强度:%s 工速:%s 施速:%s 护甲:%s 生命:%s\n",
                        i, r.touristName(), r.level(),
                        String.format(Locale.ROOT, "%.2f", r.spellPower()),
                        String.format(Locale.ROOT, "%.2f", r.workSpeed()),
                        String.format(Locale.ROOT, "%.2f", r.spellSpeed()),
                        String.format(Locale.ROOT, "%.1f", r.armorValue()),
                        String.format(Locale.ROOT, "%.0f", r.maxHp())));
            }
            src.sendSuccess(() -> listMsg, false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            src.sendFailure(I18n.name("message.wandscape.command.tavern_list_failed",
                    "[Wandscape] 获取酒馆简历失败: %s", e.getMessage()));
            return 0;
        }
    }

    private static UUID resolveColonyId(Vec3 pos) {
        var colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return null;
        UUID colonyId = colonyApi.getColonyId(BlockPos.containing(pos));
        if (colonyId == null) {
            var ids = colonyApi.getAllColonyIds();
            if (!ids.isEmpty()) {
                colonyId = ids.iterator().next();
            }
        }
        return colonyId;
    }
}
