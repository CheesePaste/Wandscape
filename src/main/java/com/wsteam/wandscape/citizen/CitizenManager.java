package com.wsteam.wandscape.citizen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Singleton manager for citizen NPC lifecycle.
 *
 * <p>Drives spawn, per-tick updates, and despawn. Phase 1 spawns 5
 * citizens near world spawn on server start and advances them every
 * server tick. No persistence — all citizens are discarded on stop.
 */
public class CitizenManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CitizenManager INSTANCE = new CitizenManager();

    private CitizenManager() {}

    public static CitizenManager getInstance() {
        return INSTANCE;
    }

    // ──────────────────────── Configuration ────────────────────────

    private static final int MAX_CITIZENS = 5;

    // ──────────────────────── Name pool ────────────────────────

    private static final String[] SURNAMES = {
            "李", "王", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴",
            "徐", "孙", "马", "胡", "朱", "郭", "何", "罗", "高", "林",
            "郑", "梁", "谢", "宋", "唐", "许", "韩", "冯", "邓", "曹",
            "彭", "曾", "萧", "田", "董", "潘", "袁", "蔡", "蒋", "余",
            "于", "杜", "叶", "程", "苏", "魏", "吕", "丁", "任", "沈"
    };

    private static final String[] GIVENS = {
            "明", "华", "文", "伟", "芳", "秀英", "丽", "强", "勇", "静",
            "慧", "敏", "俊", "杰", "兰", "玲", "超", "平", "刚", "涛",
            "斌", "霞", "红", "建国", "海燕", "宁", "磊", "洋", "辉", "鑫",
            "怡", "珊", "君", "佳", "晨", "宇", "涵", "浩", "博", "瑞",
            "思远", "晓", "雨", "梦", "毅", "恒", "淑珍", "志强", "雪", "云"
    };

    private static final Profession[] PROFESSIONS = Profession.values();

    // ──────────────────────── Runtime state ────────────────────────

    private final Map<UUID, CitizenEntity> activeCitizens = new HashMap<>();
    private final Set<String> usedNames = new HashSet<>();
    private final Random random = new Random();

    // ──────────────────────── Tick ────────────────────────

    /**
     * Called every server tick. Removes dead citizens and
     * (in future phases) drives schedule transitions.
     */
    public void tick(ServerLevel level) {
        for (Iterator<Map.Entry<UUID, CitizenEntity>> it = activeCitizens.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, CitizenEntity> entry = it.next();
            CitizenEntity citizen = entry.getValue();
            if (citizen.isRemoved()) {
                it.remove();
                usedNames.remove(citizen.getCitizenName());
                LOGGER.debug("[Citizen] {} removed from world", citizen.getCitizenName());
            }
        }
    }

    // ──────────────────────── Spawn ────────────────────────

    /**
     * Spawn the initial batch of citizens near the world spawn point.
     */
    public void spawnInitial(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        LOGGER.info("[Citizen] spawning {} citizens near spawn {}", MAX_CITIZENS, spawn);

        for (int i = 0; i < MAX_CITIZENS; i++) {
            int dx = random.nextInt(10) - 5;
            int dz = random.nextInt(10) - 5;
            BlockPos pos = spawn.offset(dx, 0, dz);
            // Find the ground
            BlockPos ground = findGround(level, pos);
            spawnCitizen(level, ground);
        }
    }

    /**
     * Spawn a single citizen at the given position.
     *
     * @return the new entity, or null if the cap is reached or an error occurs
     */
    public CitizenEntity spawnCitizen(ServerLevel level, BlockPos pos) {
        if (activeCitizens.size() >= MAX_CITIZENS) {
            LOGGER.debug("[Citizen] spawn skipped — cap {} reached", MAX_CITIZENS);
            return null;
        }

        String name = generateUniqueName();
        Profession profession = PROFESSIONS[random.nextInt(PROFESSIONS.length)];

        CitizenEntity citizen = new CitizenEntity(
                com.wsteam.wandscape.Wandscape.CITIZEN.get(),
                level);
        citizen.setCitizenName(name);
        citizen.setProfession(profession);
        citizen.setMood(40 + random.nextInt(41)); // 40–80
        citizen.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

        level.addFreshEntity(citizen);
        activeCitizens.put(citizen.getUUID(), citizen);

        LOGGER.info("[Citizen] spawned {} ({}), total={}",
                name, profession.getDisplayName(), activeCitizens.size());
        return citizen;
    }

    // ──────────────────────── Despawn ────────────────────────

    /**
     * Remove a specific citizen from the world and tracking.
     */
    public void despawnCitizen(UUID id) {
        CitizenEntity citizen = activeCitizens.remove(id);
        if (citizen != null) {
            usedNames.remove(citizen.getCitizenName());
            citizen.discard();
            LOGGER.info("[Citizen] despawned {}", citizen.getCitizenName());
        }
    }

    /** Discard all citizens. Called on server stop. */
    public void onServerStopped() {
        LOGGER.info("[Citizen] stopping — discarding {} citizens", activeCitizens.size());
        for (CitizenEntity citizen : activeCitizens.values()) {
            citizen.discard();
        }
        activeCitizens.clear();
        usedNames.clear();
    }

    // ──────────────────────── Queries ────────────────────────

    public int countActive() {
        return activeCitizens.size();
    }

    public Set<UUID> getActiveIds() {
        return Set.copyOf(activeCitizens.keySet());
    }

    // ──────────────────────── Name generation ────────────────────────

    private String generateUniqueName() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String surname = SURNAMES[random.nextInt(SURNAMES.length)];
            String given = GIVENS[random.nextInt(GIVENS.length)];
            String candidate = surname + given;
            if (!usedNames.contains(candidate)) {
                usedNames.add(candidate);
                return candidate;
            }
        }
        // Fallback: append numeric suffix
        for (int i = 2; ; i++) {
            String surname = SURNAMES[random.nextInt(SURNAMES.length)];
            String given = GIVENS[random.nextInt(GIVENS.length)];
            String candidate = surname + given + i;
            if (!usedNames.contains(candidate)) {
                usedNames.add(candidate);
                return candidate;
            }
        }
    }

    // ──────────────────────── Helpers ────────────────────────

    /** Find the top solid block at the given XZ column. */
    private static BlockPos findGround(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
        // Start from a reasonable height, scan down for a solid block
        mp.setY(Math.min(level.getMaxBuildHeight(), 120));
        while (mp.getY() > level.getMinBuildHeight()) {
            if (!level.getBlockState(mp).isAir()
                    && level.getBlockState(mp.above()).isAir()) {
                return mp.above().immutable();
            }
            mp.move(0, -1, 0);
        }
        return pos; // fallback
    }
}
