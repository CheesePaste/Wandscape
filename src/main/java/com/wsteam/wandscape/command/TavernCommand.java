package com.wsteam.wandscape.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.shared.data.MageAttributeRoller;
import com.wsteam.wandscape.shared.data.MageResume;
import com.wsteam.wandscape.shared.data.RecruitmentCandidate;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.tourist.entity.TouristEntity;
import com.wsteam.wandscape.tourist.internal.TouristSpawnSystem;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;
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
            src.sendFailure(Component.literal("[Wandscape] 未检测到殖民地，请在殖民地范围内使用或先创建殖民地"));
            return 0;
        }

        int safeLevel = Math.clamp(level, 1, 10);
        RecruitmentCandidate candidate = MageAttributeRoller.roll(safeLevel,
                new java.util.Random(serverLevel.random.nextLong()));
        String name = TouristSpawnSystem.generateRandomTouristName();
        int variant = serverLevel.random.nextInt(TouristEntity.WIZARD_SKIN_COUNT);

        try {
            var tavernApi = WandscapeApis.getTavernApi();
            tavernApi.receiveMageResume(colonyId, name, safeLevel,
                    candidate.maxHp(), candidate.moveSpeed(), candidate.spellPower(),
                    candidate.workSpeed(), candidate.spellSpeed(), candidate.armorValue(),
                    candidate.maxMana(), variant);

            Component msg = Component.literal(String.format(
                    "[Tavern] 已向殖民地 %s 酒馆添加法师简历：%s (Lv.%d)\n"
                    + "  属性: 生命 %.0f, 魔力 %.0f, 强度 %.2f, 工速 %.2f, 施速 %.2f, 护甲 %.1f\n"
                    + "  可直接前往酒馆打开「法师简历」标签页招聘",
                    colonyId.toString().substring(0, 8), name, safeLevel,
                    candidate.maxHp(), candidate.maxMana(), candidate.spellPower(),
                    candidate.workSpeed(), candidate.spellSpeed(), candidate.armorValue()));
            src.sendSuccess(() -> msg, false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            src.sendFailure(Component.literal("[Wandscape] 酒馆系统不可用: " + e.getMessage()));
            return 0;
        }
    }

    private static int listResumes(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Vec3 pos = src.getPosition();

        UUID colonyId = resolveColonyId(pos);
        if (colonyId == null) {
            src.sendFailure(Component.literal("[Wandscape] 未检测到殖民地"));
            return 0;
        }

        try {
            var tavernApi = WandscapeApis.getTavernApi();
            List<MageResume> resumes = tavernApi.getMageResumes(colonyId);
            if (resumes.isEmpty()) {
                Component emptyMsg = Component.literal("[Tavern] 殖民地 " + colonyId.toString().substring(0, 8) + " 当前无待招聘法师简历");
                src.sendSuccess(() -> emptyMsg, false);
                return Command.SINGLE_SUCCESS;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== 酒馆法师简历 (").append(resumes.size()).append(" 份) ===\n");
            for (int i = 0; i < resumes.size(); i++) {
                MageResume r = resumes.get(i);
                sb.append(String.format("[%d] %s (Lv.%d) - 强度:%.2f 工速:%.2f 施速:%.2f 护甲:%.1f 生命:%.0f\n",
                        i, r.touristName(), r.level(), r.spellPower(), r.workSpeed(), r.spellSpeed(), r.armorValue(), r.maxHp()));
            }
            Component listMsg = Component.literal(sb.toString().trim());
            src.sendSuccess(() -> listMsg, false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            src.sendFailure(Component.literal("[Wandscape] 获取酒馆简历失败: " + e.getMessage()));
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
