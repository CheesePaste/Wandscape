package com.wsteam.wandscape.content.npc.data;

import java.util.List;
import java.util.Random;

/**
 * Rolls a mage recruit's base attributes with a random⁴ skew distribution,
 * scaled by a per-level bonus — the same profile used for tourist resumes
 * (TouristEntity). Centralizes the formula so tourism and tavern recruitment
 * stay in sync.
 */
public final class MageAttributeRoller {
    private MageAttributeRoller() {}

    /**
     * Roll a mage candidate at the given level. Each attribute draws an
     * independent random⁴ factor ∈ [0,1) — mostly low with occasional high
     * spikes (natural specialisation); the level adds a flat per-level bonus.
     */
    public static RecruitmentCandidate roll(int level, Random random) {
        int safeLevel = Math.max(1, level);
        int lvl = safeLevel - 1;
        float maxHp = (float) Math.round(20 + 20 * skew(random)) + lvl * 2f;        // 20–40 + 2/级
        float maxMana = (float) Math.round(150 + 100 * skew(random)) + lvl * 15f;   // 150–250 + 15/级
        float moveSpeed = 0.2f + random.nextFloat() * 0.2f;            // 0.2–0.4 + 0.02/级
        float spellPower = round2(0.5f + (float) skew(random) + lvl * 0.05f);        // 0.5–1.5 + 0.05/级
        float workSpeed = round2(0.5f + (float) skew(random) + lvl * 0.05f);
        float spellSpeed = round2(0.5f + (float) skew(random) + lvl * 0.05f);
        float armorValue = (float) Math.round(10 * skew(random));       // 0–10 + 0.5/级
        return new RecruitmentCandidate(safeLevel, maxHp, moveSpeed, spellPower,
                workSpeed, spellSpeed, armorValue, maxMana, List.of());
    }

    /** 偏斜随机因子：random*random ∈ [0,1)，多数偏向低值、偶发接近 1。 */
    private static double skew(Random random) {
        double r = random.nextDouble();
        return r * r;
    }

    /** 保留两位小数。 */
    private static float round2(float v) {
        return Math.round(v * 100f) / 100f;
    }
}
