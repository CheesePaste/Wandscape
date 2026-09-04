package com.wsteam.wandscape.content.colony;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.foundation.util.NameStyle;

import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.colony.ColonySavedData;
import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ColonyApiImpl implements ColonyApi {

    private static final String TAG = "ColonyApiImpl";
    private static final int MAX_COLONY_RANGE = 256;
    private static volatile ColonyApiImpl instance;

    /** Colony origin → colony UUID (spatial index). */
    private final Map<BlockPos, UUID> colonyOrigins = new ConcurrentHashMap<>();

    /** Colony UUID → origin position (reverse lookup). */
    private final Map<UUID, BlockPos> colonyToOrigin = new ConcurrentHashMap<>();

    /** Colony level/exp data source — injected by Wandscape.java during assembly. */
    @Nullable
    private ColonyLevelManager colonyLevelManager;

    private ColonyApiImpl() {}

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public UUID createColony(BlockPos origin, @Nullable UUID founder) {
        UUID existing = colonyOrigins.get(origin);
        if (existing != null) return existing;

        UUID colonyId = UUID.randomUUID();
        colonyOrigins.put(origin, colonyId);
        colonyToOrigin.put(colonyId, origin);

        // Persist to ColonySavedData synchronously — NeoForge's default
        // async IO worker may not flush before a crash or quick exit.
        ColonySavedData csd = getColonySavedData();
        if (csd != null) {
            csd.addColony(colonyId, origin, founder);
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                csd.saveNow(server.overworld(), server.overworld().registryAccess());
            }
        }

        Log.info(TAG, "[Colony] Created colony {} at origin {}",
                colonyId.toString().substring(0, 8), origin);
        return colonyId;
    }

    @Override
    @Nullable
    public UUID getFounder(UUID colonyId) {
        ColonySavedData csd = getColonySavedData();
        return csd != null ? csd.getFounder(colonyId) : null;
    }

    @Override
    @Nullable
    public UUID getColonyByFounder(UUID founder) {
        ColonySavedData csd = getColonySavedData();
        return csd != null ? csd.getColonyByFounder(founder) : null;
    }

    @Override
    public void deleteColony(UUID colonyId) {
        BlockPos origin = colonyToOrigin.remove(colonyId);
        if (origin != null) {
            colonyOrigins.remove(origin);

            // Remove from persistence
            ColonySavedData csd = getColonySavedData();
            if (csd != null) {
                csd.removeColony(colonyId);
            }

            Log.info(TAG, "[Colony] Deleted colony {} (origin {})",
                    colonyId.toString().substring(0, 8), origin);
            BuildingSavedData sd = getSavedData();
            if (sd != null) {
                for (BuildingData bd : sd.getAllBuildings()) {
                    if (colonyId.equals(bd.getColonyId())) {
                        setColonyId(bd, null);
                    }
                }
            }
        }
    }

    // ── Spatial lookup ────────────────────────────────────────────────────

    @Override
    @Nullable
    public UUID getColonyId(BlockPos pos) {
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (BlockPos origin : colonyOrigins.keySet()) {
            double d = Math.sqrt(pos.distSqr(origin));
            if (d < nearestDist && d <= MAX_COLONY_RANGE) {
                nearestDist = d;
                nearest = origin;
            }
        }
        return nearest != null ? colonyOrigins.get(nearest) : null;
    }

    @Override
    public boolean isColonyOrigin(BlockPos pos) {
        return colonyOrigins.containsKey(pos);
    }

    // ── Event hooks ───────────────────────────────────────────────────────

    @Nullable
    public UUID onBuildingIntact(BuildingData data) {
        // 归属跟放置者：建筑已有归属（放置/命名时确定）则保留，绝不被「空间最近原点」改写——
        // 否则近邻小镇各建各的也会被串到对方镇、消耗对方物资。
        UUID current = data.getColonyId();
        if (current != null) {
            return current;
        }
        if ("government".equals(data.getCategory())) {
            // NEVER auto-create colonies here — colony creation is explicit
            // (command or the town-hall naming panel). If a town hall is built
            // within an existing colony, link it; otherwise leave it unassigned:
            // the player can right-click it to open the naming panel, which
            // creates the colony and links this town hall.
            UUID existing = getColonyId(data.getPosition());
            if (existing != null) {
                colonyOrigins.put(data.getPosition(), existing);
                colonyToOrigin.put(existing, data.getPosition());
                setColonyId(data, existing);
                Log.info(TAG, "[Colony] Town hall at {} linked to colony {}",
                        data.getPosition(), existing.toString().substring(0, 8));
                return existing;
            }
            Log.info(TAG, "[Colony] Town hall at {} intact but no colony nearby — "
                    + "right-click it to name & create the colony.",
                    data.getPosition());
            return null;
        }
        UUID colonyId = getColonyId(data.getPosition());
        if (colonyId != null) {
            setColonyId(data, colonyId);
            Log.info(TAG, "[Colony] Assigned {} at {} to colony {}",
                    data.getBuildingTypeId(), data.getPosition(),
                    colonyId.toString().substring(0, 8));
        }
        return colonyId;
    }

    public void onBuildingDestroyed(BuildingData data) {
        // Colonies are permanent — destroying the town hall does NOT delete
        // the colony. The colony persists in ColonySavedData and will be
        // recovered on next restart as long as the data file exists.
    }

    public void assignColonyIfPossible(BuildingData data) {
        if (data.getColonyId() != null) return;
        UUID colonyId = getColonyId(data.getPosition());
        if (colonyId != null) {
            setColonyId(data, colonyId);
        }
    }

    /** 直接把建筑归属到指定小镇（放置者归属），与空间最近原点无关。 */
    public void assignToColony(BuildingData data, @Nullable UUID colonyId) {
        if (data == null || colonyId == null) return;
        setColonyId(data, colonyId);
    }

    @Override
    public Collection<UUID> getAllColonyIds() {
        return colonyToOrigin.keySet();
    }

    @Override
    public com.wsteam.wandscape.foundation.util.NameStyle getNamingStyle(UUID colonyId) {
        ColonySavedData csd = getColonySavedData();
        return csd != null ? csd.getNamingStyle(colonyId)
                : com.wsteam.wandscape.foundation.util.NameStyle.FANTASY;
    }

    @Override
    public void setNamingStyle(UUID colonyId, com.wsteam.wandscape.foundation.util.NameStyle style) {
        ColonySavedData csd = getColonySavedData();
        if (csd != null) {
            csd.setNamingStyle(colonyId, style);
        }
    }

    public void rebuildFromSavedData() {
        colonyOrigins.clear();
        colonyToOrigin.clear();

        // Primary source: ColonySavedData (independent colony persistence)
        ColonySavedData csd = getColonySavedData();
        if (csd != null && csd.size() > 0) {
            for (var entry : csd.getAllColonies().entrySet()) {
                colonyOrigins.put(entry.getValue(), entry.getKey());
                colonyToOrigin.put(entry.getKey(), entry.getValue());
            }
            Log.info(TAG, "[Colony] Rebuilt index: {} colonies from ColonySavedData", colonyOrigins.size());
            return;
        }

        // Fallback: scan government buildings (backward compat / migration).
        // This is a safety net for worlds created before ColonySavedData existed.
        // Do NOT require isStructureIntact() — a broken town hall is still the colony origin.
        BuildingSavedData sd = getSavedData();
        if (sd != null) {
            for (BuildingData bd : sd.getAllBuildings()) {
                if ("government".equals(bd.getCategory()) && bd.getColonyId() != null) {
                    colonyOrigins.put(bd.getPosition(), bd.getColonyId());
                    colonyToOrigin.put(bd.getColonyId(), bd.getPosition());
                    if (csd != null) {
                        csd.addColony(bd.getColonyId(), bd.getPosition());
                    }
                }
            }
            if (!colonyOrigins.isEmpty()) {
                Log.info(TAG, "[Colony] Rebuilt index: {} colonies from BuildingSavedData fallback (migrated)",
                        colonyOrigins.size());
            }
        }

        if (colonyOrigins.isEmpty()) {
            Log.warn(TAG, "[Colony] No colonies restored — ColonySavedData is empty and no government building found");
        }
    }

    // ── Colony level / experience ──────

    public void setColonyLevelManager(@Nullable ColonyLevelManager mgr) {
        this.colonyLevelManager = mgr;
    }

    @Override
    public int getColonyLevel(UUID colonyId) {
        if (colonyId == null || !colonyToOrigin.containsKey(colonyId)) return 0;
        return colonyLevelManager != null ? colonyLevelManager.getLevel(colonyId) : 1;
    }

    @Override
    public int getColonyExp(UUID colonyId) {
        if (colonyId == null || !colonyToOrigin.containsKey(colonyId)) return 0;
        return colonyLevelManager != null ? colonyLevelManager.getExperience(colonyId) : 0;
    }

    @Override
    public void grantExperience(UUID colonyId, int amount) {
        if (colonyId == null || !colonyToOrigin.containsKey(colonyId)) return;
        if (colonyLevelManager != null) colonyLevelManager.addExperience(colonyId, amount);
    }

    // ── ColonyApi 补齐：名字 / 上限 / 下一级经验 / 激活 / 等级设置 ──

    @Override
    public String getColonyName(UUID colonyId) {
        if (colonyId == null || !colonyToOrigin.containsKey(colonyId)) return "";
        return colonyLevelManager != null ? colonyLevelManager.getColonyName(colonyId) : "";
    }

    @Override
    public void setColonyName(UUID colonyId, String name) {
        if (colonyId == null || !colonyToOrigin.containsKey(colonyId)) return;
        if (colonyLevelManager != null) colonyLevelManager.setColonyName(colonyId, name);
    }

    @Override
    public int getMaxLevel() {
        return com.wsteam.wandscape.Config.COLONY_MAX_LEVEL.get();
    }

    @Override
    public int getExpToNext(UUID colonyId) {
        if (colonyId == null || !colonyToOrigin.containsKey(colonyId)) return 0;
        return colonyLevelManager != null ? colonyLevelManager.expToNextLevel(colonyId) : 0;
    }

    @Override
    public boolean isActive(UUID colonyId) {
        return ColonyActivation.isColonyActive(colonyId);
    }

    @Override
    public void setActive(UUID colonyId, boolean active) {
        ColonyActivation.setForcedActive(colonyId, active);
    }

    @Override
    public boolean setColonyLevel(UUID colonyId, int level) {
        if (colonyId == null || !colonyToOrigin.containsKey(colonyId)) return false;
        if (colonyLevelManager == null) return false;
        int max = com.wsteam.wandscape.Config.COLONY_MAX_LEVEL.get();
        if (level < 1 || level > max) return false;
        colonyLevelManager.setLevel(colonyId, level);
        return true;
    }

    // ── Singleton ─────────────────────────────────────────────────────────

    public static ColonyApiImpl get() {
        if (instance == null) {
            synchronized (ColonyApiImpl.class) {
                if (instance == null) instance = new ColonyApiImpl();
            }
        }
        return instance;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @Nullable
    private static BuildingSavedData getSavedData() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return BuildingSavedData.get(server.overworld());
    }

    @Nullable
    private static ColonySavedData getColonySavedData() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return ColonySavedData.getOrCreate(server.overworld());
    }

    /**
     * Set colonyId directly on a {@link BuildingState} reference.
     *
     * <p><b>IMPORTANT:</b> Do NOT use {@code getBuildingAt(pos)} for this — the
     * positional lookup relies on {@code posIndex} (only rebuilt from
     * BuildingConfig patterns, empty after server restart) and
     * {@code chunkIndex → bounds.isInside(anchor)} (only works when the anchor
     * lies inside the bounding box).  The caller already holds the live
     * {@link BuildingState} reference, so use it directly.
     */
    private void setColonyId(BuildingData data, @Nullable UUID colonyId) {
        if (data instanceof BuildingState bs) {
            bs.setColonyId(colonyId);
            BuildingSavedData sd = getSavedData();
            if (sd != null) sd.setDirty();
        }
    }
}
