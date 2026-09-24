package com.wsteam.wandscape.foundation.ui.settings;

import com.wsteam.wandscape.foundation.ui.I18n;

public enum SettingTab {
    /** 本镇设置：随殖民地走、任何玩家都能改自己小镇的那几项，故排在最前。 */
    SETTLEMENT("gui.wandscape.settings.tab.settlement", "本镇"),
    VISUAL("gui.wandscape.settings.tab.visual", "视效"),
    COLONY("gui.wandscape.settings.tab.colony", "经营"),
    TOURIST("gui.wandscape.settings.tab.tourist", "游客"),
    RULES("gui.wandscape.settings.tab.rules", "规则"),
    PACKAGES("gui.wandscape.settings.tab.packages", "建筑包");

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
        return I18n.string(i18nKey, fallbackName);
    }
}
