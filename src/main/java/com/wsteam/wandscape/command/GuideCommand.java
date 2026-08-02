package com.wsteam.wandscape.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.shared.network.GuideTestPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Debug command to launch the Markdown guide test screen.
 * Syntax: /wandscape guide
 */
public final class GuideCommand {

    private GuideCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("guide")
                .executes(ctx -> executeTest(ctx.getSource()))
                .build();
    }

    private static int executeTest(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[Wandscape] 该指令只能由玩家在游戏内执行"));
            return 0;
        }

        String markdownContent;
        try {
            InputStream is = GuideCommand.class.getClassLoader()
                    .getResourceAsStream("assets/wandscape/guide/test_guide.md");
            if (is != null) {
                markdownContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                is.close();
            } else {
                markdownContent = "# Wandscape Guide\n\nFailed to load test_guide.md from classpath.";
            }
        } catch (Exception e) {
            markdownContent = "# Wandscape Guide Error\n\nError: " + e.getMessage();
        }

        PacketDistributor.sendToPlayer(player, new GuideTestPacket(markdownContent));
        source.sendSuccess(() -> Component.literal("[Wandscape] 已成功打开 Markdown 引导测试视窗"), false);

        return Command.SINGLE_SUCCESS;
    }
}
