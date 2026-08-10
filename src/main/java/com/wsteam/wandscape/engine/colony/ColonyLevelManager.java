package com.wsteam.wandscape.engine.colony;

import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

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

/**
 * Business logic for colony leveling and experience.
 *
 * <p>Experience calculation:
 * <ul>
 *   <li>tourist level &lt; colony level → 0 exp</li>
 *   <li>tourist level == colony level → {@link Config#COLONY_EXP_EQUAL_LEVEL}</li>
 *   <li>tourist level &gt; colony level → {@link Config#COLONY_EXP_ABOVE_LEVEL}</li>
 * </ul>
 *
 * <p>Level-up formula: expToNext(level) = level × 1000
 * <br>1→2: 1000, 2→3: 2000, 3→4: 3000, etc.
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

    /** 直接设置殖民地等级（调试/测试用），经验清零。 */
    public void setLevel(UUID colonyId, int level) {
        data.setLevel(colonyId, Math.max(1, level));
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
        return currentLevel * 1000;
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
     * @return experience contributed (0 if tourist is below colony level)
     */
    public static int computeExpContribution(int colonyLevel, int touristLevel) {
        if (touristLevel < colonyLevel) return 0;
        if (touristLevel == colonyLevel) return Config.COLONY_EXP_EQUAL_LEVEL.get();
        return Config.COLONY_EXP_ABOVE_LEVEL.get();
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

    /** 升级庆祝：在殖民地市政厅位置放烟花。粒子纯装饰，API 未就绪时静默跳过。 */
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
