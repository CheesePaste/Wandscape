package com.wsteam.wandscape.engine.colony;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.engine.service.ParticleService;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.shared.event.ColonyLevelUpEvent;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Business logic for colony leveling and experience.
 *
 * <p>Experience calculation:
 * <ul>
 *   <li>tourist level &lt; colony level → half of {@link Config#COLONY_EXP_EQUAL_LEVEL}
 *       （不再归零：缓解"停留期间小镇升级导致满条游客经验全流失"的自限效应）</li>
 *   <li>tourist level == colony level → {@link Config#COLONY_EXP_EQUAL_LEVEL}</li>
 *   <li>tourist level &gt; colony level → {@link Config#COLONY_EXP_ABOVE_LEVEL}</li>
 * </ul>
 *
 * <p>Level-up formula: expToNext(level) = 300×(level+1) + 55×(level+1)² —— 二次曲线,
 * 前期便宜后期贵 → 小镇等级前快后慢（标定：5级≈5天、10级≈12天、15级≈22天、20级≈34天、30级满≈68天）。
 * 等级上限见 {@link Config#COLONY_MAX_LEVEL}。
 */
public final class ColonyLevelManager {
    private static final String TAG = "ColonyLevelManager";

    private final ColonyLevelData data;

    @Nullable
    private Consumer<ColonyLevelUpEvent> levelUpCallback;

    public ColonyLevelManager(ColonyLevelData data) {
        this.data = data;
    }

    public void setLevelUpCallback(@Nullable Consumer<ColonyLevelUpEvent> callback) {
        this.levelUpCallback = callback;
    }

    /** Get the level for a colony (default 1). */
    public int getLevel(UUID colonyId) {
        return data.getLevel(colonyId);
    }

    /** Get the experience for a colony (default 0). */
    public int getExperience(UUID colonyId) {
        return data.getExperience(colonyId);
    }

    /** Get the display name for a colony. */
    public String getColonyName(UUID colonyId) {
        return data.getName(colonyId);
    }

    /** 直接设置小镇等级（调试/测试用），经验清零。上限 {@link Config#COLONY_MAX_LEVEL}。 */
    public void setLevel(UUID colonyId, int level) {
        data.setLevel(colonyId, Math.min(Config.COLONY_MAX_LEVEL.get(), Math.max(1, level)));
        data.setExperience(colonyId, 0);
    }

    /** Set the display name for a colony. */
    public void setColonyName(UUID colonyId, String name) {
        if (name != null && !name.isEmpty()) {
            data.setName(colonyId, name);
        }
    }

    /** Calculate experience needed to reach the next level from the given level. */
    public static int expToNext(int currentLevel) {
        // 二次曲线: 前期便宜后期贵（A=300, B=25）。B 从 55 降到 25 加速后期升级
        //（外部 sim 实测: 5级≈3天 10级≈9天 15级≈18天 20级≈32天 30级满≈76天）
        int m = currentLevel + 1;
        return 300 * m + 25 * m * m;
    }

    /** Calculate experience needed for the colony's next level. */
    public int expToNextLevel(UUID colonyId) {
        return expToNext(getLevel(colonyId));
    }

    /**
     * Return the experience a tourist of the given level contributes
     * when departing with all three bars full.
     *
     * @param colonyLevel the colony's current level
     * @param touristLevel the tourist's level
     * @return experience contributed (half of equal-level exp if tourist is below colony level)
     */
    public static int computeExpContribution(int colonyLevel, int touristLevel) {
        if (touristLevel == colonyLevel) return Config.COLONY_EXP_EQUAL_LEVEL.get();
        if (touristLevel > colonyLevel) return Config.COLONY_EXP_ABOVE_LEVEL.get();
        // 低于小镇等级: 给一半（不再归零）——游客停留期间小镇常升级 1-2 级，
        // 若归零则大部分满条游客经验全流失（自限效应），前期升级过慢。
        return Config.COLONY_EXP_EQUAL_LEVEL.get() / 2;
    }

    /** Add experience to a colony and auto-level-up if possible.
     * Returns the amount of experience actually added (may be 0).
     *
     * @param colonyId target colony
     * @param amount experience to add (must be non-negative)
     * @return true if the colony gained experience (not necessarily leveled up)
     */
    public boolean addExperience(UUID colonyId, int amount) {
        if (amount <= 0) return false;

        int level = getLevel(colonyId);
        int maxLevel = Config.COLONY_MAX_LEVEL.get();
        if (level >= maxLevel) {
            Log.info(TAG, "[Colony] Colony {} already at max level Lv.{} — exp ignored",
                    shortId(colonyId), level);
            return false;
        }

        int exp = getExperience(colonyId);
        int required = expToNext(level);
        int total = exp + amount;

        Log.info(TAG, "[Colony] +{} exp to colony {} (Lv.{}: {}/{})",
                amount, shortId(colonyId), level, exp, required);

        // Check level-up
        if (total >= required) {
            int newLevel = level + 1;
            int overflow = total - required;
            data.setLevel(colonyId, newLevel);
            data.setExperience(colonyId, overflow);
            Log.info(TAG, "[Colony] ⬆ Colony {} leveled up: Lv.{} → Lv.{} (overflow={})",
                    shortId(colonyId), level, newLevel, overflow);
            if (levelUpCallback != null) {
                levelUpCallback.accept(new ColonyLevelUpEvent(colonyId, level, newLevel, overflow));
            }
            fireLevelUpCelebration(colonyId);
        } else {
            data.setExperience(colonyId, total);
        }
        return true;
    }

    /** 升级庆祝：在小镇市政厅位置放烟花。粒子纯装饰，API 未就绪时静默跳过。 */
    private static void fireLevelUpCelebration(UUID colonyId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        try {
            var api = WandscapeApis.getBuildingApi();
            for (var bd : api.getColonyBuildings(colonyId)) {
                if ("government".equals(bd.getCategory())) {
                    var bounds = bd.getBounds();
                    if (bounds != null) {
                        ParticleService.celebrateAt(server.overworld(),
                                ParticleService.boundsCenterAbove(bounds, 2), 5);
                    } else {
                        ParticleService.celebrateAt(server.overworld(), bd.getPosition().getCenter(), 5);
                    }
                    SoundService.playAt(server.overworld(), bd.getPosition(),
                            WandscapeSounds.COLONY_LEVEL_UP, SoundSource.NEUTRAL, 0.8f, 1.0f);
                    return;
                }
            }
        } catch (IllegalStateException e) {
        }
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
