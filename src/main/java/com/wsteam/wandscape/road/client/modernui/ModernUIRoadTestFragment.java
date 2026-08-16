package com.wsteam.wandscape.road.client.modernui;

import com.wsteam.wandscape.shared.log.Log;
import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.SeekBar;
import icyllis.modernui.widget.SwitchButton;
import icyllis.modernui.widget.TextView;
import icyllis.modernui.widget.Toast;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;

/**
 * ModernUI Pure Java Test Fragment for Road Studio.
 * Demonstrates Android-style layout hierarchy, custom drawables, and live interactive state.
 */
public class ModernUIRoadTestFragment extends Fragment {
    private static final String TAG = "ModernUIRoadTestFragment";

    // Color Palette (Catppuccin Mocha / Wandscape Theme)
    private static final int COLOR_SCRIM       = 0x9911111B;
    private static final int COLOR_CARD_BG     = 0xF51E1E2E;
    private static final int COLOR_CARD_BORDER = 0xFF89B4FA;
    private static final int COLOR_SECTION_BG  = 0xCC181825;
    private static final int COLOR_TEXT_MAIN   = 0xFFCDD6F4;
    private static final int COLOR_TEXT_MUTED  = 0xFFA6ADC8;
    private static final int COLOR_TEXT_DIM    = 0xFF6C7086;
    private static final int COLOR_ACCENT_BLUE = 0xFF89B4FA;
    private static final int COLOR_ACCENT_GOLD = 0xFFF9E2AF;
    private static final int COLOR_ACCENT_GREEN = 0xFFA6E3A1;
    private static final int COLOR_BTN_BG      = 0xFF313244;
    private static final int COLOR_BTN_HOVER   = 0xFF45475A;
    private static final int COLOR_DANGER      = 0xFFF38BA8;

    // Interactive Demo State
    private int clickCount = 0;
    private int roadWidth = 5;
    private int stepDistance = 2;
    private String activeTab = "曲线编辑";

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, DataSet savedInstanceState) {
        Context context = requireContext();

        // ── Root Overlay (Full Screen, Dimmed Scrim, Centered Modal) ──
        FrameLayout root = new FrameLayout(context);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.setBackground(new ColorDrawable(COLOR_SCRIM));

        // Close on clicking backdrop outside card
        root.setOnClickListener(v -> closeScreen());

        // ── Modal Card Container ──
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                root.dp(480),
                root.dp(560)
        );
        cardLp.gravity = Gravity.CENTER;
        card.setLayoutParams(cardLp);

        // Styled Card Background with Rounded Corners & Border
        ShapeDrawable cardDrawable = new ShapeDrawable();
        cardDrawable.setShape(ShapeDrawable.RECTANGLE);
        cardDrawable.setCornerRadius(card.dp(14));
        cardDrawable.setColor(COLOR_CARD_BG);
        cardDrawable.setStroke(card.dp(1.5f), COLOR_CARD_BORDER);
        card.setBackground(cardDrawable);
        card.setPadding(card.dp(16), card.dp(14), card.dp(16), card.dp(14));

        // Prevent click events on card from bubbling to backdrop
        card.setOnClickListener(v -> {});

        // ── 1. Header Bar ──
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout titleBlock = new LinearLayout(context);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        titleBlock.setLayoutParams(titleLp);

        TextView titleText = new TextView(context);
        titleText.setText("✦ Wandscape 道路制作工坊");
        titleText.setTextSize(16);
        titleText.setTextColor(COLOR_TEXT_MAIN);
        titleBlock.addView(titleText);

        TextView subtitleText = new TextView(context);
        subtitleText.setText("ModernUI 纯 Java UI 架构测试 (NeoForge 1.21.1)");
        subtitleText.setTextSize(11);
        subtitleText.setTextColor(COLOR_TEXT_MUTED);
        titleBlock.addView(subtitleText);

        header.addView(titleBlock);

        // Close Button ('✕')
        Button closeBtn = new Button(context);
        closeBtn.setText("✕");
        closeBtn.setTextSize(14);
        closeBtn.setTextColor(COLOR_DANGER);
        closeBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams closeBtnLp = new LinearLayout.LayoutParams(closeBtn.dp(32), closeBtn.dp(32));
        closeBtn.setLayoutParams(closeBtnLp);

        ShapeDrawable closeBtnBg = new ShapeDrawable();
        closeBtnBg.setShape(ShapeDrawable.RECTANGLE);
        closeBtnBg.setCornerRadius(closeBtn.dp(8));
        closeBtnBg.setColor(COLOR_BTN_BG);
        closeBtn.setBackground(closeBtnBg);
        closeBtn.setOnClickListener(v -> closeScreen());
        header.addView(closeBtn);

        card.addView(header);

        // ── 2. Mode Selector Bar (Tabs) ──
        LinearLayout tabBar = new LinearLayout(context);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        tabLp.setMargins(0, card.dp(10), 0, card.dp(10));
        tabBar.setLayoutParams(tabLp);

        String[] modes = {"曲线编辑", "区域填充", "路径替换", "模板工具"};
        TextView activeModeBadge = new TextView(context);

        for (String mode : modes) {
            Button modeBtn = new Button(context);
            modeBtn.setText(mode);
            modeBtn.setTextSize(12);
            modeBtn.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, modeBtn.dp(30), 1.0f);
            btnLp.setMargins(modeBtn.dp(2), 0, modeBtn.dp(2), 0);
            modeBtn.setLayoutParams(btnLp);

            ShapeDrawable btnBg = new ShapeDrawable();
            btnBg.setShape(ShapeDrawable.RECTANGLE);
            btnBg.setCornerRadius(modeBtn.dp(6));
            boolean isSelected = mode.equals("曲线编辑");
            btnBg.setColor(isSelected ? COLOR_ACCENT_BLUE : COLOR_BTN_BG);
            modeBtn.setBackground(btnBg);
            modeBtn.setTextColor(isSelected ? 0xFF11111B : COLOR_TEXT_MAIN);

            modeBtn.setOnClickListener(v -> {
                activeTab = mode;
                activeModeBadge.setText("当前模式: " + mode);
                Toast.makeText(context, "切换到模式: " + mode, Toast.LENGTH_SHORT).show();
            });

            tabBar.addView(modeBtn);
        }
        card.addView(tabBar);

        // ── 3. Scrollable Content Area ──
        ScrollView scrollView = new ScrollView(context);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
        );
        scrollView.setLayoutParams(scrollLp);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // ── Section A: 基础交互控件 ──
        content.addView(createSectionHeader(context, "▌ 基础交互组件 (Widget & Events)"));

        LinearLayout sectionA = createSectionContainer(context);

        activeModeBadge.setText("当前模式: 曲线编辑");
        activeModeBadge.setTextSize(12);
        activeModeBadge.setTextColor(COLOR_ACCENT_GOLD);
        sectionA.addView(activeModeBadge);

        // Counter Button
        Button counterBtn = new Button(context);
        counterBtn.setText("点击测试计数: 0 次");
        counterBtn.setTextSize(13);
        counterBtn.setTextColor(COLOR_TEXT_MAIN);
        LinearLayout.LayoutParams counterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                counterBtn.dp(36)
        );
        counterLp.setMargins(0, counterBtn.dp(8), 0, counterBtn.dp(8));
        counterBtn.setLayoutParams(counterLp);

        ShapeDrawable counterBg = new ShapeDrawable();
        counterBg.setShape(ShapeDrawable.RECTANGLE);
        counterBg.setCornerRadius(counterBtn.dp(8));
        counterBg.setColor(COLOR_BTN_BG);
        counterBtn.setBackground(counterBg);

        counterBtn.setOnClickListener(v -> {
            clickCount++;
            counterBtn.setText("点击测试计数: " + clickCount + " 次 (ModernUI)");
            ShapeDrawable bg = (ShapeDrawable) counterBtn.getBackground();
            bg.setColor(clickCount % 2 == 0 ? COLOR_BTN_BG : COLOR_BTN_HOVER);
        });
        sectionA.addView(counterBtn);

        // Text Input + Live Mirror
        TextView inputLabel = new TextView(context);
        inputLabel.setText("道路名称输入:");
        inputLabel.setTextSize(12);
        inputLabel.setTextColor(COLOR_TEXT_MUTED);
        sectionA.addView(inputLabel);

        EditText editField = new EditText(context);
        editField.setHint("输入测试道路名称...");
        editField.setHintTextColor(COLOR_TEXT_DIM);
        editField.setTextColor(COLOR_TEXT_MAIN);
        editField.setTextSize(13);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                editField.dp(36)
        );
        editLp.setMargins(0, editField.dp(4), 0, editField.dp(4));
        editField.setLayoutParams(editLp);
        editField.setPadding(editField.dp(10), editField.dp(6), editField.dp(10), editField.dp(6));

        ShapeDrawable editBg = new ShapeDrawable();
        editBg.setShape(ShapeDrawable.RECTANGLE);
        editBg.setCornerRadius(editField.dp(6));
        editBg.setColor(0xAA11111B);
        editBg.setStroke(editField.dp(1), COLOR_TEXT_DIM);
        editField.setBackground(editBg);

        TextView liveMirror = new TextView(context);
        liveMirror.setText("实时预览: (未输入)");
        liveMirror.setTextSize(11);
        liveMirror.setTextColor(COLOR_TEXT_MUTED);

        editField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s == null || s.length() == 0) {
                    liveMirror.setText("实时预览: (未输入)");
                } else {
                    liveMirror.setText("实时预览: \"" + s + "\"");
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        sectionA.addView(editField);
        sectionA.addView(liveMirror);
        content.addView(sectionA);

        // ── Section B: 参数滑块调节 ──
        content.addView(createSectionHeader(context, "▌ 参数滑块 (Sliders & Range)"));

        LinearLayout sectionB = createSectionContainer(context);

        // Slider 1: Road Width
        TextView widthLabel = new TextView(context);
        widthLabel.setText("道路宽度: " + roadWidth + " 格");
        widthLabel.setTextSize(12);
        widthLabel.setTextColor(COLOR_TEXT_MAIN);
        sectionB.addView(widthLabel);

        SeekBar widthSlider = new SeekBar(context);
        widthSlider.setMax(20);
        widthSlider.setProgress(roadWidth);
        widthSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                roadWidth = Math.max(1, progress);
                widthLabel.setText("道路宽度: " + roadWidth + " 格");
            }
        });
        sectionB.addView(widthSlider);

        // Slider 2: Step Distance
        TextView stepLabel = new TextView(context);
        stepLabel.setText("采样步长: " + stepDistance + ".0 格");
        stepLabel.setTextSize(12);
        stepLabel.setTextColor(COLOR_TEXT_MAIN);
        LinearLayout.LayoutParams stepLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        stepLabelLp.setMargins(0, widthLabel.dp(6), 0, 0);
        stepLabel.setLayoutParams(stepLabelLp);
        sectionB.addView(stepLabel);

        SeekBar stepSlider = new SeekBar(context);
        stepSlider.setMax(10);
        stepSlider.setProgress(stepDistance);
        stepSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                stepDistance = Math.max(1, progress);
                stepLabel.setText("采样步长: " + stepDistance + ".0 格");
            }
        });
        sectionB.addView(stepSlider);

        content.addView(sectionB);

        // ── Section C: 开关与操作按钮 ──
        content.addView(createSectionHeader(context, "▌ 开关与动作 (Switches & Actions)"));

        LinearLayout sectionC = createSectionContainer(context);

        // Switch row 1: Grid Snapping
        LinearLayout switchRow1 = new LinearLayout(context);
        switchRow1.setOrientation(LinearLayout.HORIZONTAL);
        switchRow1.setGravity(Gravity.CENTER_VERTICAL);
        switchRow1.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                switchRow1.dp(32)
        ));

        TextView switchLabel1 = new TextView(context);
        switchLabel1.setText("启用网格吸附对齐 (Grid Snapping)");
        switchLabel1.setTextSize(12);
        switchLabel1.setTextColor(COLOR_TEXT_MAIN);
        switchLabel1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        switchRow1.addView(switchLabel1);

        SwitchButton switchBtn1 = new SwitchButton(context);
        switchBtn1.setChecked(true);
        switchBtn1.setCheckedColor(COLOR_ACCENT_BLUE);
        switchBtn1.setUncheckedColor(COLOR_BTN_BG);
        switchBtn1.setOnCheckedChangeListener((v, isChecked) -> {
            Toast.makeText(context, "网格吸附: " + (isChecked ? "已启用" : "已停用"), Toast.LENGTH_SHORT).show();
        });
        switchRow1.addView(switchBtn1);
        sectionC.addView(switchRow1);

        // Action Buttons Row
        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        actionRowLp.setMargins(0, actionRow.dp(8), 0, 0);
        actionRow.setLayoutParams(actionRowLp);

        Button toastBtn = new Button(context);
        toastBtn.setText("🔔 测试 Toast");
        toastBtn.setTextSize(12);
        toastBtn.setTextColor(COLOR_TEXT_MAIN);
        LinearLayout.LayoutParams toastLp = new LinearLayout.LayoutParams(0, toastBtn.dp(34), 1.0f);
        toastLp.setMargins(0, 0, toastBtn.dp(4), 0);
        toastBtn.setLayoutParams(toastLp);

        ShapeDrawable toastBg = new ShapeDrawable();
        toastBg.setShape(ShapeDrawable.RECTANGLE);
        toastBg.setCornerRadius(toastBtn.dp(6));
        toastBg.setColor(COLOR_BTN_BG);
        toastBtn.setBackground(toastBg);
        toastBtn.setOnClickListener(v -> {
            Toast.makeText(context, "✦ Modern UI 纯 Java 渲染管线响应正常！", Toast.LENGTH_SHORT).show();
        });
        actionRow.addView(toastBtn);

        Button confirmBtn = new Button(context);
        confirmBtn.setText("✓ 确认并关闭");
        confirmBtn.setTextSize(12);
        confirmBtn.setTextColor(0xFF11111B);
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(0, confirmBtn.dp(34), 1.0f);
        confirmLp.setMargins(confirmBtn.dp(4), 0, 0, 0);
        confirmBtn.setLayoutParams(confirmLp);

        ShapeDrawable confirmBg = new ShapeDrawable();
        confirmBg.setShape(ShapeDrawable.RECTANGLE);
        confirmBg.setCornerRadius(confirmBtn.dp(6));
        confirmBg.setColor(COLOR_ACCENT_GREEN);
        confirmBtn.setBackground(confirmBg);
        confirmBtn.setOnClickListener(v -> closeScreen());
        actionRow.addView(confirmBtn);

        sectionC.addView(actionRow);
        content.addView(sectionC);

        scrollView.addView(content);
        card.addView(scrollView);

        // ── 4. Footer Status Bar ──
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        footerLp.setMargins(0, footer.dp(8), 0, 0);
        footer.setLayoutParams(footerLp);

        TextView footerText = new TextView(context);
        footerText.setText("Modern UI 3.12.0 · 纯 Java UI 架构运行正常");
        footerText.setTextSize(10);
        footerText.setTextColor(COLOR_TEXT_DIM);
        footer.addView(footerText);

        card.addView(footer);

        root.addView(card);
        return root;
    }

    private TextView createSectionHeader(Context context, String title) {
        TextView header = new TextView(context);
        header.setText(title);
        header.setTextSize(13);
        header.setTextColor(COLOR_ACCENT_GOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, header.dp(8), 0, header.dp(4));
        header.setLayoutParams(lp);
        return header;
    }

    private LinearLayout createSectionContainer(Context context) {
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 0, section.dp(6));
        section.setLayoutParams(lp);

        ShapeDrawable bg = new ShapeDrawable();
        bg.setShape(ShapeDrawable.RECTANGLE);
        bg.setCornerRadius(section.dp(8));
        bg.setColor(COLOR_SECTION_BG);
        section.setBackground(bg);
        section.setPadding(section.dp(10), section.dp(10), section.dp(10), section.dp(10));
        return section;
    }

    private void closeScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(null);
        }
    }

    /**
     * Helper to open the ModernUI Road Studio in Minecraft client.
     */
    public static void open() {
        RoadStudioModernUI.open();
    }
}
