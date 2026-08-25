package com.wsteam.wandscape.production.client;

import com.wsteam.wandscape.production.internal.QuantityWindow;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.Slider;

import net.minecraft.network.chat.Component;

/**
 * A windowed quantity selector: a Slider spanning at most {@link QuantityWindow#PAGE_SIZE}
 * (64) values, flanked by -64/+64 buttons that page the window up/down by a full page.
 * The window is clamped to [1, totalMax], so paging stops at the real limit (a recipe
 * affordable for 100 units pages 1-64 then 65-100).
 */
public class QuantityStepper {

    private static final int BTN_W = 30;
    private static final int BTN_H = 18;
    private static final int BTN_DY = 4;
    private static final int SLIDER_W = 88;

    private final Slider slider;
    private final MedievalButton minusBtn;
    private final MedievalButton plusBtn;

    private int totalMax = 1;
    private int page = 0;

    /** @param x left edge (the -64 button's x); the slider and +64 button sit to its right. */
    public QuantityStepper(int x, int y) {
        this.slider = new Slider(x + BTN_W + 2, y, SLIDER_W, 1, 1, 1, v -> {});
        this.minusBtn = new MedievalButton(x, y + BTN_DY, BTN_W, BTN_H,
                Component.literal("-64"), this::prevPage);
        this.plusBtn = new MedievalButton(x + BTN_W + 2 + SLIDER_W + 2, y + BTN_DY, BTN_W, BTN_H,
                Component.literal("+64"), this::nextPage);
    }

    public Slider slider() {
        return slider;
    }

    public MedievalButton minusBtn() {
        return minusBtn;
    }

    public MedievalButton plusBtn() {
        return plusBtn;
    }

    /** @return the currently selected quantity (already clamped into the active window). */
    public int getValue() {
        return slider.getValue();
    }

    /**
     * Reset to the first page for a new total and snap the slider back to 1.
     * Call when a different item/recipe is selected or when new data arrives.
     */
    public void setTotalMax(int totalMax) {
        this.totalMax = Math.max(1, totalMax);
        this.page = 0;
        this.slider.setValue(1);
        applyWindow();
    }

    private void nextPage() {
        if (page >= QuantityWindow.maxPage(totalMax)) return;
        page++;
        applyWindow();
    }

    private void prevPage() {
        if (page <= 0) return;
        page--;
        applyWindow();
    }

    private void applyWindow() {
        QuantityWindow.Page w = QuantityWindow.page(totalMax, page);
        slider.setRange(w.min(), w.max());
    }
}
