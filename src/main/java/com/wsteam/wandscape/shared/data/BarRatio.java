package com.wsteam.wandscape.shared.data;

/**
 * 游客三条需求条填充率（0-100，floor(sat×100/need)）。共享 API/事件/统计走它。
 */
public record BarRatio(int comfort, int magic, int wonder) {
    public static final BarRatio ZERO = new BarRatio(0, 0, 0);

    public BarRatio {
        comfort = Math.clamp(comfort, 0, 100);
        magic   = Math.clamp(magic,   0, 100);
        wonder  = Math.clamp(wonder,  0, 100);
    }

    /** 由三条 sat/need 算填充率。need≤0 视为 0（防除零）。 */
    public static BarRatio of(int comfortSat, int comfortNeed, int magicSat, int magicNeed,
                              int wonderSat, int wonderNeed) {
        return new BarRatio(pct(comfortSat, comfortNeed), pct(magicSat, magicNeed), pct(wonderSat, wonderNeed));
    }

    private static int pct(int sat, int need) {
        return need <= 0 ? 0 : (int) Math.floor(sat * 100.0 / need);
    }

    /** min-ratio×100：三条最短板；100 ⟺ 满条（isFullySatisfied）。离场语调/闲逛情绪用。 */
    public int minPct() { return Math.min(Math.min(comfort, magic), wonder); }
}
