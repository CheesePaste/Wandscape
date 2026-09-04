package com.wsteam.wandscape.content.tourist.internal;

import com.wsteam.wandscape.api.NpcSpawnSpec;
import com.wsteam.wandscape.api.TavernApi;
import com.wsteam.wandscape.api.WarehouseApi;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.data.MageResume;
import com.wsteam.wandscape.content.npc.data.RecruitmentCandidate;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of {@link TavernApi}.
 * Delegates mage resume storage to {@link TavernRecruitStorage} (SavedData).
 */
public class TavernApiImpl implements TavernApi {
    private static final String TAG = "TavernApiImpl";

    @Nullable
    private TavernRecruitStorage storage;

    public void setStorage(TavernRecruitStorage storage) {
        this.storage = storage;
    }

    private TavernRecruitStorage getStorage() {
        if (storage != null) return storage;
        ServerLevel level = getServerLevel();
        if (level == null) return null;
        storage = TavernRecruitStorage.getOrCreate(level);
        return storage;
    }

    public void receiveMageResume(UUID colonyId, String touristName, int level,
                                   float maxHp, float moveSpeed, float spellPower,
                                   float workSpeed, float spellSpeed, float armorValue,
                                   float maxMana, int skinVariant) {
        TavernRecruitStorage s = getStorage();
        if (s == null) return;

        MageResume resume = new MageResume(touristName, level, maxHp, moveSpeed, spellPower,
                workSpeed, spellSpeed, armorValue, maxMana, skinVariant, System.currentTimeMillis());
        s.addResume(colonyId, resume);
        Log.info(TAG, "[Tourist] Received mage resume: {} (Lv.{}) for colony {}",
                touristName, level, colonyId.toString().substring(0, 8));
    }

    @Override
    public List<MageResume> getMageResumes(UUID colonyId) {
        TavernRecruitStorage s = getStorage();
        if (s == null) return List.of();
        List<MageResume> resumes = new ArrayList<>(s.getResumes(colonyId));
        // Reverse so newest is first
        java.util.Collections.reverse(resumes);
        return resumes;
    }

    @Override
    public MageResume recruitMage(UUID tavernId, UUID colonyId, int index) {
        return recruitMage(tavernId, colonyId, index, NpcSpawnSpec.builder().build());
    }

    @Override
    public MageResume recruitMage(UUID tavernId, UUID colonyId, int index, NpcSpawnSpec overrides) {
        if (colonyId == null) return null;
        TavernRecruitStorage s = getStorage();
        if (s == null) return null;
        List<MageResume> resumes = new ArrayList<>(s.getResumes(colonyId));
        java.util.Collections.reverse(resumes);
        if (index < 0 || index >= resumes.size()) return null;
        MageResume resume = resumes.get(index);

        // 定位酒馆作为生成点；无法定位则不消耗简历。在门附近找落点，避免生成进结构内部。
        BlockPos anchor = resolveTavernAnchor(tavernId);
        if (anchor == null) {
            Log.warn(TAG, "recruitMage: cannot resolve tavern position, resume kept");
            return null;
        }
        ServerLevel lvl = getServerLevel();
        if (lvl == null) return null;
        BlockPos pos = findSpawnPos(lvl, anchor);

        // 从简历生成并合并 overrides；生成成功才消耗简历。
        NpcSpawnSpec spec = specFromResume(resume, overrides);
        UUID npcId = spawnViaApi(colonyId, pos, spec);
        if (npcId == null) {
            Log.warn(TAG, "recruitMage: spawn failed for {} — resume kept", resume.touristName());
            return null;
        }

        MageResume taken = s.takeResume(colonyId, resumes.size() - 1 - index);
        Log.info(TAG, "[Tavern] recruited mage {} (Lv.{}) for colony {} from resume",
                resume.touristName(), resume.level(), shortId(colonyId));
        return taken != null ? taken : resume;
    }

    @Override
    public MageResume rejectMage(UUID colonyId, int index) {
        TavernRecruitStorage s = getStorage();
        if (s == null) return null;
        List<MageResume> resumes = new ArrayList<>(s.getResumes(colonyId));
        java.util.Collections.reverse(resumes);
        if (index < 0 || index >= resumes.size()) return null;
        MageResume resume = s.takeResume(colonyId, resumes.size() - 1 - index);
        if (resume != null) {
            Log.info(TAG, "[Tourist] Rejected mage resume {} for colony {}",
                    resume.touristName(), colonyId.toString().substring(0, 8));
        }
        return resume;
    }

    @Override
    public int getRecruitCount(UUID colonyId) {
        TavernRecruitStorage s = getStorage();
        return s != null ? s.getRecruitCount(colonyId) : 0;
    }

    @Override
    public boolean canAffordRecruit(UUID colonyId) {
        TavernRecruitStorage s = getStorage();
        if (s == null) return false;
        if (s.getRecruitCount(colonyId) == 0) return true; // 首次免费
        WarehouseApi wh = WandscapeApis.getWarehouseApiSilently();
        return wh != null && ElementType.allEnough(wh.getAllElements(colonyId),
                com.wsteam.wandscape.Config.TAVERN_RECRUIT_COST_PER_ELEMENT.get());
    }

    @Override
    public boolean chargeRecruit(UUID colonyId) {
        return chargeRecruit(colonyId, com.wsteam.wandscape.Config.TAVERN_RECRUIT_COST_PER_ELEMENT.get());
    }

    @Override
    public boolean chargeRecruit(UUID colonyId, int costPerElement) {
        TavernRecruitStorage s = getStorage();
        if (s == null) return false;
        int cost = Math.max(0, costPerElement);
        if (s.getRecruitCount(colonyId) == 0) {
            s.incrementRecruitCount(colonyId); // 首次免费
            return true;
        }
        WarehouseApi wh = WandscapeApis.getWarehouseApiSilently();
        if (wh == null) return false;
        if (!ElementType.allEnough(wh.getAllElements(colonyId), cost)) return false;
        for (ElementType t : ElementType.values()) {
            wh.consumeElement(colonyId, t, cost);
        }
        s.incrementRecruitCount(colonyId);
        return true;
    }

    @Override
    public UUID recruitForColony(UUID colonyId, BlockPos spawnPos) {
        // 酒馆招募的法师无起始战斗魔法：显式空载荷（与简历招募一致）。
        return recruitForColony(colonyId, spawnPos,
                NpcSpawnSpec.builder().spells(List.of()).build(),
                com.wsteam.wandscape.Config.TAVERN_RECRUIT_COST_PER_ELEMENT.get());
    }

    @Override
    public UUID recruitForColony(UUID colonyId, BlockPos spawnPos, NpcSpawnSpec spec, int costPerElement) {
        if (colonyId == null || spawnPos == null) return null;
        // 酒馆招募的法师默认无起始战斗魔法：调用方未显式设 spells 时按空载荷处理。
        if (spec == null) spec = NpcSpawnSpec.builder().build();
        if (spec.spells() == null) spec = spec.withSpells(List.of());
        TavernRecruitStorage s = getStorage();
        int cost = Math.max(0, costPerElement);
        // 收费门控（首次免费）：不足则不生成、不计费。
        if (s != null && s.getRecruitCount(colonyId) != 0) {
            WarehouseApi wh = WandscapeApis.getWarehouseApiSilently();
            if (wh == null || !ElementType.allEnough(wh.getAllElements(colonyId), cost)) return null;
        }
        UUID npcId = spawnViaApi(colonyId, spawnPos, spec);
        if (npcId == null) return null;
        if (s != null) chargeRecruit(colonyId, cost);
        return npcId;
    }

    // ── helpers ──

    /** 从简历 + overrides 合并出生成规格（简历属性为底，overrides 逐项覆盖）。 */
    private static NpcSpawnSpec specFromResume(MageResume r, NpcSpawnSpec overrides) {
        Map<AttributeType, Float> attrs = new HashMap<>();
        attrs.put(AttributeType.MAX_HP, r.maxHp());
        attrs.put(AttributeType.MOVE_SPEED, r.moveSpeed());
        attrs.put(AttributeType.SPELL_POWER, r.spellPower());
        attrs.put(AttributeType.WORK_SPEED, r.workSpeed());
        attrs.put(AttributeType.SPELL_SPEED, r.spellSpeed());
        attrs.put(AttributeType.ARMOR_VALUE, r.armorValue());
        attrs.put(AttributeType.MAX_MANA, r.maxMana());
        if (overrides != null && overrides.attributes() != null) {
            attrs.putAll(overrides.attributes());
        }

        NpcSpawnSpec.Builder b = NpcSpawnSpec.builder()
                .name(r.touristName())
                .level(r.level())
                .skinVariant(r.skinVariant())
                .spells(List.of())       // 招聘法师无起始战斗魔法
                .attributes(attrs);
        if (overrides != null) {
            if (overrides.name() != null) b.name(overrides.name());
            if (overrides.level() != null) b.level(overrides.level());
            if (overrides.skinVariant() != null) b.skinVariant(overrides.skinVariant());
            if (overrides.hatColor() != null) b.hatColor(overrides.hatColor());
            if (overrides.strategyPreset() != null) b.strategyPreset(overrides.strategyPreset());
            if (overrides.spells() != null) b.spells(overrides.spells());
        }
        return b.build();
    }

    /** 经 NpcApi 生成（未装配时安全返回 null）。 */
    @Nullable
    private static UUID spawnViaApi(UUID colonyId, BlockPos pos, NpcSpawnSpec spec) {
        try {
            return WandscapeApis.getNpcApi().spawnNpc(colonyId, pos, spec);
        } catch (IllegalStateException e) {
            Log.warn(TAG, "NpcApi unavailable, spawn aborted");
            return null;
        }
    }

    /** 酒馆建筑的世界锚点坐标。 */
    @Nullable
    private static BlockPos resolveTavernAnchor(UUID tavernId) {
        if (tavernId == null) return null;
        try {
            var api = WandscapeApis.getBuildingApiSilently();
            if (api == null) return null;
            var b = api.getBuilding(tavernId);
            return b != null ? b.getPosition() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 在 origin 附近找一块「上方空气、下方实心」的地面落点（无则落在 origin.above(2)）。 */
    private static BlockPos findSpawnPos(ServerLevel level, BlockPos origin) {
        BlockPos[] candidates = {
                origin, origin.offset(1, 0, 0), origin.offset(-1, 0, 0),
                origin.offset(0, 0, 1), origin.offset(0, 0, -1),
                origin.offset(1, 0, 1), origin.offset(-1, 0, -1),
                origin.offset(1, 0, -1), origin.offset(-1, 0, 1),
        };
        for (BlockPos base : candidates) {
            for (int dy = 0; dy < 6; dy++) {
                BlockPos check = base.offset(0, dy, 0);
                if (level.isEmptyBlock(check) && !level.isEmptyBlock(check.below())) {
                    return check;
                }
            }
            if (level.isEmptyBlock(base.above())) {
                return base.above();
            }
        }
        return origin.above(2);
    }

    private static String shortId(UUID id) {
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    @Nullable
    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
