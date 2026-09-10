package com.wsteam.wandscape.foundation.ui.settings;

import com.wsteam.wandscape.foundation.ui.I18n;

public enum SettingTab {
    VISUAL("gui.wandscape.settings.tab.visual", "视效控制"),
    COLONY("gui.wandscape.settings.tab.colony", "城镇经营"),
    TOURIST("gui.wandscape.settings.tab.tourist", "游客生态"),
    RULES("gui.wandscape.settings.tab.rules", "规则防护");

    private final String i18nKey;
    private final String fallbackName;

    SettingTab(String i18nKey, String fallbackName) {
        this.i18nKey = i18nKey;
        this.fallbackName = fallbackName;
    }

    public String getI18nKey() {
        return i18nKey;
    }

    public String getDisplayName() {
        return I18n.name(i18nKey, fallbackName).getString();
    }
}
