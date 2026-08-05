package com.wsteam.wandscape.tourist.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.colony.ColonyLevelManager;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.TouristApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.tourist.entity.TouristEntity;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Drives tourists while their position chunk is unloaded.
 *
 * <p>Every tourist has a {@link TouristShadow}. Every {@code SIM_INTERVAL} ticks
 * this system walks all shadows and switches per tourist by chunk load state:
 * <ul>
 *   <li><b>Loaded</b> — the physical entity runs the real AI; the shadow mirrors it.
 *       On the unloaded→loaded transition the shadow wins (sim may have moved the
 *       tourist), then the entity takes over.</li>
 *   <li><b>Unloaded</b> — the sim advances the shadow: constant-speed straight-line
 *       movement (no terrain, no pathfinding), shop/service/hotel interactions and
 *       cooldowns via the shared {@link TouristSimulation} economy, then departure
 *       on night / energy exhaustion.</li>
 * </ul>
 *
 * <p>Orphaned entity bodies (departed tourists whose shadow was deleted) are
 * discarded when their chunk loads, per the "依赖 vanilla 身体 + 孤儿清除" model.
 */
public final class TouristSimSystem {

    private static final String TAG = "TouristSimSystem";
    /** Sim runs every tick — per-tourist work is a few arithmetic ops (negligible). */
    private static final int SIM_INTERVAL = 1;
    /** Constant straight-line speed per tick: 0.5 blocks/tick (matches entity speed). */
    private static final double SPEED = 0.5;
    private static final double ARRIVE_RANGE = 1.0;
    private static final int WANDER_RADIUS = 24;

    private int tickCounter;
    private TouristSimRegistry registry;
    private final Random random = new Random();

    @Nullable
    private static TouristSimSystem instance;

    private TouristSimSystem() {
    }

    @Nullable
    public static TouristSimSystem getActive() {
        return instance;
    }

    /** Create/reset the sim system and its registry for a server start. */
    public static TouristSimSystem register(ServerLevel level) {
        instance = new TouristSimSystem();
        instance.registry = TouristSimRegistry.getOrCreate(level);
        // Adopt any existing live tourists so an upgrade doesn't orphan them.
        instance.adoptExistingEntities(level);
        NeoForge.EVENT_BUS.register(instance);
        return instance;
    }

    public static void reset() {
        if (instance != null) {
            NeoForge.EVENT_BUS.unregister(instance);
            instance = null;
        }
    }

    public TouristSimRegistry getRegistry() {
        return registry;
    }

    /**
     * Create (or refresh) the data shadow for a live entity. Called when a
     * tourist spawns so the sim can track it once its chunk unloads.
     */
    public void adoptTourist(TouristEntity t) {
        if (registry == null) return;
        TouristShadow s = new TouristShadow();
        s.setTouristId(t.getUUID());
        s.setTouristName(t.getTouristName());
        s.setMage(t.isMage());
        s.setSkinVariant(t.getSkinVariant());
        s.setMaxMana(t.getMaxMana());
        s.setManaRegenRate(t.getManaRegenRate());
        s.setSpellPower(t.getSpellPower());
        exportToShadow(t, s);
        registry.put(t.getUUID(), s);
    }

    /** Remove a tourist's shadow (called when a loaded tourist departs). */
    public void removeShadow(UUID touristId) {
        if (registry != null) {
            registry.remove(touristId);
        }
    }

    // ── Server tick driver ──

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null || registry == null) return;

        if (++tickCounter % SIM_INTERVAL != 0) return;
        runTick(level);
    }

    private void runTick(ServerLevel level) {
        Map<UUID, TouristShadow> shadows = registry.getShadows();
        if (shadows.isEmpty()) return;

        // Index live entities for O(1) lookup + orphan scan.
        Map<UUID, TouristEntity> entities = new java.util.HashMap<>();
        for (var e : level.getAllEntities()) {
            if (e instanceof TouristEntity t) {
                if (!t.isAlive()) continue;
                entities.put(t.getUUID(), t);
                TouristShadow sh = shadows.get(t.getUUID());
                if (sh == null) {
                    // Orphan: no shadow → departed tourist, clear the residual body.
                    Log.info(TAG, "[Tourist] discarding orphan body {} (departed)", shortId(t.getUUID()));
                    t.discard();
                } else if (((int) t.getX() >> 4) != ((int) sh.getPosX() >> 4)
                        || ((int) t.getZ() >> 4) != ((int) sh.getPosZ() >> 4)) {
                    // Stale frozen body: the sim moved the shadow to another chunk. The
                    // real entity spawns/positions at the shadow's chunk — this leftover
                    // body would otherwise duplicate it when that chunk loads.
                    Log.info(TAG, "[Tourist] discarding stale body {} (shadow moved chunk)", shortId(t.getUUID()));
                    t.discard();
                }
            }
        }

        for (TouristShadow s : new ArrayList<>(shadows.values())) {
            boolean loaded = level.isLoaded(new BlockPos((int) s.getPosX(), (int) s.getPosY(), (int) s.getPosZ()));
            if (loaded) {
                handleLoaded(level, s, entities.get(s.getTouristId()));
            } else {
                simStep(level, s);
            }
        }
    }

    // ── Loaded path ──

    private void handleLoaded(ServerLevel level, TouristShadow s, @Nullable TouristEntity entity) {
        if (entity == null) {
            if (s.isHydrated()) {
                // The entity was removed (killed/discarded) while its chunk stayed
                // loaded — drop the shadow so the sim doesn't respawn it.
                registry.remove(s.getTouristId());
                Log.info(TAG, "[Tourist] dropped shadow {} (entity removed while loaded)", shortId(s.getTouristId()));
            } else {
                spawnEntity(level, s);
                s.markHydrated();
            }
            return;
        }
        if (!s.isHydrated()) {
            // Shadow wins on the unloaded→loaded transition (sim may have moved the tourist).
            importToEntity(entity, s);
            s.markHydrated();
        } else {
            // Live entity is the source while loaded.
            exportToShadow(entity, s);
        }
    }

    /** Create a physical entity from the shadow at the shadow's position. */
    private void spawnEntity(ServerLevel level, TouristShadow s) {
        TouristEntity tourist = new TouristEntity(Wandscape.TOURIST.get(), level);
        importToEntity(tourist, s);
        level.addFreshEntity(tourist);
        Log.info(TAG, "[Tourist] spawned entity {} from shadow at {}", shortId(s.getTouristId()),
                tourist.blockPosition().toShortString());
    }

    // ── Shadow ↔ entity sync ──

    private void importToEntity(TouristEntity e, TouristShadow s) {
        e.setTouristName(s.getTouristName());
        e.setSkinVariant(s.getSkinVariant());
        e.setAppearance(s.isMage() ? TouristEntity.Appearance.MAGE : TouristEntity.Appearance.TOURIST);
        e.setPos(s.getPosX(), s.getPosY(), s.getPosZ());
        // The sim ignores terrain — the shadow's Y may have drifted into the ground
        // or air. Snap to the nearest ground surface now that the chunk is loaded.
        if (e.level() instanceof ServerLevel sl) {
            BlockPos ground = groundAt(sl, e.getX(), e.getY(), e.getZ());
            if (ground != null) {
                e.setPos(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
            }
        }
        e.setLevel(s.getLevel());
        e.setWallet(s.getWallet());
        e.setInitialWallet(s.getInitialWallet());
        e.setEnergy(s.getEnergy());
        e.setSatisfaction(s.getSatisfaction());
        for (var entry : s.getTypePreferences().entrySet()) {
            e.adjustTypePreference(entry.getKey(), entry.getValue() - 40);
        }
        e.setColonyId(s.getColonyId());
        e.setTargetBuildingId(s.getTargetBuildingId());
        e.setTargetBuildingCategory(s.getTargetBuildingCategory());
        e.setCommuteTarget(s.getCommuteTarget());
        e.setCheckedInBuildingId(s.getCheckedInBuildingId());
        e.setHotelCheckinTime(s.getHotelCheckinTime());
        e.setArrivalTime(s.getArrivalTime());
        e.setMageResumeStored(s.isMageResumeStored());
        for (UUID id : s.getVisitedBuildings()) e.addVisitedBuilding(id);
        // Convert shadow cooldowns (simTick base) to entity tickCount base.
        int entityNow = e.tickCount;
        for (var entry : s.getServiceCooldownsMap().entrySet()) {
            int remaining = entry.getValue() - s.timeBase();
            if (remaining > 0) e.setServiceCooldown(entry.getKey(), entityNow + remaining);
        }
        int globalRemaining = s.getServiceCooldownEndTick() - s.timeBase();
        if (globalRemaining > 0) e.setServiceCooldownEndTick(entityNow + globalRemaining);
        for (var v : s.getRecentVisits()) e.addVisitMemory(v);
        e.applyState(com.wsteam.wandscape.tourist.internal.TouristState.VISITING);
    }

    private void exportToShadow(TouristEntity e, TouristShadow s) {
        s.setPosition(e.getX(), e.getY(), e.getZ());
        s.setLevel(e.getLevel());
        s.setWallet(e.getWallet());
        s.setInitialWallet(e.getInitialWallet());
        s.setEnergy(e.getEnergy());
        s.setSatisfaction(e.getSatisfaction());
        s.getTypePreferences().clear();
        s.getTypePreferences().putAll(e.getTypePreferencesMap());
        s.setColonyId(e.getColonyId());
        s.setTargetBuildingId(e.getTargetBuildingId());
        s.setTargetBuildingCategory(e.getTargetBuildingCategory());
        s.setCommuteTarget(e.getCommuteTarget());
        s.setCheckedInBuildingId(e.getCheckedInBuildingId());
        s.setHotelCheckinTime(e.getHotelCheckinTime());
        s.setArrivalTime(e.getArrivalTime());
        s.setMageResumeStored(e.isMageResumeStored());
        s.getVisitedBuildings().clear();
        s.getVisitedBuildings().addAll(e.getVisitedBuildings());
        int entityNow = e.tickCount;
        s.getServiceCooldownsMap().clear();
        for (var entry : e.getServiceCooldownsMap().entrySet()) {
            int remaining = entry.getValue() - entityNow;
            if (remaining > 0) s.setServiceCooldown(entry.getKey(), s.timeBase() + remaining);
        }
        int globalRemaining = e.getServiceCooldownEndTick() - entityNow;
        s.setServiceCooldownEndTick(globalRemaining > 0 ? s.timeBase() + globalRemaining : 0);
        s.clearRecentVisits();
        for (var v : e.getRecentVisits()) s.addVisitMemory(v);
    }

    // ── Unloaded sim step ──

    private void simStep(ServerLevel level, TouristShadow s) {
        s.advanceSimTick(SIM_INTERVAL);
        s.markUnhydrated();

        UUID hotel = s.getCheckedInBuildingId();
        if (hotel != null) {
            long dayTime = level.getDayTime() % 24000;
            if (dayTime >= 1000 && dayTime < 1200) {
                // Morning checkout: energy → 100.
                s.setCheckedInBuildingId(null);
                s.setHotelCheckinTime(0);
                s.setEnergy(100);
                s.setCommuteTarget(null);
            }
            return;
        }

        BlockPos commute = s.getCommuteTarget();
        if (commute != null) {
            if (moveToward(s, commute)) {
                if (s.getTargetBuildingId() != null) {
                    interact(level, s);
                } else {
                    s.setCommuteTarget(null);
                }
            }
        } else {
            decideNext(level, s);
        }

        checkDeparture(level, s);
    }

    private boolean moveToward(TouristShadow s, BlockPos target) {
        double tx = target.getX() + 0.5;
        double ty = target.getY();
        double tz = target.getZ() + 0.5;
        double dx = tx - s.getPosX();
        double dy = ty - s.getPosY();
        double dz = tz - s.getPosZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist <= ARRIVE_RANGE) {
            s.setPosition(tx, ty, tz);
            return true;
        }
        double step = Math.min(SPEED, dist);
        s.setPosition(
                s.getPosX() + dx / dist * step,
                s.getPosY() + dy / dist * step,
                s.getPosZ() + dz / dist * step);
        return false;
    }

    private void decideNext(ServerLevel level, TouristShadow s) {
        if (s.getServiceCooldownEndTick() > s.timeBase()) {
            wander(s);
            return;
        }
        BuildingState chosen = TouristSimulation.selectNextTarget(level, s);
        if (chosen != null) {
            s.setTargetBuildingId(chosen.getBuildingId());
            s.setTargetBuildingCategory(chosen.getCategory());
            s.setCommuteTarget(chosen.getAnchor());
            return;
        }
        wander(s);
    }

    /** Scan down a few blocks for the first solid-with-air-above surface; null if none nearby. */
    private static @Nullable BlockPos groundAt(ServerLevel level, double x, double y, double z) {
        int startY = Math.clamp((int) y + 1, level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 1);
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos((int) x, startY, (int) z);
        for (int i = 0; i < 16; i++) {
            if (!level.getBlockState(mp).isAir() && level.getBlockState(mp.above()).isAir()) {
                return mp.above().immutable();
            }
            mp.move(0, -1, 0);
        }
        return null;
    }

    private void wander(TouristShadow s) {
        double a = random.nextDouble() * 2 * Math.PI;
        double r = random.nextDouble() * WANDER_RADIUS;
        s.setCommuteTarget(new BlockPos(
                (int) (s.getPosX() + Math.cos(a) * r),
                (int) s.getPosY(),
                (int) (s.getPosZ() + Math.sin(a) * r)));
    }

    private void interact(ServerLevel level, TouristShadow s) {
        UUID buildingId = s.getTargetBuildingId();
        UUID colonyId = s.getColonyId();
        if (buildingId == null || colonyId == null) return;

        BuildingApi api = getBuildingApi();
        BuildingData data = api != null ? api.getBuilding(buildingId) : null;
        if (data == null || data.isShutdown() || !data.isStructureIntact()) {
            s.setCommuteTarget(null);
            s.setTargetBuildingId(null);
            s.setTargetBuildingCategory(null);
            return;
        }

        boolean isHotel = TouristSimulation.isHotelBuilding(level, buildingId);
        if (isHotel) {
            long dayTime = level.getDayTime() % 24000;
            boolean isNight = dayTime >= 13000;
            int sat = s.getSatisfaction();
            boolean energyDepleted = s.getEnergy() <= 0;
            if (sat >= 50 && sat < 100 && (isNight || energyDepleted) && hasHotelVacancy(level, buildingId)) {
                s.setCheckedInBuildingId(buildingId);
                s.setHotelCheckinTime(s.simTick());
                s.addVisitedBuilding(buildingId);
                s.setCommuteTarget(null);
                s.setTargetBuildingId(null);
                s.setTargetBuildingCategory(null);
                Log.info(TAG, "[Tourist] {} (sim) checked into hotel {}", shortId(s.getTouristId()), shortId(buildingId));
                return;
            }
        }

        String category = s.getTargetBuildingCategory();
        if ("shop".equals(category)) {
            TouristSimulation.performShopInteraction(level, s, buildingId, colonyId);
        } else if ("service".equals(category) || isHotel) {
            TouristSimulation.performServiceInteraction(level, s, buildingId, colonyId);
        }

        s.addVisitedBuilding(buildingId);
        s.setCommuteTarget(null);
        s.setTargetBuildingId(null);
        s.setTargetBuildingCategory(null);
    }

    /** Hotel vacancy derived from the shadow registry (covers loaded + unloaded tourists). */
    private boolean hasHotelVacancy(ServerLevel level, UUID buildingId) {
        var config = TouristSimulation.getConfig(level, buildingId);
        if (config == null || config.service() == null) return false;
        int max = config.service().maxOccupancy();
        if (max <= 0) return false;
        int occupied = 0;
        for (TouristShadow s : registry.getShadows().values()) {
            if (buildingId.equals(s.getCheckedInBuildingId())) occupied++;
        }
        return occupied < max;
    }

    // ── Departure ──

    private void checkDeparture(ServerLevel level, TouristShadow s) {
        if (s.getCheckedInBuildingId() != null) return;

        if (s.getSatisfaction() >= 100 && s.isMage() && !s.isMageResumeStored()) {
            storeMageResume(s);
            s.setMageResumeStored(true);
        }

        long dayTime = level.getDayTime() % 24000;
        boolean isNight = dayTime >= 13000;
        boolean energyDepleted = s.getEnergy() <= 0;
        boolean isIdle = s.getCommuteTarget() == null && s.getTargetBuildingId() == null;
        boolean idleTimeout = isIdle && s.simTick() > Config.TOURIST_DESPAWN_TIMEOUT_TICKS.get();
        int sat = s.getSatisfaction();

        boolean leave;
        if (sat >= 100) {
            leave = energyDepleted || (isNight && isIdle) || idleTimeout;
        } else if (sat >= 50) {
            if (energyDepleted || isNight) {
                leave = !routeToHotel(level, s);
            } else {
                leave = idleTimeout;
            }
        } else {
            leave = energyDepleted || (isNight && isIdle) || idleTimeout;
        }
        if (leave) {
            depart(level, s);
        }
    }

    private boolean routeToHotel(ServerLevel level, TouristShadow s) {
        UUID colonyId = s.getColonyId();
        if (colonyId == null) return false;
        BuildingApi api = getBuildingApi();
        if (api == null) return false;
        for (BuildingData b : api.getColonyBuildings(colonyId)) {
            if (!"service".equals(b.getCategory())) continue;
            if (b.isShutdown() || !b.isStructureIntact()) continue;
            if (!TouristSimulation.isHotelBuilding(level, b.getBuildingId())) continue;
            if (!hasHotelVacancy(level, b.getBuildingId())) continue;
            s.setTargetBuildingId(b.getBuildingId());
            s.setTargetBuildingCategory("service");
            s.setCommuteTarget(b.getPosition());
            return true;
        }
        return false;
    }

    private void depart(ServerLevel level, TouristShadow s) {
        grantExperience(s);
        if (s.isMage() && s.getSatisfaction() >= 100 && !s.isMageResumeStored()) {
            storeMageResume(s);
        }
        TouristApi touristApi = getTouristApi();
        if (touristApi != null && s.getColonyId() != null) {
            touristApi.registerDeparture(s.getTouristId(), s.getColonyId(), s.getSatisfaction());
        }
        registry.remove(s.getTouristId());
        Log.info(TAG, "[Tourist] {} (sim) departed (sat={} energy={})",
                s.getTouristName(), s.getSatisfaction(), s.getEnergy());
    }

    private void grantExperience(TouristShadow s) {
        if (s.getSatisfaction() < 100) return;
        ColonyLevelManager lm = WandscapeEngine.getColonyLevelManager();
        if (lm == null || s.getColonyId() == null) return;
        int colonyLevel = lm.getLevel(s.getColonyId());
        int contribution = ColonyLevelManager.computeExpContribution(colonyLevel, s.getLevel());
        if (contribution > 0) {
            lm.addExperience(s.getColonyId(), contribution);
        }
    }

    private void storeMageResume(TouristShadow s) {
        if (s.getColonyId() == null) return;
        try {
            WandscapeApis.getTavernApi().receiveMageResume(
                    s.getColonyId(), s.getTouristName(), s.getLevel(),
                    s.getMaxMana(), s.getManaRegenRate(), s.getSpellPower(), s.getSkinVariant());
        } catch (IllegalStateException e) {
            Log.warn(TAG, "[Tourist] TavernApi not available — mage resume lost: {}", s.getTouristName());
        }
    }

    // ── Startup adoption ──

    /** Create shadows for any live entities that predate this system (upgrade path). */
    private void adoptExistingEntities(ServerLevel level) {
        int adopted = 0;
        for (var e : level.getAllEntities()) {
            if (e instanceof TouristEntity t && t.isAlive() && registry.get(t.getUUID()) == null) {
                adoptTourist(t);
                adopted++;
            }
        }
        if (adopted > 0) {
            Log.info(TAG, "[Tourist] adopted {} existing entities into shadow registry", adopted);
        }
    }

    // ── Helpers ──

    @Nullable
    private static BuildingApi getBuildingApi() {
        try {
            return WandscapeApis.getBuildingApi();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    @Nullable
    private static TouristApi getTouristApi() {
        try {
            return WandscapeApis.getTouristApi();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
