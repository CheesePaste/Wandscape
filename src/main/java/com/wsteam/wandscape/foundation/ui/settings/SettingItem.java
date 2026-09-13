package com.wsteam.wandscape.foundation.ui.settings;

import com.wsteam.wandscape.ClientConfig;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.foundation.ui.settings.network.ConfigUpdatePacket;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Function;

/**
 * Represents a single configurable setting item in the Wandscape Settings Center.
 *
 * <p>取值上下限不在这里另写一套：{@link #rangeHint()} 与加减夹取都直接读 Config 里
 * {@code defineInRange} 声明的那份范围（见 {@link #declaredRange}），面板只额外决定步进大小。
 * 面板比 config 夹得更紧会让玩家改了 TOML 却在面板里被悄悄改回去。
 *
 * <p>Value bounds are not duplicated here: {@link #rangeHint()} and the stepper clamping both read the
 * range declared by {@code defineInRange} in Config (see {@link #declaredRange}); the panel only decides
 * step sizes. A tighter clamp in the panel silently reverts values players set in the TOML.
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

    /** 本项当前值的规范化字符串，与 {@link #applyFromString} 构成往返，用于把服务端权威值同步给两端。 */
    String rawValue();

    /** 把字符串写进本地 config。不发包、不落盘、不触发 {@link #onModified}，因此同步回包不会打成死循环。 */
    boolean applyFromString(String raw);

    /** 值成功写入本地 config 之后的副作用钩子，两端都会跑。默认无。 */
    default void onApplied() {}

    default void onModified(String stringValue) {
        if (isClientOnly()) {
            // 客户端配置只在本机生效，改完即落盘。
            if (ClientConfig.SPEC.isLoaded()) {
                ClientConfig.SPEC.save();
            }
            return;
        }
        // 通用配置以服务端为准：这里只把请求发出去，落盘与最终值等服务端回包。
        // 若先本地 save，服务端拒绝（无权限 / 值非法）时本地文件已经被写脏，两端就此不一致。
        try {
            PacketDistributor.sendToServer(new ConfigUpdatePacket(key(), stringValue));
        } catch (Throwable t) {
            // 未连接服务端（主菜单等）没有可校验的一方，退化为本地落盘，至少不丢改动。
            if (Config.SPEC.isLoaded()) {
                Config.SPEC.save();
            }
        }
    }

    /** 读 Config 里 {@code defineInRange} 声明的范围；没声明范围（{@code define} / {@code defineList}）时返回 null。 */
    @Nullable
    static <V extends Comparable<? super V>> ModConfigSpec.Range<V> declaredRange(ModConfigSpec.ConfigValue<?> configValue) {
        try {
            ModConfigSpec.ValueSpec spec = configValue.getSpec();
            return spec == null ? null : spec.getRange();
        } catch (Throwable t) {
            return null;
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
        private final java.util.function.Supplier<Boolean> getter;
        private final java.util.function.Consumer<Boolean> setter;
        private final boolean defaultValue;

        public BooleanSetting(String key, String title, String description, SettingTab tab,
                              boolean clientOnly, boolean hotReloadable, ModConfigSpec.BooleanValue configValue) {
            this(key, title, description, tab, clientOnly, hotReloadable,
                    configValue::get, configValue::set, configValue.getDefault());
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

        @Override public String rawValue() { return String.valueOf(get()); }

        @Override public boolean applyFromString(String raw) {
            setter.accept(Boolean.parseBoolean(raw));
            return true;
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
        private final boolean rangeDeclared;
        private final double min;
        private final double max;
        private final double step;
        private final double largeStep;
        private final Function<Double, String> formatter;

        public DoubleSetting(String key, String title, String description, SettingTab tab,
                             boolean clientOnly, boolean hotReloadable, ModConfigSpec.DoubleValue configValue,
                             double step, double largeStep,
                             Function<Double, String> formatter) {
            this.key = key;
            this.title = title;
            this.description = description;
            this.tab = tab;
            this.clientOnly = clientOnly;
            this.hotReloadable = hotReloadable;
            this.configValue = configValue;
            ModConfigSpec.Range<Double> range = SettingItem.declaredRange(configValue);
            this.rangeDeclared = range != null;
            this.min = range != null ? range.getMin() : -Double.MAX_VALUE;
            this.max = range != null ? range.getMax() : Double.MAX_VALUE;
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

        /** 夹取到 config 声明的范围后写入。返回真正落下去的值，供回包与提示用同一份。 */
        private double write(double value) {
            double clamped = Math.max(min, Math.min(max, Math.round(value * 1000.0) / 1000.0));
            configValue.set(clamped);
            return clamped;
        }

        public void set(double value) {
            onModified(String.valueOf(write(value)));
        }

        public void adjust(boolean increase, boolean large) {
            double delta = (large ? largeStep : step) * (increase ? 1.0 : -1.0);
            set(get() + delta);
        }

        @Override public String rawValue() { return String.valueOf(get()); }

        @Override public boolean applyFromString(String raw) {
            write(Double.parseDouble(raw));
            return true;
        }

        @Override public String formatValue() {
            return formatter.apply(get());
        }

        @Override public String rangeHint() {
            String base = "默认: " + formatter.apply(configValue.getDefault());
            // 没声明范围时不硬凑一个 ±MAX_VALUE 的假区间
            return rangeDeclared ? base + "  |  范围: " + formatter.apply(min) + " ~ " + formatter.apply(max) : base;
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
        private final boolean rangeDeclared;
        private final int min;
        private final int max;
        private final int step;
        private final int largeStep;
        private final Function<Integer, String> formatter;

        public IntSetting(String key, String title, String description, SettingTab tab,
                          boolean clientOnly, boolean hotReloadable, ModConfigSpec.IntValue configValue,
                          int step, int largeStep,
                          Function<Integer, String> formatter) {
            this.key = key;
            this.title = title;
            this.description = description;
            this.tab = tab;
            this.clientOnly = clientOnly;
            this.hotReloadable = hotReloadable;
            this.configValue = configValue;
            ModConfigSpec.Range<Integer> range = SettingItem.declaredRange(configValue);
            this.rangeDeclared = range != null;
            this.min = range != null ? range.getMin() : Integer.MIN_VALUE;
            this.max = range != null ? range.getMax() : Integer.MAX_VALUE;
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

        /** 夹取到 config 声明的范围后写入。返回真正落下去的值，供回包与提示用同一份。 */
        private int write(int value) {
            int clamped = Math.max(min, Math.min(max, value));
            configValue.set(clamped);
            return clamped;
        }

        public void set(int value) {
            onModified(String.valueOf(write(value)));
        }

        public void adjust(boolean increase, boolean large) {
            int delta = (large ? largeStep : step) * (increase ? 1 : -1);
            set(get() + delta);
        }

        @Override public String rawValue() { return String.valueOf(get()); }

        @Override public boolean applyFromString(String raw) {
            write(Integer.parseInt(raw));
            return true;
        }

        @Override public String formatValue() {
            return formatter.apply(get());
        }

        @Override public String rangeHint() {
            String base = "默认: " + formatter.apply(configValue.getDefault());
            // 没声明范围时不硬凑一个 MIN/MAX_VALUE 的假区间
            return rangeDeclared ? base + "  |  范围: " + formatter.apply(min) + " ~ " + formatter.apply(max) : base;
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

        @Override public String rawValue() { return get(); }

        @Override public boolean applyFromString(String raw) {
            configValue.set(raw);
            return true;
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
