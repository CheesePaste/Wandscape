package com.wsteam.wandscape.shared.ui.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.ui.skin.SkinSprite;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
/**
 * Demo screen showing all Wandscape UI components.
 * Press M in-game to open. For development/testing only.
 */
public class DemoScreen extends MedievalScreen {

    private static final int PW = 320;
    private static final int PH = 220;

    private TabBar tabBar;
    private int selectedTab;

    // Tab 0: Buttons
    private MedievalButton normalBtn;
    private MedievalButton disabledBtn;
    private IconButton iconBtn;
    private HelpButton helpBtn;
    private OptionButton optionBtn;
    private LessButton lessBtn;
    private MoreButton moreBtn;
    private LeftArrowButton leftArrowBtn;
    private RightArrowButton rightArrowBtn;

    // Tab 1: Lists
    private ScrollableList<String> stringList;

    // Tab 2: Elements
    private ElementPanel elementPanel;

    // Tab 3: Inputs
    private SearchBar searchBar;
    private Slider slider;

    // Tab 4: Progress
    private ProgressIndicator progressBar;

    private static final List<String> TAB_LABELS = List.of("Buttons", "Lists", "Elements", "Inputs", "Progress");

    public DemoScreen() {
        super(Component.literal("Wandscape UI Demo"), PW, PH);
        setTitleBar("Wandscape UI Demo");
        headerHeight = 24;
    }

    @Override
    protected void init() {
        super.init();

        int contentX = leftPos + 4;
        int contentY = topPos + headerHeight + 2; // below title bar
        int contentW = PW - 8;

        // Tab bar below title bar
        tabBar = new TabBar(contentX, contentY, contentW, TAB_LABELS, 0, idx -> {
            selectedTab = idx;
            refreshTab();
        });
        addRenderableWidget(tabBar);

        int tabContentY = contentY + 18;
        int tabContentH = PH - headerHeight - 2 - 18 - 10;

        // -- Tab 0: Buttons --
        normalBtn = new MedievalButton(contentX + 5, tabContentY + 5, 140, 20,
                Component.literal("Normal Button"), () -> {});
        disabledBtn = new MedievalButton(contentX + 5, tabContentY + 30, 140, 20,
                Component.literal("Disabled Button"), () -> {});
        disabledBtn.active = false;
        iconBtn = new IconButton(contentX + 160, tabContentY + 5, 24, "X",
                MedievalColors.DANGER_RED, Component.literal("Close"), () -> {},
                SkinSprite.CLOSE_BTN);
        helpBtn = new HelpButton(contentX + 195, tabContentY + 3, 24, 24, () -> {});
        optionBtn = new OptionButton(contentX + 225, tabContentY + 3, 24, 24, () -> {});
        lessBtn = new LessButton(contentX + 5, tabContentY + 55, () -> {});
        moreBtn = new MoreButton(contentX + 35, tabContentY + 55, () -> {});
        leftArrowBtn = new LeftArrowButton(contentX + 75, tabContentY + 60, () -> {});
        rightArrowBtn = new RightArrowButton(contentX + 101, tabContentY + 60, () -> {});

        // -- Tab 1: Lists --
        stringList = new ScrollableList<>(contentX + 5, tabContentY, contentW - 10, tabContentH, 18) {
            @Override
            protected void renderRow(GuiGraphics g, String item, int x, int y, int index,
                                      boolean selected, boolean hovered) {
                int color = selected ? MedievalColors.ACCENT_GOLD
                        : hovered ? MedievalColors.TEXT_WARM_WHITE
                        : MedievalColors.TEXT_MUTED;
                g.drawString(Minecraft.getInstance().font, item, x, y + 4, color);
            }
        };
        List<String> sampleItems = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            sampleItems.add("List Item #" + i);
        }
        stringList.setItems(sampleItems);

        // -- Tab 2: Elements --
        elementPanel = new ElementPanel(contentX + 5, tabContentY + 5, 160);
        Map<ElementType, Long> sampleElements = new LinkedHashMap<>();
        sampleElements.put(ElementType.EARTH, 128000L);
        sampleElements.put(ElementType.WOOD, 52000L);
        sampleElements.put(ElementType.WATER, 8000L);
        sampleElements.put(ElementType.FIRE, 1200L);
        sampleElements.put(ElementType.METAL, 450L);
        sampleElements.put(ElementType.WIND, 3200L);
        sampleElements.put(ElementType.DARK, 64L);
        elementPanel.setElements(sampleElements);

        // -- Tab 3: Inputs --
        searchBar = new SearchBar(contentX + 5, tabContentY + 5, 200, 16,
                "Search items...", s -> {});
        slider = new Slider(contentX + 5, tabContentY + 30, 200,
                1, 64, 32, v -> {});

        // -- Tab 4: Progress --
        progressBar = new ProgressIndicator(contentX + 5, tabContentY + 5, 200, 16, 0.65f);
        progressBar.setLabel("65%");

        // Show initial tab
        refreshTab();
    }

    private void refreshTab() {
        clearWidgets();

        // Always re-add tab bar
        addRenderableWidget(tabBar);

        switch (selectedTab) {
            case 0 -> {
                addRenderableWidget(normalBtn);
                addRenderableWidget(disabledBtn);
                addRenderableWidget(iconBtn);
                addRenderableWidget(helpBtn);
                addRenderableWidget(optionBtn);
                addRenderableWidget(lessBtn);
                addRenderableWidget(moreBtn);
                addRenderableWidget(leftArrowBtn);
                addRenderableWidget(rightArrowBtn);
            }
            case 1 -> addRenderableWidget(stringList);
            case 2 -> addRenderableWidget(elementPanel);
            case 3 -> {
                addRenderableWidget(searchBar);
                addRenderableWidget(slider);
            }
            case 4 -> addRenderableWidget(progressBar);
        }
    }
}
