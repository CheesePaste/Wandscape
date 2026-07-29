package com.wsteam.wandscape.engine.colony;

import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.shared.event.ColonyLevelUpEvent;
import com.wsteam.wandscape.shared.log.Log;

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
 * <p>Level-up formula: expToNext(level) = (level + 1) × 1000
 * <br>1→2: 2000, 2→3: 3000, 3→4: 4000, etc.
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

    /** Set the display name for a colony. */
    public void setColonyName(UUID colonyId, String name) {
        if (name != null && !name.isEmpty()) {
            data.setName(colonyId, name);
        }
    }

    /** Calculate experience needed to reach the next level from the given level. */
    public static int expToNext(int currentLevel) {
        return (currentLevel + 1) * 1000;
    }

    /** Calculate experience needed for the colony's next level. */
    public int expToNextLevel(UUID colonyId) {
        return expToNext(getLevel(colonyId));
    }

    /**
     * Return the experience a tourist of the given level contributes
     * when departing with 100% satisfaction.
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
        } else {
            data.setExperience(colonyId, total);
        }
        return true;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
