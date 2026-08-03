package com.wsteam.wandscape.shared.ui.guide;

import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentHistoryStack;
import com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader;
import com.wsteam.wandscape.shared.ui.markdown.widget.MarkdownRenderWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Interactive Markdown Guide Screen supporting inter-document navigation,
 * browser-like back/forward history stack, and ESC key press interception.
 */
public class GuideTestScreen extends MedievalScreen {

    private final Screen parentScreen;
    private final DocumentHistoryStack historyStack;
    private MarkdownRenderWidget markdownWidget;

    private MedievalButton btnBack;
    private MedievalButton btnForward;

    public GuideTestScreen(String initialMarkdownContent) {
        this(null, initialMarkdownContent, "assets/wandscape/guide/test_guide.md");
    }

    public GuideTestScreen(Screen parentScreen, String initialMarkdownContent, String initialDocPath) {
        super(Component.literal("Wandscape 引导系统"), 320, 230);
        this.parentScreen = parentScreen;
        this.historyStack = new DocumentHistoryStack(initialDocPath);
        this.showCloseButton = true;
        this.titleXOffset = 52;
        setTitleBar(Component.literal("Wandscape 引导指南"));
    }

    @Override
    protected void init() {
        super.init();

        int navY = topPos + (headerHeight - 14) / 2;

        // Navigation Back button ◄ (positioned top left inside header)
        btnBack = new MedievalButton(leftPos + 6, navY, 20, 14, Component.literal("◄"), this::handleGoBack);
        addRenderableWidget(btnBack);

        // Navigation Forward button ►
        btnForward = new MedievalButton(leftPos + 28, navY, 20, 14, Component.literal("►"), this::handleGoForward);
        addRenderableWidget(btnForward);

        int contentX = leftPos + 6;
        int contentY = topPos + headerHeight + 4;
        int contentW = panelWidth - 12;
        int contentH = panelHeight - headerHeight - 10;

        String currentDocPath = historyStack.getCurrentDocument();
        String content = DocumentLoader.loadMarkdown(currentDocPath);

        markdownWidget = new MarkdownRenderWidget(contentX, contentY, contentW, contentH, content);
        markdownWidget.setActionClickListener(this::handleLinkAction);

        addRenderableWidget(markdownWidget);

        updateNavigationState();
    }

    private void handleGoBack() {
        if (historyStack.canGoBack()) {
            String prevDoc = historyStack.goBack();
            loadDocument(prevDoc);
        }
    }

    private void handleGoForward() {
        if (historyStack.canGoForward()) {
            String nextDoc = historyStack.goForward();
            loadDocument(nextDoc);
        }
    }

    private void handleLinkAction(String action) {
        if (action == null || action.isBlank()) {
            return;
        }

        // Inter-document navigation link (guide:doc_path)
        if (action.startsWith("guide:")) {
            String docPath = action.substring(6).trim();
            historyStack.navigateTo(docPath);
            loadDocument(docPath);
            return;
        }

        // Game action link (action:...)
        Log.debug("GuideTestScreen", "[Guide] Triggered in-game action: {}", action);
    }

    private void loadDocument(String docPath) {
        String mdContent = DocumentLoader.loadMarkdown(docPath);
        if (markdownWidget != null) {
            markdownWidget.setMarkdown(mdContent);
        }
        updateNavigationState();
    }

    private void updateNavigationState() {
        if (btnBack != null) {
            btnBack.active = historyStack.canGoBack();
        }
        if (btnForward != null) {
            btnForward.active = historyStack.canGoForward();
        }
    }

    /**
     * Whether this guide is currently showing the given document path.
     * Used by the spline editor to detect its own guide (for H-toggle/ESC close).
     */
    public boolean isShowingDocument(String docPath) {
        return historyStack != null && docPath != null
                && docPath.equals(historyStack.getCurrentDocument());
    }

    @Override
    public void onClose() {
        if (parentScreen != null && minecraft != null) {
            minecraft.setScreen(parentScreen);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true; // Intercept ESC key press event, prevent event pass-through to parent screen
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
