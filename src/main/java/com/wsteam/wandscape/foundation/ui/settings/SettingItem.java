package com.wsteam.wandscape.foundation.ui.settings;

import com.wsteam.wandscape.ClientConfig;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.foundation.ui.settings.network.ConfigUpdatePacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Function;

/**
 * Represents a single configurable setting item in the Wandscape Settings Center.
 */
public interface SettingItem {

    enum Type { BOOLEAN, DOUBLE_STEP, INT_STEP, OPTIONS }

    String key();
    String title();
    String description();
    SettingTab tab();
    Type type();
    boolean isClientOnly();
    boolean isHotReloadable();

    String formatValue();
    String rangeHint();
    boolean isDefault();
    void resetToDefault();

    default void onModified(String stringValue) {
        if (isClientOnly()) {
            if (ClientConfig.SPEC.isLoaded()) {
                ClientConfig.SPEC.save();
            }
        } else {
            if (Config.SPEC.isLoaded()) {
                Config.SPEC.save();
            }
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getConnection() != null) {
                    PacketDistributor.sendToServer(new ConfigUpdatePacket(key(), stringValue));
                }
            } catch (Throwable ignored) {}
        }
    }

    // ── Concrete implementations ──

    class BooleanSetting implements SettingItem {
        private final String key;
        private final String title;
        private final String description;
        private final SettingTab tab;
        private final boolean clientOnly;
        private final boolean hotReloadable;
        private final ModConfigSpec.BooleanValue configValue;
        private final java.util.function.Supplier<Boolean> getter;
        private final java.util.function.Consumer<Boolean> setter;
        private final boolean defaultValue;

        public BooleanSetting(String key, String title, String description, SettingTab tab,
                              boolean clientOnly, boolean hotReloadable, ModConfigSpec.BooleanValue configValue) {
            this.key = key;
            this.title = title;
            this.description = description;
            this.tab = tab;
            this.clientOnly = clientOnly;
            this.hotReloadable = hotReloadable;
            this.configValue = configValue;
            this.getter = configValue::get;
            this.setter = configValue::set;
            this.defaultValue = configValue.getDefault();
        }

        public BooleanSetting(String key, String title, String description, SettingTab tab,
                              boolean clientOnly, boolean hotReloadable,
                              java.util.function.Supplier<Boolean> getter,
                              java.util.function.Consumer<Boolean> setter,
                              boolean defaultValue) {
            this.key = key;
            this.title = title;
            this.description = description;
            this.tab = tab;
            this.clientOnly = clientOnly;
            this.hotReloadable = hotReloadable;
            this.configValue = null;
            this.getter = getter;
            this.setter = setter;
            this.defaultValue = defaultValue;
        }

        @Override public String key() { return key; }
        @Override public String title() { return title; }
        @Override public String description() { return description; }
        @Override public SettingTab tab() { return tab; }
        @Override public Type type() { return Type.BOOLEAN; }
        @Override public boolean isClientOnly() { return clientOnly; }
        @Override public boolean isHotReloadable() { return hotReloadable; }

        public boolean get() { return getter.get(); }
        public void set(boolean value) {
            setter.accept(value);
            onModified(String.valueOf(value));
        }

        public void toggle() {
            set(!get());
        }

        @Override public String formatValue() {
            return get() ? "已开启" : "已关闭";
        }

        @Override public String rangeHint() {
            return "默认: " + (defaultValue ? "开启" : "关闭");
        }

        @Override public boolean isDefault() {
            return get() == defaultValue;
        }

        @Override public void resetToDefault() {
            set(defaultValue);
        }
    }

    class DoubleSetting implements SettingItem {
        private final String key;
        private final String title;
        private final String description;
        private final SettingTab tab;
        private final boolean clientOnly;
        private final boolean hotReloadable;
        private final ModConfigSpec.DoubleValue configValue;
        private final double min;
        private final double max;
        private final double step;
        private final double largeStep;
        private final Function<Double, String> formatter;

        public DoubleSetting(String key, String title, String description, SettingTab tab,
                             boolean clientOnly, boolean hotReloadable, ModConfigSpec.DoubleValue configValue,
                             double min, double max, double step, double largeStep,
                             Function<Double, String> formatter) {
            this.key = key;
            this.title = title;
            this.description = description;
            this.tab = tab;
            this.clientOnly = clientOnly;
            this.hotReloadable = hotReloadable;
            this.configValue = configValue;
            this.min = min;
            this.max = max;
            this.step = step;
            this.largeStep = largeStep;
            this.formatter = formatter;
        }

        @Override public String key() { return key; }
        @Override public String title() { return title; }
        @Override public String description() { return description; }
        @Override public SettingTab tab() { return tab; }
        @Override public Type type() { return Type.DOUBLE_STEP; }
        @Override public boolean isClientOnly() { return clientOnly; }
        @Override public boolean isHotReloadable() { return hotReloadable; }

        public double get() { return configValue.get(); }
        public void set(double value) {
            double clamped = Math.max(min, Math.min(max, Math.round(value * 1000.0) / 1000.0));
            configValue.set(clamped);
            onModified(String.valueOf(clamped));
        }

        public void adjust(boolean increase, boolean large) {
            double delta = (large ? largeStep : step) * (increase ? 1.0 : -1.0);
            set(get() + delta);
        }

        @Override public String formatValue() {
            return formatter.apply(get());
        }

        @Override public String rangeHint() {
            return "默认: " + formatter.apply(configValue.getDefault())
                    + "  |  范围: " + formatter.apply(min) + " ~ " + formatter.apply(max);
        }

        @Override public boolean isDefault() {
            return Math.abs(get() - configValue.getDefault()) < 1e-4;
        }

        @Override public void resetToDefault() {
            set(configValue.getDefault());
        }
    }

    class IntSetting implements SettingItem {
        private final String key;
        private final String title;
        private final String description;
        private final SettingTab tab;
        private final boolean clientOnly;
        private final boolean hotReloadable;
        private final ModConfigSpec.IntValue configValue;
        private final int min;
        private final int max;
        private final int step;
        private final int largeStep;
        private final Function<Integer, String> formatter;

        public IntSetting(String key, String title, String description, SettingTab tab,
                          boolean clientOnly, boolean hotReloadable, ModConfigSpec.IntValue configValue,
                          int min, int max, int step, int largeStep,
                          Function<Integer, String> formatter) {
            this.key = key;
            this.title = title;
            this.description = description;
            this.tab = tab;
            this.clientOnly = clientOnly;
            this.hotReloadable = hotReloadable;
            this.configValue = configValue;
            this.min = min;
            this.max = max;
            this.step = step;
            this.largeStep = largeStep;
            this.formatter = formatter;
        }

        @Override public String key() { return key; }
        @Override public String title() { return title; }
        @Override public String description() { return description; }
        @Override public SettingTab tab() { return tab; }
        @Override public Type type() { return Type.INT_STEP; }
        @Override public boolean isClientOnly() { return clientOnly; }
        @Override public boolean isHotReloadable() { return hotReloadable; }

        public int get() { return configValue.get(); }
        public void set(int value) {
            int clamped = Math.max(min, Math.min(max, value));
            configValue.set(clamped);
            onModified(String.valueOf(clamped));
        }

        public void adjust(boolean increase, boolean large) {
            int delta = (large ? largeStep : step) * (increase ? 1 : -1);
            set(get() + delta);
        }

        @Override public String formatValue() {
            return formatter.apply(get());
        }

        @Override public String rangeHint() {
            return "默认: " + formatter.apply(configValue.getDefault())
                    + "  |  范围: " + formatter.apply(min) + " ~ " + formatter.apply(max);
        }

        @Override public boolean isDefault() {
            return get() == configValue.getDefault();
        }

        @Override public void resetToDefault() {
            set(configValue.getDefault());
        }
    }

    class OptionsSetting implements SettingItem {
        private final String key;
        private final String title;
        private final String description;
        private final SettingTab tab;
        private final boolean clientOnly;
        private final boolean hotReloadable;
        private final ModConfigSpec.ConfigValue<String> configValue;
        private final List<String> options;
        private final List<String> optionLabels;

        public OptionsSetting(String key, String title, String description, SettingTab tab,
                              boolean clientOnly, boolean hotReloadable, ModConfigSpec.ConfigValue<String> configValue,
                              List<String> options, List<String> optionLabels) {
            this.key = key;
            this.title = title;
            this.description = description;
            this.tab = tab;
            this.clientOnly = clientOnly;
            this.hotReloadable = hotReloadable;
            this.configValue = configValue;
            this.options = options;
            this.optionLabels = optionLabels;
        }

        @Override public String key() { return key; }
        @Override public String title() { return title; }
        @Override public String description() { return description; }
        @Override public SettingTab tab() { return tab; }
        @Override public Type type() { return Type.OPTIONS; }
        @Override public boolean isClientOnly() { return clientOnly; }
        @Override public boolean isHotReloadable() { return hotReloadable; }

        public String get() { return configValue.get(); }
        public void set(String value) {
            configValue.set(value);
            onModified(value);
        }

        public void cycle(boolean forward) {
            int idx = options.indexOf(get());
            if (idx < 0) idx = 0;
            int next = forward ? (idx + 1) % options.size() : (idx - 1 + options.size()) % options.size();
            set(options.get(next));
        }

        @Override public String formatValue() {
            int idx = options.indexOf(get());
            return (idx >= 0 && idx < optionLabels.size()) ? optionLabels.get(idx) : get();
        }

        @Override public String rangeHint() {
            int defIdx = options.indexOf(configValue.getDefault());
            String defLabel = (defIdx >= 0 && defIdx < optionLabels.size()) ? optionLabels.get(defIdx) : configValue.getDefault();
            return "默认: " + defLabel;
        }

        @Override public boolean isDefault() {
            return configValue.getDefault().equalsIgnoreCase(get());
        }

        @Override public void resetToDefault() {
            set(configValue.getDefault());
        }
    }
}
