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
import com.wsteam.wandscape.engine.colony.ColonyActivation;
import com.wsteam.wandscape.engine.colony.ColonyLevelManager;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.TouristApi;
import com.wsteam.wandscape.shared.data.BarRatio;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;
import com.wsteam.wandscape.tourist.entity.TouristEntity;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Drives tourists when no player can observe them.
 *
 * <p>Every tourist has a {@link TouristShadow}. Every {@code SIM_INTERVAL} ticks
 * this system walks all shadows and switches per tourist by whether any player is
 * within simulation distance. Chunk state is deliberately NOT used: spawn chunks
 * stay "loaded"/"ticking" even with the player far away, yet the real AI doesn't
 * behave for unobserved tourists — so player proximity is the only reliable signal:
 * <ul>
 *   <li><b>Observed</b> — the physical entity runs the real AI; the shadow mirrors it.
 *       On the unobserved→observed transition the shadow wins (sim may have moved the
 *       tourist), then the entity takes over.</li>
 *   <li><b>Unobserved</b> — the physical body is detached (UNLOADED_TO_CHUNK, shadow
 *       survives) and the sim advances the shadow: constant-speed straight-line
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
    /** 幽灵占位自愈探测间隔（tick）：周期释放占用者已消失的交互点，兜底任何漏清理路径。 */
    private static final int SPOT_PURGE_INTERVAL = 100;
    /** Constant straight-line speed per tick: 0.5 blocks/tick (matches entity speed). */
    private static final double SPEED = 0.5;
    private static final double ARRIVE_RANGE = 1.0;
    private static final int WANDER_RADIUS = 24;
    /** 住店客判定「已在自己旅店」的水平距离（格）：旅店可能很大，取宽松值。 */
    private static final int AT_HOTEL_RANGE = 16;

    private int tickCounter;
    private int simStepLogCounter;
    private int spotPurgeCounter;
    private TouristSimRegistry registry;
    private final Random random = new Random();

    /** Live tourist entities keyed by UUID — O(1) lookup for tick loops, avoiding level.getAllEntities(). */
    private static final java.util.Map<UUID, TouristEntity> LIVE_TOURISTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Register a spawned/loaded tourist entity for O(1) tick lookup. Called from TouristEntity.onAddedToLevel. */
    public static void registerEntity(TouristEntity t) {
        if (!t.isPreview()) LIVE_TOURISTS.put(t.getUUID(), t);
    }

    /** Unregister a killed/discarded/unloaded tourist entity. Called from TouristEntity.onRemovedFromLevel. */
    public static void unregisterEntity(UUID id) {
        LIVE_TOURISTS.remove(id);
    }

    /** Snapshot of currently live tourist entities (safe to iterate; backed by ConcurrentHashMap). */
    public static java.util.Collection<TouristEntity> getLiveTourists() {
        return LIVE_TOURISTS.values();
    }

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
        s.setTouristName(t.getTouristNameKey());
        s.setMage(t.isMage());
        s.setSkinVariant(t.getSkinVariant());
        s.setMaxHp(t.getMaxHp());
        s.setMoveSpeed(t.getMoveSpeed());
        s.setSpellPower(t.getSpellPower());
        s.setWorkSpeed(t.getWorkSpeed());
        s.setSpellSpeed(t.getSpellSpeed());
        s.setArmor(t.getArmor());
        s.setMaxMana(t.getMaxMana());
        exportToShadow(t, s);
        registry.put(t.getUUID(), s);
        Log.info(TAG, "[Tourist][diag] adopted shadow {} at ({},{}), commute={}, target={}",
                s.getTouristName(), (int) s.getPosX(), (int) s.getPosZ(),
                s.getCommuteTarget(), s.getTargetBuildingId());
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
        if (level == null || registry == null) {
            if (tickCounter == 0) Log.info(TAG, "[Tourist][diag] onServerTick skipped (server/level/registry null)");
            return;
        }
        if (tickCounter == 0) Log.info(TAG, "[Tourist][diag] onServerTick firing, shadows={}", registry.getShadows().size());

        // 幽灵占位自愈保险：占用者已不在世界且无 shadow（sim 驱动中实体 detach 但 shadow 在场不算幽灵）
        // → 释放该 spot 并清其排队（兜底漏清理路径）。
        if (++spotPurgeCounter % SPOT_PURGE_INTERVAL == 0) {
            int cleaned = TouristSpotManager.getActive().purgeMissing(
                    uuid -> level.getEntity(uuid) != null || registry.getShadows().containsKey(uuid));
            if (cleaned > 0) Log.info(TAG, "[Tourist] purged {} ghost spot(s)", cleaned);
        }

        if (++tickCounter % SIM_INTERVAL != 0) return;
        runTick(level);
    }

    // ── 玩家睡觉跳过夜晚：夜间批量快进（睡→醒，让夜晚后果照常发生）──

    @SubscribeEvent
    public void onSleepFinished(SleepFinishedTimeEvent event) {
        if (registry == null) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level != level.getServer().overworld()) return; // 游客只在主世界
        // ServerLevel.tick 里 setDayTime(EventHooks.onSleepFinished(...))：事件触发时 getDayTime() 仍是旧时刻，
        // getNewTime() = 次晨 dawn 绝对时刻 → skipped = 被跳过的夜晚 tick 数。
        long skipped = event.getNewTime() - level.getDayTime();
        if (skipped <= 0) return;
        fastForwardNight(level, skipped);
    }

    private void runTick(ServerLevel level) {
        Map<UUID, TouristShadow> shadows = registry.getShadows();
        if (shadows.isEmpty()) return;

        // Pre-compute player probes and sim-range once per tick (was O(S×P) alloc per shadow).
        double simRange = level.getServer().getPlayerList().getSimulationDistance() * 16.0;
        double simRangeSq = simRange * simRange;
        java.util.List<PlayerProbe> probeList = new java.util.ArrayList<>();
        for (var p : level.players()) {
            if (!p.isSpectator()) probeList.add(new PlayerProbe(p.getX(), p.getZ()));
        }
        PlayerProbe[] probes = probeList.toArray(new PlayerProbe[0]);

        // Index live entities for O(1) lookup + orphan scan.
        // Uses the static LIVE_TOURISTS cache (populated by TouristEntity lifecycle)
        // instead of level.getAllEntities() — that iterates every entity in the world
        // (items, mobs, xp orbs, …) every tick, which is the #1 CPU hog.
        Map<UUID, TouristEntity> entities = new java.util.HashMap<>();
        for (TouristEntity t : LIVE_TOURISTS.values()) {
            if (!t.isAlive()) continue;
            if (t.isPreview()) continue; // 预览假人：无 shadow，不参与 sim/孤儿清除
            entities.put(t.getUUID(), t);
            // Orphan: no shadow → departed tourist, clear the residual body.
            // (A chunk-unload race can briefly move a shadow to another chunk while
            // the body is still loaded — do NOT discard by position difference, that
            // kills freshly spawned tourists.)
            if (!shadows.containsKey(t.getUUID())) {
                Log.info(TAG, "[Tourist] discarding orphan body {} (departed)", shortId(t.getUUID()));
                t.discard();
            }
        }

        int observedCount = 0, simmedCount = 0, stuckCount = 0, frozenCount = 0;
        for (TouristShadow s : new ArrayList<>(shadows.values())) {
            // 创始人不在线 → 冻结小镇：游客原地冻结——不 sim、不实体化、不离场、不被清。
            // 冻结期间占位/排队保留（shadow 仍在 registry，spot purge 不误清）。
            if (s.getColonyId() != null && !ColonyActivation.isColonyActive(s.getColonyId())) {
                frozenCount++;
                continue;
            }
            // The sim drives a tourist whenever no player can observe it. Chunk state is
            // an unreliable proxy here: spawn chunks stay "loaded"/"ticking" even with the
            // player far away (so isLoaded/isPositionTicking never let the sim take over),
            // yet the real AI doesn't actually behave for unobserved tourists. Player
            // proximity is the signal that decides whether the physical entity runs.
            boolean observed = probes.length > 0 && hasObserver(simRangeSq, s.getPosX(), s.getPosZ(), probes);
            if (observed) {
                observedCount++;
                if (entities.get(s.getTouristId()) == null) stuckCount++;
                handleLoaded(level, s, entities.get(s.getTouristId()));
            } else {
                // Detach the physical body (UNLOADED_TO_CHUNK ≠ KILLED/DISCARDED, so the
                // shadow survives) so it can't double-run real AI against the sim's shadow.
                TouristEntity body = entities.get(s.getTouristId());
                if (body != null && body.isAlive()) {
                    body.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                }
                // 实体→sim 切换瞬间才清瞬时交互/排队状态（onRemovedFromLevel 已释放实体的 spot/queue）。
                // 不能每 tick 重置——否则排队中的 shadow 每次被踢出队尾、交互中的被清零，
                // 排队/交互永不推进（游客原地卡死）。
                if (s.isHydrated()) {
                    s.setInteractTicksLeft(0);
                    s.setQueueSpotIndex(-1);
                    s.setOccupiedSpot(-1);
                }
                simmedCount++;
                simStep(level, s);
            }
        }
        if (tickCounter % 200 == 0) {
            Log.info(TAG, "[Tourist][diag] runTick shadows={} observed={} (entity-null={}) simmed={} frozen={}",
                    shadows.size(), observedCount, stuckCount, simmedCount, frozenCount);
        }
    }

    /** Pre-computed player positions for observer checks (refreshed once per runTick). */
    private static final class PlayerProbe {
        final double x, z;
        PlayerProbe(double x, double z) { this.x = x; this.z = z; }
    }

    /** True when a non-spectator player is within simulation distance of the tourist.
     *  @param probes pre-computed player positions (refreshed once per runTick to avoid
     *         O(shadows × players) iterator allocations). */
    private static boolean hasObserver(double simRangeSq, double sx, double sz, PlayerProbe[] probes) {
        for (PlayerProbe p : probes) {
            double dx = p.x - sx;
            double dz = p.z - sz;
            if (dx * dx + dz * dz < simRangeSq) return true;
        }
        return false;
    }

    // ── Loaded path ──

    private void handleLoaded(ServerLevel level, TouristShadow s, @Nullable TouristEntity entity) {
        if (entity == null) {
            if (!s.isHydrated()) {
                spawnEntity(level, s);
                s.markHydrated();
            }
            // entity == null && hydrated: the chunk is mid-unload (entity already removed
            // from the loaded list while isLoaded is still briefly true) or the tourist was
            // killed (onRemovedFromLevel already removed the shadow). Do nothing — the
            // chunk finishes unloading and simStep takes over. Never drop the shadow here:
            // that orphaned the surviving entity and mass-killed tourists on world load.
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
        if (s.getTouristId() == null) return;
        // sim 占的 spot/队不迁移给实体——实体走 TouristMoveGoal 重新占位/排队。
        releaseShadowSpots(s);
        TouristEntity tourist = new TouristEntity(Wandscape.TOURIST.get(), level);
        // The body must BE this shadow's tourist. Without this, the fresh body gets a
        // random UUID and onAddedToLevel's auto-adopt registers it as a brand-new shadow,
        // leaving the original shadow as a ghost that respawns bodies — duplicating the
        // tourist exponentially across unload/reload cycles (and making duplicates unkillable).
        tourist.setUUID(s.getTouristId());
        importToEntity(tourist, s);
        level.addFreshEntity(tourist);
        Log.info(TAG, "[Tourist] spawned entity {} from shadow at {}", shortId(s.getTouristId()),
                tourist.blockPosition().toShortString());
    }

    // ── Shadow ↔ entity sync ──

    private void importToEntity(TouristEntity e, TouristShadow s) {
        e.setTouristName(s.getTouristNameKey());
        e.setSkinVariant(s.getSkinVariant());
        e.setAppearance(s.isMage() ? TouristEntity.Appearance.MAGE : TouristEntity.Appearance.TOURIST);
        e.setPos(s.getPosX(), s.getPosY(), s.getPosZ());
        // The sim ignores terrain — the shadow's Y may have drifted into the ground
        // or air. Snap to the nearest ground surface now that the chunk is loaded.
        // The shadow straight-lines through terrain, so its column can be over a
        // building: never hydrate onto a roof — relocate outside all buildings.
        if (e.level() instanceof ServerLevel sl) {
            BlockPos ground = groundAt(sl, e.getX(), e.getY(), e.getZ());
            if (ground != null) {
                if (TouristTeleport.isRoofInsideBuilding(sl, ground, s.getColonyId())) {
                    BlockPos safe = TouristTeleport.findSafeSpot(sl, ground, s.getColonyId(), s.getTargetBuildingId());
                    if (safe != null) ground = safe;
                }
                e.setPos(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
            }
        }
        e.setLevel(s.getLevel());
        e.setWallet(s.getWallet());
        e.setInitialWallet(s.getInitialWallet());
        e.setEnergy(s.getEnergy());
        e.setComfortSat(s.getComfortSat());
        e.setMagicSat(s.getMagicSat());
        e.setWonderSat(s.getWonderSat());
        e.setComfortNeed(s.getComfortNeed());
        e.setMagicNeed(s.getMagicNeed());
        e.setWonderNeed(s.getWonderNeed());
        e.setCurrentActivity(s.getCurrentActivity());
        e.setActivityTicks(s.getActivityTicks());
        e.setOccupiedSpot(s.getOccupiedSpot());
        e.setNightsStayed(s.getNightsStayed());
        e.setDepartureDeadline(s.getDepartureDeadline());
        e.setTravelFund(s.getTravelFund());
        e.setLastAtmWithdrawTime(s.getLastAtmWithdrawTime());
        e.setColonyId(s.getColonyId());
        e.setTargetBuildingId(s.getTargetBuildingId());
        e.setTargetBuildingCategory(s.getTargetBuildingCategory());
        e.setCommuteTarget(s.getCommuteTarget());
        e.setCheckedInBuildingId(s.getCheckedInBuildingId());
        e.setHotelCheckinTime(s.getHotelCheckinTime());
        e.setWakeUpPos(s.getWakeUpPos());
        e.setArrivalTime(s.getArrivalTime());
        e.setMageResumeStored(s.isMageResumeStored());
        // Shadow may carry the pre-Block-2 defaults (arrival=0 / deadline=Long.MAX_VALUE) —
        // heal so the info screen's stay-day count and deadline departure stay sane.
        e.ensureStayWindow(e.level().getGameTime());
        for (UUID id : s.getVisitedBuildings()) e.addVisitedBuilding(id);
        for (var v : s.getRecentVisits()) e.addVisitMemory(v);
        e.applyState(com.wsteam.wandscape.tourist.internal.TouristState.VISITING);
    }

    private void exportToShadow(TouristEntity e, TouristShadow s) {
        s.setPosition(e.getX(), e.getY(), e.getZ());
        s.setLevel(e.getLevel());
        s.setWallet(e.getWallet());
        s.setInitialWallet(e.getInitialWallet());
        s.setEnergy(e.getEnergy());
        s.setComfortSat(e.getComfortSat());
        s.setMagicSat(e.getMagicSat());
        s.setWonderSat(e.getWonderSat());
        s.setComfortNeed(e.getComfortNeed());
        s.setMagicNeed(e.getMagicNeed());
        s.setWonderNeed(e.getWonderNeed());
        s.setCurrentActivity(e.getCurrentActivity());
        s.setActivityTicks(e.getActivityTicks());
        s.setOccupiedSpot(e.getOccupiedSpot());
        s.setNightsStayed(e.getNightsStayed());
        s.setDepartureDeadline(e.getDepartureDeadline());
        s.setTravelFund(e.getTravelFund());
        s.setLastAtmWithdrawTime(e.getLastAtmWithdrawTime());
        s.setColonyId(e.getColonyId());
        s.setTargetBuildingId(e.getTargetBuildingId());
        s.setTargetBuildingCategory(e.getTargetBuildingCategory());
        s.setCommuteTarget(e.getCommuteTarget());
        s.setCheckedInBuildingId(e.getCheckedInBuildingId());
        s.setHotelCheckinTime(e.getHotelCheckinTime());
        s.setWakeUpPos(e.getWakeUpPos());
        s.setArrivalTime(e.getArrivalTime());
        s.setMageResumeStored(e.isMageResumeStored());
        s.getVisitedBuildings().clear();
        s.getVisitedBuildings().addAll(e.getVisitedBuildings());
        s.clearRecentVisits();
        for (var v : e.getRecentVisits()) s.addVisitMemory(v);
    }

    // ── Unloaded sim step ──

    private void simStep(ServerLevel level, TouristShadow s) {
        if (++simStepLogCounter % 100 == 0) {
            Log.info(TAG, "[Tourist][diag] simStep {} pos=({},{}) commute={} target={} bars={}/{}/{} energy={} tick={}",
                    s.getTouristName(), (int) s.getPosX(), (int) s.getPosZ(),
                    s.getCommuteTarget() != null ? s.getCommuteTarget().toShortString() : "null",
                    s.getTargetBuildingId() != null ? s.getTargetBuildingId().toString().substring(0, 8) : "null",
                    s.getComfortSat(), s.getMagicSat(), s.getWonderSat(), s.getEnergy(), s.simTick());
        }
        s.advanceSimTick(SIM_INTERVAL);
        s.markUnhydrated();

        long dayTime = level.getDayTime() % 24000;
        boolean isNight = dayTime >= Config.TOURIST_NIGHT_START.get();
        UUID hotel = s.getCheckedInBuildingId();

        // 白天：解除夜晚「无空闲旅店」闩锁，让下一晚重新尝试找旅店
        if (dayTime < Config.TOURIST_EVENING_ROUTING_START.get()) {
            s.getHotelRouteBackoff().clear();
        }

        if (hotel != null) {
            if (dayTime < 1000) {
                // 深夜：住店客在旅店，不动
                checkDeparture(level, s);
                return;
            }
            if (dayTime < 1200) {
                // 清晨晨起：住店晚数 +1、精力回 100、结算一晚满意值、回入住前站位；**保持登记**（名单不删）
                s.setHotelCheckinTime(0);
                s.setNightsStayed(s.getNightsStayed() + 1);
                s.setEnergy(WandscapeConstants.TOURIST_MAX_ENERGY);
                TouristSimulation.grantHotelNightStay(level, s, hotel);
                s.setCommuteTarget(null);
                BlockPos wake = s.getWakeUpPos();
                if (wake != null) {
                    s.setPosition(wake.getX() + 0.5, wake.getY(), wake.getZ() + 0.5);
                    s.setWakeUpPos(null);
                }
                // 晨起后白天外出 → fall through 到正常 sim
            } else if (isNight) {
                // 夜晚：未满条 → 回自己旅店睡；满条 → 不回店（18000+ 由 checkDeparture 离场）
                if (!s.isFullySatisfied()) {
                    boolean busy = s.getInteractTicksLeft() > 0 || s.getQueueSpotIndex() >= 0;
                    if (!busy) {
                        if (atOwnHotel(level, s, hotel)) {
                            checkDeparture(level, s);
                            return; // 已到店 → 不动
                        }
                        if (!hotel.equals(s.getTargetBuildingId())) {
                            if (!routeToOwnHotel(level, s)) {
                                // 旅店已失效 → 解除登记，按无旅店游客处理
                                s.setCheckedInBuildingId(null);
                                s.setHotelCheckinTime(0);
                                s.setCommuteTarget(null);
                                s.setTargetBuildingId(null);
                                s.setTargetBuildingCategory(null);
                            }
                        }
                        // fall through → moveToward 回店
                    }
                    // 交互/排队中：先完成（完成后下 tick 回店）
                }
                // 满条住店客夜晚 → 正常 sim（不回店，等离场窗口）
            }
            // 白天（1200-14000）：外出逛街，fall through
        }

        // ── 傍晚路由：无旅店游客停止当前任务去旅店（防夜晚无旅店被清场；16000 起）──
        if (dayTime >= Config.TOURIST_EVENING_ROUTING_START.get()
                && hotel == null && !s.isFullySatisfied()) {
            UUID cur = s.getTargetBuildingId();
            if (cur == null || !TouristSimulation.isHotelBuilding(level, cur)) {
                // 闩锁中（当晚无空闲旅店）：不每 sim-tick 重扫建筑（与实体一致，避免夜晚全扫卡顿）
                if (!s.getHotelRouteBackoff().isActive() && !routeToHotelForEvening(level, s)) {
                    // 无空闲旅店 → 闩锁今晚不再搜索（夜晚无退宿，重扫白费），离场窗口兜底
                    s.getHotelRouteBackoff().enter();
                }
            }
            // fall through → 正常移动逻辑（走回旅店）
        }

        // ── 交互中：在交互点等 interactionDuration 倒计时结束才结算（与实体一致，防 sim 0CD 刷产出）──
        if (s.getInteractTicksLeft() > 0) {
            s.setInteractTicksLeft(s.getInteractTicksLeft() - SIM_INTERVAL);
            if (s.getInteractTicksLeft() <= 0) {
                settleInteraction(level, s);
            }
            checkDeparture(level, s);
            return;
        }

        // ── 排队中：仅队首可认领空 spot（与实体一致，spot 串行化限制并发交互）──
        if (s.getQueueSpotIndex() >= 0) {
            // 排队超时（与实体 tickQueue 一致）：超 TOURIST_QUEUE_WAIT_TOLERANCE_TICKS 放弃去别处
            s.setQueueTicks(s.getQueueTicks() + 1);
            if (s.getQueueTicks() > Config.TOURIST_QUEUE_WAIT_TOLERANCE_TICKS.get()) {
                abandonQueue(level, s);
                checkDeparture(level, s);
                return;
            }
            tryClaimQueuedSpot(level, s);
            checkDeparture(level, s);
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
        BuildingState chosen = TouristSimulation.selectNextTarget(level, s, false);
        if (chosen != null) {
            s.setTargetBuildingId(chosen.getBuildingId());
            s.setTargetBuildingCategory(chosen.getCategory());
            BlockPos target = TouristSimulation.spotWorldPos(level, chosen.getBuildingId(), 0);
            s.setCommuteTarget(target != null ? target : chosen.getAnchor());
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
            boolean isNight = dayTime >= Config.TOURIST_NIGHT_START.get();
            boolean alreadyResident = buildingId.equals(s.getCheckedInBuildingId());
            if (isNight && !s.isFullySatisfied()) {
                // 夜晚 + 未满条：到达即入住（住店客回店免查空位、免重复填条）——入住即时完成，无 spot 交互
                if (alreadyResident || hasHotelVacancy(level, buildingId)) {
                    if (!alreadyResident) {
                        s.setCheckedInBuildingId(buildingId);
                        s.setHotelCheckinTime(s.simTick());
                        s.setWakeUpPos(s.touristPos());
                        s.addVisitedBuilding(buildingId);
                        // 满意值不在此结算，改为每晚晨起结算（见 TouristSimulation.grantHotelNightStay）
                        Log.info(TAG, "[Tourist] {} (sim) checked into hotel {}", shortId(s.getTouristId()), shortId(buildingId));
                    }
                    s.setCommuteTarget(null);
                    s.setTargetBuildingId(null);
                    s.setTargetBuildingCategory(null);
                    return;
                }
                // 夜晚意图入住但旅店满员 → 不当 service 逛/排队（避免排队拖到被清场），放弃重新规划
                s.setCommuteTarget(null);
                s.setTargetBuildingId(null);
                s.setTargetBuildingCategory(null);
                return;
            }
            // 白天/满条 → 当普通 service 交互（fall through）
        }

        // 占 spot 启动交互时长（与实体一致）；spot 全满 → 排队（每 spot 一队，均匀分散到队最短的 spot）。
        int total = TouristSimulation.interactSpotCount(level, buildingId);
        int spot = TouristSpotManager.getActive().claim(buildingId, total, s.getTouristId());
        if (spot >= 0) {
            s.setOccupiedSpot(spot);
            s.setInteractTicksLeft(TouristSimulation.interactionDuration(level, buildingId));
        } else {
            int spotIdx = TouristSpotManager.getActive().shortestQueueSpot(buildingId, total);
            TouristSpotManager.getActive().joinQueue(buildingId, spotIdx, s.getTouristId());
            s.setQueueSpotIndex(spotIdx);
        }
    }

    /** 排队轮询：仅队首可认领空 spot；认领成功启动交互时长。建筑失效/target 已变 → 退出排队。 */
    private void tryClaimQueuedSpot(ServerLevel level, TouristShadow s) {
        UUID buildingId = s.getTargetBuildingId();
        int spotIdx = s.getQueueSpotIndex();
        if (buildingId == null || spotIdx < 0) {
            s.setQueueSpotIndex(-1);
            return;
        }
        // 建筑失效（被拆/停用）→ 退出排队重规划
        BuildingApi api = getBuildingApi();
        BuildingData data = api != null ? api.getBuilding(buildingId) : null;
        if (data == null || data.isShutdown() || !data.isStructureIntact()) {
            TouristSpotManager.getActive().leaveAllQueues(buildingId, s.getTouristId());
            s.setQueueSpotIndex(-1);
            s.setCommuteTarget(null);
            s.setTargetBuildingId(null);
            s.setTargetBuildingCategory(null);
            return;
        }
        // 已不在目标建筑的队里（target 被 routeToHotel 等改掉）→ 退出排队，走移动/重规划
        if (TouristSpotManager.getActive().queuePosition(buildingId, spotIdx, s.getTouristId()) < 0) {
            s.setQueueSpotIndex(-1);
            return;
        }
        if (TouristSpotManager.getActive().queuePosition(buildingId, spotIdx, s.getTouristId()) != 0) {
            return; // 非队首，继续等
        }
        int total = TouristSimulation.interactSpotCount(level, buildingId);
        int spot = TouristSpotManager.getActive().claimAt(buildingId, spotIdx, total, s.getTouristId());
        if (spot >= 0) {
            TouristSpotManager.getActive().leaveAllQueues(buildingId, s.getTouristId());
            s.setQueueSpotIndex(-1);
            s.setOccupiedSpot(spot);
            s.setInteractTicksLeft(TouristSimulation.interactionDuration(level, buildingId));
        }
    }

    /** 排队超时放弃（镜像实体 abandonBuildingVisit）：离队、清目标、记 visited，
     *  下 tick 重新规划去别处（避免超时→重排→再超时死循环）。 */
    private void abandonQueue(ServerLevel level, TouristShadow s) {
        UUID buildingId = s.getTargetBuildingId();
        if (buildingId != null) {
            TouristSpotManager.getActive().leaveAllQueues(buildingId, s.getTouristId());
            // 放弃也算「逛过」：本次停留不再尝试该建筑（与实体一致）
            s.addVisitedBuilding(buildingId);
            Log.info(TAG, "[Tourist] {} (sim) abandoned queue at {} (wait timeout), re-planning",
                    s.getTouristName(), shortId(buildingId));
        }
        s.setQueueSpotIndex(-1);
        s.setInteractTicksLeft(0);
        s.setOccupiedSpot(-1);
        s.setCommuteTarget(null);
        s.setTargetBuildingId(null);
        s.setTargetBuildingCategory(null);
    }

    /** 交互时长结束：结算产出/记行程/标记已逛，释放 spot 并清队，重新规划下一目标。 */
    private void settleInteraction(ServerLevel level, TouristShadow s) {
        UUID buildingId = s.getTargetBuildingId();
        UUID colonyId = s.getColonyId();
        String category = s.getTargetBuildingCategory();
        if (buildingId != null && colonyId != null) {
            var result = switch (category == null ? "" : category) {
                case "shop" -> TouristSimulation.performShopInteraction(level, s, buildingId, colonyId);
                case "relax" -> TouristSimulation.performRelaxInteraction(level, s, buildingId, colonyId);
                case "atm" -> TouristSimulation.performAtmInteraction(level, s, buildingId, colonyId);
                default -> TouristSimulation.performServiceInteraction(level, s, buildingId, colonyId);
            };
            if (result != null) {
                Log.info(TAG, "[Tourist] {} (sim) {} at {} '{}' → bars {}/{}/{}, energy {}",
                        s.getTouristName(), result.whatHappened(), shortId(buildingId), category,
                        s.getComfortSat(), s.getMagicSat(), s.getWonderSat(), s.getEnergy());
                // 与实体路径一致：交互记入行程（买不起记「逛了一圈什么也没买」）
                String bldType = TouristSimulation.getBuildingTypeId(level, buildingId);
                var bldCfg = TouristSimulation.getConfig(level, buildingId);
                String bldName = (bldCfg != null && bldCfg.displayName() != null && !bldCfg.displayName().isEmpty())
                        ? bldCfg.displayName() : (bldType != null ? bldType : "unknown");
                TouristSimulation.addVisitMemory(s, bldType, bldName, category,
                        level.getGameTime(), result.comfortDelta(), result.magicDelta(), result.wonderDelta(),
                        result.energyDelta(), result.whatHappened());
            } else {
                Log.info(TAG, "[Tourist] {} (sim) nothing buyable at {} '{}'",
                        s.getTouristName(), shortId(buildingId), category);
            }
            s.addVisitedBuilding(buildingId);
        }
        // 释放占位 / 清队
        int spot = s.getOccupiedSpot();
        if (buildingId != null && spot >= 0) {
            TouristSpotManager.getActive().release(buildingId, spot);
        }
        s.setOccupiedSpot(-1);
        if (buildingId != null && s.getQueueSpotIndex() >= 0) {
            TouristSpotManager.getActive().leaveAllQueues(buildingId, s.getTouristId());
        }
        s.setQueueSpotIndex(-1);
        s.setInteractTicksLeft(0);
        s.setCommuteTarget(null);
        s.setTargetBuildingId(null);
        s.setTargetBuildingCategory(null);
    }

    /** 释放 shadow 占用的 spot 与排队（实体化/离场前调用，防 sim 占位残留卡死实体）。 */
    private void releaseShadowSpots(TouristShadow s) {
        UUID buildingId = s.getTargetBuildingId();
        if (buildingId != null) {
            int spot = s.getOccupiedSpot();
            if (spot >= 0) {
                TouristSpotManager.getActive().release(buildingId, spot);
            }
            if (s.getQueueSpotIndex() >= 0) {
                TouristSpotManager.getActive().leaveAllQueues(buildingId, s.getTouristId());
            }
        }
        s.setOccupiedSpot(-1);
        s.setQueueSpotIndex(-1);
        s.setInteractTicksLeft(0);
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

    // ── 夜间快进（玩家睡觉跳过夜晚）──

    /**
     * 玩家睡觉跳过夜晚时，把「睡→醒」这一整段在 sim 状态批量快进：
     * 无旅店未满条游客离场、满条游客当晚离场、住店客晨起、有旅店游客入住+晨起——
     * 让跳过夜晚后游客处于与真实夜晚过去后一致的终态（不积压人口）。
     * 观察中的活实体随后把快进结果推回实体（importToEntity），否则下一 tick
     * exportToShadow 会把影子覆盖回实体旧状态、撤销快进。
     */
    private void fastForwardNight(ServerLevel level, long skippedTicks) {
        long wakeGameTime = level.getGameTime() + skippedTicks; // 模拟「醒来」时刻（用于截止判定）
        Log.info(TAG, "[Tourist] 玩家睡觉跳过夜晚：快进 {} tick 模拟夜间（{} 名游客）",
                skippedTicks, registry.getShadows().size());
        Map<UUID, TouristEntity> live = new java.util.HashMap<>();
        for (TouristEntity t : LIVE_TOURISTS.values()) {
            if (t.isAlive() && !t.isPreview()) live.put(t.getUUID(), t);
        }
        for (TouristShadow s : new ArrayList<>(registry.getShadows().values())) {
            // 创始人不在线 → 冻结小镇：不随夜晚快进（原地冻结，保留当前状态）
            if (s.getColonyId() != null && !ColonyActivation.isColonyActive(s.getColonyId())) {
                continue;
            }
            s.advanceSimTick((int) skippedTicks);
            releaseShadowSpots(s);
            s.setCommuteTarget(null);
            s.setTargetBuildingId(null);
            s.setTargetBuildingCategory(null);

            // 住店客旅店失效 → 解除登记，按无旅店处理（镜像 routeToOwnHotel 校验）
            UUID hotel = s.getCheckedInBuildingId();
            if (hotel != null && !hotelStillValid(level, hotel)) {
                s.setCheckedInBuildingId(null);
                s.setHotelCheckinTime(0);
                hotel = null;
            }

            boolean deadline = wakeGameTime >= s.getDepartureDeadline();
            boolean full = s.isFullySatisfied();
            BuildingState hotelTarget = null;
            if (!deadline && !full && hotel == null) {
                hotelTarget = TouristSimulation.findHotelTarget(level, s, false);
            }
            switch (nightOutcome(deadline, full, hotel != null, hotelTarget != null)) {
                case DEPART_DEADLINE, DEPART_FULL, DEPART_NO_HOTEL -> {
                    depart(level, s);
                    continue;
                }
                case WAKE -> wakeUpShadow(level, s);
                case CHECKIN_WAKE -> {
                    checkInAtNight(s, hotelTarget.getBuildingId());
                    wakeUpShadow(level, s);
                }
            }

            TouristEntity e = live.get(s.getTouristId());
            if (e != null && e.isAlive()) {
                if (e.isSleeping()) e.stopSleeping(); // 快进可能已晨起，解除睡姿
                importToEntity(e, s);
            }
        }
    }

    /** 夜间快进结果判定（纯逻辑，可单测）：deadline/满条 优先于住店/无旅店。 */
    enum NightOutcome {
        DEPART_DEADLINE, DEPART_FULL, DEPART_NO_HOTEL, WAKE, CHECKIN_WAKE
    }

    static NightOutcome nightOutcome(boolean deadlineReached, boolean fullySatisfied,
            boolean checkedInValid, boolean hotelFound) {
        if (deadlineReached) return NightOutcome.DEPART_DEADLINE;
        if (fullySatisfied) return NightOutcome.DEPART_FULL;
        if (checkedInValid) return NightOutcome.WAKE;
        return hotelFound ? NightOutcome.CHECKIN_WAKE : NightOutcome.DEPART_NO_HOTEL;
    }

    /** 晨起：精力回 100、住店晚数 +1、结算一晚满意值、回入住前站位、保留登记（镜像 simStep 晨起分支）。 */
    private void wakeUpShadow(ServerLevel level, TouristShadow s) {
        s.setHotelCheckinTime(0);
        s.setNightsStayed(s.getNightsStayed() + 1);
        s.setEnergy(WandscapeConstants.TOURIST_MAX_ENERGY);
        BlockPos wake = s.getWakeUpPos();
        if (wake != null) {
            s.setPosition(wake.getX() + 0.5, wake.getY(), wake.getZ() + 0.5);
            s.setWakeUpPos(null);
        }
        UUID hotel = s.getCheckedInBuildingId();
        if (hotel != null) {
            TouristSimulation.grantHotelNightStay(level, s, hotel);
        }
    }

    /** 夜晚入住：登记（满意值不在此结算，改为紧接的晨起结算）。 */
    private void checkInAtNight(TouristShadow s, UUID buildingId) {
        s.setCheckedInBuildingId(buildingId);
        s.setHotelCheckinTime(s.simTick());
        s.setWakeUpPos(s.touristPos());
        s.addVisitedBuilding(buildingId);
        Log.info(TAG, "[Tourist] {} (快进夜) checked into hotel {}", shortId(s.getTouristId()), shortId(buildingId));
    }

    /** 住店客旅店是否仍有效（镜像 routeToOwnHotel 校验）。 */
    private boolean hotelStillValid(ServerLevel level, UUID hotel) {
        BuildingApi api = getBuildingApi();
        BuildingData data = api != null ? api.getBuilding(hotel) : null;
        return data != null && !data.isShutdown() && data.isStructureIntact()
                && TouristSimulation.isHotelBuilding(level, hotel);
    }

    // ── Departure ──

    private void checkDeparture(ServerLevel level, TouristShadow s) {
        UUID hotel = s.getCheckedInBuildingId();
        long dayTime = level.getDayTime() % 24000;
        // 离场/清场只在 18000-24000 窗口（与实体路径一致，sim 不再 14000 起提前清人）
        boolean inDepartureWindow = dayTime >= Config.TOURIST_DEPARTURE_WINDOW_START.get()
                && dayTime < Config.TOURIST_DEPARTURE_WINDOW_END.get();

        if (hotel != null) {
            // 住店客：只按停留截止（任意时刻）或满条在离场窗口离场；其余时刻不被清
            if (level.getGameTime() >= s.getDepartureDeadline()) {
                depart(level, s);
            } else if (s.isFullySatisfied() && inDepartureWindow) {
                depart(level, s);
            }
            return;
        }

        if (s.isFullySatisfied() && s.isMage() && !s.isMageResumeStored()) {
            storeMageResume(s);
            s.setMageResumeStored(true);
        }

        boolean isIdle = s.getCommuteTarget() == null && s.getTargetBuildingId() == null;
        boolean idleTimeout = isIdle && s.simTick() > Config.TOURIST_DESPAWN_TIMEOUT_TICKS.get();
        // 交互/排队中：不转旅店（routeToHotel 会改 target 打断当前交互），先完成当前交互，
        // 完成后 decideNext 的夜晚逻辑自会选旅店。
        boolean interacting = s.getInteractTicksLeft() > 0 || s.getQueueSpotIndex() >= 0;

        // D6 离场（goal.md）：到点 / 满条离场窗口 / 离场窗口无旅店 / idle 超时
        boolean leave;
        if (level.getGameTime() >= s.getDepartureDeadline()) {
            leave = true;
        } else if (s.isFullySatisfied()) {
            // 满条等离场窗口再离场（白天满条先开心闲逛；14000-18000 不提前清）
            leave = inDepartureWindow || idleTimeout;
        } else if (inDepartureWindow) {
            // 离场窗口 + 未满条：入旅店；无旅店/满 → 离场。交互/排队中先完成交互，不打断。
            leave = interacting ? false : !routeToHotel(level, s);
        } else {
            leave = idleTimeout;
        }
        if (leave) {
            depart(level, s);
        }
    }

    private boolean routeToHotel(ServerLevel level, TouristShadow s) {
        BuildingState hotel = TouristSimulation.findHotelTarget(level, s, false);
        if (hotel == null) return false;
        s.setTargetBuildingId(hotel.getBuildingId());
        s.setTargetBuildingCategory("service");
        s.setCommuteTarget(hotel.getPosition());
        return true;
    }

    /**
     * 傍晚路由（16000 起，无旅店游客）：打断当前交互/排队，设置去最近旅店的目标。
     * 旅店可用才打断（无旅店则保持原状，离场窗口兜底）。
     */
    private boolean routeToHotelForEvening(ServerLevel level, TouristShadow s) {
        BuildingState hotel = TouristSimulation.findHotelTarget(level, s, false);
        if (hotel == null) return false;
        releaseShadowSpots(s);
        s.setCommuteTarget(null);
        s.setTargetBuildingId(null);
        s.setTargetBuildingCategory(null);
        s.setTargetBuildingId(hotel.getBuildingId());
        s.setTargetBuildingCategory("service");
        s.setCommuteTarget(hotel.getPosition());
        return true;
    }

    /** 住店客夜晚回自己旅店；旅店已失效返回 false（调用方解除登记）。 */
    private boolean routeToOwnHotel(ServerLevel level, TouristShadow s) {
        UUID hotel = s.getCheckedInBuildingId();
        if (hotel == null) return false;
        BuildingApi api = getBuildingApi();
        BuildingData data = api != null ? api.getBuilding(hotel) : null;
        if (data == null || data.isShutdown() || !data.isStructureIntact()
                || !TouristSimulation.isHotelBuilding(level, hotel)) {
            return false;
        }
        s.setCommuteTarget(null);
        s.setTargetBuildingId(null);
        s.setTargetBuildingCategory(null);
        s.setTargetBuildingId(hotel);
        s.setTargetBuildingCategory("service");
        s.setCommuteTarget(data.getPosition());
        return true;
    }

    /** 影子是否已在自己旅店附近（入住/回店后停在此处）。旅店已消失视为已到店（离场逻辑兜底）。 */
    private boolean atOwnHotel(ServerLevel level, TouristShadow s, UUID hotel) {
        BuildingApi api = getBuildingApi();
        BuildingData data = api != null ? api.getBuilding(hotel) : null;
        if (data == null) return true;
        BlockPos p = data.getPosition();
        return Math.abs(s.getPosX() - p.getX()) <= AT_HOTEL_RANGE
                && Math.abs(s.getPosZ() - p.getZ()) <= AT_HOTEL_RANGE;
    }

    private void depart(ServerLevel level, TouristShadow s) {
        releaseShadowSpots(s);
        grantExperience(s);
        if (s.isMage() && s.isFullySatisfied() && !s.isMageResumeStored()) {
            storeMageResume(s);
        }
        TouristApi touristApi = getTouristApi();
        if (touristApi != null && s.getColonyId() != null) {
            BarRatio fill = BarRatio.of(s.getComfortSat(), s.getComfortNeed(),
                    s.getMagicSat(), s.getMagicNeed(), s.getWonderSat(), s.getWonderNeed());
            touristApi.registerDeparture(s.getTouristId(), s.getColonyId(), fill);
        }
        registry.remove(s.getTouristId());
        Log.info(TAG, "[Tourist] {} (sim) departed (bars={}/{}/{} energy={})",
                s.getTouristName(), s.getComfortSat(), s.getMagicSat(), s.getWonderSat(), s.getEnergy());
    }

    private void grantExperience(TouristShadow s) {
        if (!s.isFullySatisfied()) return;
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
                    s.getMaxHp(), s.getMoveSpeed(), s.getSpellPower(),
                    s.getWorkSpeed(), s.getSpellSpeed(), s.getArmor(),
                    s.getMaxMana(), s.getSkinVariant());
            s.setMageResumeStored(true);
        } catch (IllegalStateException e) {
            Log.warn(TAG, "[Tourist] TavernApi not available — mage resume lost: {}", s.getTouristName());
        }
    }

    // ── Startup adoption ──

    /** Create shadows for any live entities that predate this system (upgrade path). */
    private void adoptExistingEntities(ServerLevel level) {
        int adopted = 0;
        for (var e : level.getAllEntities()) {
            if (e instanceof TouristEntity t && t.isAlive() && !t.isPreview()) {
                // Seed the live-entity cache so subsequent ticks skip getAllEntities().
                registerEntity(t);
                if (registry.get(t.getUUID()) == null) {
                    adoptTourist(t);
                    adopted++;
                }
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
