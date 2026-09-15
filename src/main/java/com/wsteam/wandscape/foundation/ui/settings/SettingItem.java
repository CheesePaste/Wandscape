package com.wsteam.wandscape.foundation.ui.settings;

import com.wsteam.wandscape.ClientConfig;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.colony.network.ColonySettingUpdatePacket;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;
import com.wsteam.wandscape.foundation.ui.settings.network.ConfigUpdatePacket;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Represents a single configurable setting item in the Wandscape Settings Center.
 *
 * <p>卡片上只显示标题与默认值：介绍文案与取值区间都不上屏（横排一行的宽度本来就放不下，
 * 截断后只剩半句废话）。取值区间改为只从 Config 的 {@code defineInRange} 读一份，
 * 用于加减夹取，不在面板里另写一套。要说明一项是干什么的，写在 Config 的 comment 里。
 *
 * <p>Cards show only the title and the default value: descriptions and value ranges are not rendered
 * (a single horizontal line cannot fit them, and truncation leaves half a sentence). The range is read
 * once from {@code defineInRange} in Config and used for clamping only. Explain a setting in its Config comment.
 */
public interface SettingItem {

    enum Type { BOOLEAN, DOUBLE_STEP, INT_STEP, OPTIONS }

    String key();
    String title();
    SettingTab tab();
    Type type();
    boolean isClientOnly();
    boolean isHotReloadable();

    String formatValue();
    /** 卡片副行：形如 {@code 默认: 50,000 件}。 */
    String defaultHint();
    boolean isDefault();
    void resetToDefault();

    /** 本项当前值的规范化字符串，与 {@link #applyFromString} 构成往返，用于把服务端权威值同步给两端。 */
    String rawValue();

    /** 把字符串写进本地 config。不发包、不落盘、不触发 {@link #onModified}，因此同步回包不会打成死循环。 */
    boolean applyFromString(String raw);

    /** 值成功写入本地 config 之后的副作用钩子，两端都会跑。默认无。 */
    default void onApplied() {}

    /**
     * 本项现在能不能改。**客户端专属项只影响本机、从不发包，因此不受管理员门控**；
     * 通用配置以服务端为准，非 OP 改了也会被拒，索性先在本地拦下。
     * **本镇设置（{@link #isColonyScoped()}）人人可改**，只要求面板当前绑定了一个小镇。
     */
    default boolean canModify() {
        if (isColonyScoped()) {
            return WandscapePanelState.getColonyId() != null;
        }
        return isClientOnly() || SettingsOverlay.canModifySettings();
    }

    /**
     * 本项作用于「玩家自己的小镇」（殖民地存档里的每镇数据），而非全局 config。
     * 这类项走 {@link ColonySettingUpdatePacket}：任何玩家都能改，但服务端只认他自己的小镇。
     */
    default boolean isColonyScoped() {
        return false;
    }

    default void onModified(String stringValue) {
        if (!canModify()) {
            return;
        }
        if (isColonyScoped()) {
            // 本镇设置由服务端落盘：值已由取值器乐观写进客户端缓存（点完即刻反馈），
            // 服务端写入后回推殖民地快照做权威覆盖——被拒时同一路径把值改回来。
            try {
                PacketDistributor.sendToServer(new ColonySettingUpdatePacket(key(), stringValue));
            } catch (Throwable t) {
                Log.warn("SettingItem", "Failed to send colony setting {}: {}", key(), t.getMessage());
            }
            return;
        }
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
        private final SettingTab tab;
        private final boolean clientOnly;
        private final boolean hotReloadable;
        private final java.util.function.Supplier<Boolean> getter;
        private final java.util.function.Consumer<Boolean> setter;
        private final boolean defaultValue;
        private final boolean colonyScoped;

        public BooleanSetting(String key, String title, SettingTab tab,
                              boolean clientOnly, boolean hotReloadable, ModConfigSpec.BooleanValue configValue) {
            this(key, title, tab, clientOnly, hotReloadable,
                    configValue::get, configValue::set, configValue.getDefault(), false);
        }

        public BooleanSetting(String key, String title, SettingTab tab,
                              boolean clientOnly, boolean hotReloadable,
                              java.util.function.Supplier<Boolean> getter,
                              java.util.function.Consumer<Boolean> setter,
                              boolean defaultValue) {
            this(key, title, tab, clientOnly, hotReloadable, getter, setter, defaultValue, false);
        }

        private BooleanSetting(String key, String title, SettingTab tab,
                               boolean clientOnly, boolean hotReloadable,
                               Supplier<Boolean> getter, Consumer<Boolean> setter,
                               boolean defaultValue, boolean colonyScoped) {
            this.key = key;
            this.title = title;
            this.tab = tab;
            this.clientOnly = clientOnly;
            this.hotReloadable = hotReloadable;
            this.getter = getter;
            this.setter = setter;
            this.defaultValue = defaultValue;
            this.colonyScoped = colonyScoped;
        }

        /** 本镇设置项：值读写客户端殖民地缓存，改动发给服务端（人人可改，但只改自己的小镇）。 */
        public static BooleanSetting colony(String key, String title, SettingTab tab,
                                           Supplier<Boolean> getter, Consumer<Boolean> setter,
                                           boolean defaultValue) {
            return new BooleanSetting(key, title, tab, false, true, getter, setter, defaultValue, true);
        }

        @Override public String key() { return key; }
        @Override public String title() { return title; }
        @Override public SettingTab tab() { return tab; }
        @Override public Type type() { return Type.BOOLEAN; }
        @Override public boolean isClientOnly() { return clientOnly; }
        @Override public boolean isHotReloadable() { return hotReloadable; }
        @Override public boolean isColonyScoped() { return colonyScoped; }

        public boolean get() { return getter.get(); }
        public void set(boolean value) {
            if (!canModify()) return;
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
            return get()
                    ? I18n.string("gui.wandscape.settings.value.on", "已开启")
                    : I18n.string("gui.wandscape.settings.value.off", "已关闭");
        }

        @Override public String defaultHint() {
            return I18n.string("gui.wandscape.settings.default_hint", "默认: %s",
                    defaultValue
                            ? I18n.string("gui.wandscape.settings.value.on_short", "开启")
                            : I18n.string("gui.wandscape.settings.value.off_short", "关闭"));
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
        private final SettingTab tab;
        private final boolean clientOnly;
        private final boolean hotReloadable;
        private final ModConfigSpec.DoubleValue configValue;
        private final double min;
        private final double max;
        private final double step;
        private final double largeStep;
        private final Function<Double, String> formatter;

        public DoubleSetting(String key, String title, SettingTab tab,
                             boolean clientOnly, boolean hotReloadable, ModConfigSpec.DoubleValue configValue,
                             double step, double largeStep,
                             Function<Double, String> formatter) {
            this.key = key;
            this.title = title;
            this.tab = tab;
            this.clientOnly = clientOnly;
            this.hotReloadable = hotReloadable;
            this.configValue = configValue;
            ModConfigSpec.Range<Double> range = SettingItem.declaredRange(configValue);
            this.min = range != null ? range.getMin() : -Double.MAX_VALUE;
            this.max = range != null ? range.getMax() : Double.MAX_VALUE;
            this.step = step;
            this.largeStep = largeStep;
            this.formatter = formatter;
        }

        @Override public String key() { return key; }
        @Override public String title() { return title; }
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
            if (!canModify()) return;
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

        @Override public String defaultHint() {
            return I18n.string("gui.wandscape.settings.default_hint", "默认: %s",
                    formatter.apply(configValue.getDefault()));
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
        private final SettingTab tab;
        private final boolean clientOnly;
        private final boolean hotReloadable;
        private final ModConfigSpec.IntValue configValue;
        private final int min;
        private final int max;
        private final int step;
        private final int largeStep;
        private final Function<Integer, String> formatter;

        public IntSetting(String key, String title, SettingTab tab,
                          boolean clientOnly, boolean hotReloadable, ModConfigSpec.IntValue configValue,
                          int step, int largeStep,
                          Function<Integer, String> formatter) {
            this.key = key;
            this.title = title;
            this.tab = tab;
            this.clientOnly = clientOnly;
            this.hotReloadable = hotReloadable;
            this.configValue = configValue;
            ModConfigSpec.Range<Integer> range = SettingItem.declaredRange(configValue);
            this.min = range != null ? range.getMin() : Integer.MIN_VALUE;
            this.max = range != null ? range.getMax() : Integer.MAX_VALUE;
            this.step = step;
            this.largeStep = largeStep;
            this.formatter = formatter;
        }

        @Override public String key() { return key; }
        @Override public String title() { return title; }
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
            if (!canModify()) return;
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

        @Override public String defaultHint() {
            return I18n.string("gui.wandscape.settings.default_hint", "默认: %s",
                    formatter.apply(configValue.getDefault()));
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
        private final SettingTab tab;
        private final boolean clientOnly;
        private final boolean hotReloadable;
        private final Supplier<String> getter;
        private final Consumer<String> setter;
        private final String defaultValue;
        private final List<String> options;
        private final List<String> optionLabels;
        private final boolean colonyScoped;

        public OptionsSetting(String key, String title, SettingTab tab,
                              boolean clientOnly, boolean hotReloadable, ModConfigSpec.ConfigValue<String> configValue,
                              List<String> options, List<String> optionLabels) {
            this(key, title, tab, clientOnly, hotReloadable,
                    configValue::get, configValue::set, configValue.getDefault(),
                    options, optionLabels, false);
        }

        private OptionsSetting(String key, String title, SettingTab tab,
                               boolean clientOnly, boolean hotReloadable,
                               Supplier<String> getter, Consumer<String> setter, String defaultValue,
                               List<String> options, List<String> optionLabels, boolean colonyScoped) {
            this.key = key;
            this.title = title;
            this.tab = tab;
            this.clientOnly = clientOnly;
            this.hotReloadable = hotReloadable;
            this.getter = getter;
            this.setter = setter;
            this.defaultValue = defaultValue;
            this.options = options;
            this.optionLabels = optionLabels;
            this.colonyScoped = colonyScoped;
        }

        /** 本镇设置项：值读写客户端殖民地缓存，改动发给服务端（人人可改，但只改自己的小镇）。 */
        public static OptionsSetting colony(String key, String title, SettingTab tab,
                                            Supplier<String> getter, Consumer<String> setter,
                                            String defaultValue,
                                            List<String> options, List<String> optionLabels) {
            return new OptionsSetting(key, title, tab, false, true, getter, setter, defaultValue,
                    options, optionLabels, true);
        }

        @Override public String key() { return key; }
        @Override public String title() { return title; }
        @Override public SettingTab tab() { return tab; }
        @Override public Type type() { return Type.OPTIONS; }
        @Override public boolean isClientOnly() { return clientOnly; }
        @Override public boolean isHotReloadable() { return hotReloadable; }
        @Override public boolean isColonyScoped() { return colonyScoped; }

        public String get() { return getter.get(); }

        public void set(String value) {
            if (!canModify()) return;
            setter.accept(value);
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
            setter.accept(raw);
            return true;
        }

        @Override public String formatValue() {
            int idx = options.indexOf(get());
            return (idx >= 0 && idx < optionLabels.size()) ? optionLabels.get(idx) : get();
        }

        @Override public String defaultHint() {
            int defIdx = options.indexOf(defaultValue);
            String defLabel = (defIdx >= 0 && defIdx < optionLabels.size()) ? optionLabels.get(defIdx) : defaultValue;
            return I18n.string("gui.wandscape.settings.default_hint", "默认: %s", defLabel);
        }

        @Override public boolean isDefault() {
            return defaultValue.equalsIgnoreCase(get());
        }

        @Override public void resetToDefault() {
            set(defaultValue);
        }
    }
}
