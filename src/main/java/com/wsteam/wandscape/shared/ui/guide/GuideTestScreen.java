package com.wsteam.wandscape.shared.ui.guide;

import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.markdown.widget.MarkdownRenderWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Test GUI Screen displaying Markdown documents rendered by {@link MarkdownRenderWidget}.
 */
public class GuideTestScreen extends MedievalScreen {

    private final String markdownContent;
    private MarkdownRenderWidget markdownWidget;

    public GuideTestScreen(String markdownContent) {
        super(Component.literal("Wandscape 引导系统"), 300, 220);
        this.markdownContent = markdownContent;
        this.showCloseButton = true;
        setTitleBar("Wandscape Markdown 视窗测试");
    }

    @Override
    protected void init() {
        super.init();

        int contentX = leftPos + 6;
        int contentY = topPos + headerHeight + 4;
        int contentW = panelWidth - 12;
        int contentH = panelHeight - headerHeight - 10;

        markdownWidget = new MarkdownRenderWidget(contentX, contentY, contentW, contentH, markdownContent);
        markdownWidget.setActionClickListener(action -> {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendSystemMessage(
                        Component.literal("§a[Wandscape Markdown] 点击了动作链接: " + action)
                );
            }
        });

        addRenderableWidget(markdownWidget);
    }
}
