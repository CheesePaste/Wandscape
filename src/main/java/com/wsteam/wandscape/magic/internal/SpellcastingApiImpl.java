package com.wsteam.wandscape.magic.internal;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.core.component.CastStrategyComponent;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.shared.api.SpellcastingApi;

/**
 * {@link SpellcastingApi} 实现：UUID → NPC 实体（EntityComponentBridge）→ 读写
 * {@code spellbook}/{@code castStrategy} 组件，优先级经 {@link CastBrain} 解析。
 */
public final class SpellcastingApiImpl implements SpellcastingApi {

    @Override
    public List<String> getKnownSpells(UUID npcId) {
        WandscapeNpc npc = resolve(npcId);
        return npc != null ? npc.spellbook.ids() : List.of();
    }

    @Override
    public void setKnownSpells(UUID npcId, List<String> spellIds) {
        WandscapeNpc npc = resolve(npcId);
        if (npc != null) {
            npc.spellbook.set(spellIds);
        }
    }

    @Override
    public String getStrategyPreset(UUID npcId) {
        WandscapeNpc npc = resolve(npcId);
        return npc != null ? npc.castStrategy.preset().name() : CastStrategyComponent.Preset.BALANCED.name();
    }

    @Override
    public List<String> getPriority(UUID npcId) {
        WandscapeNpc npc = resolve(npcId);
        if (npc == null) return List.of();
        return CastBrain.resolvePriority(npc.castStrategy,
                CastBrain.knownSpells(npc.spellbook.ids()))
                .stream().map(MagicDef::id).toList();
    }

    @Override
    public void setStrategy(UUID npcId, String preset, List<String> priority) {
        WandscapeNpc npc = resolve(npcId);
        if (npc == null) return;
        // 始终存储显式列表（setCustomPriority 会置 configured=true）：客户端每次改动发完整扁平
        // 列表（含点预设后的重排结果）；空列表 = 全部停用。不再按 CUSTOM 门控清空。
        npc.castStrategy.setPreset(preset);
        npc.castStrategy.setCustomPriority(priority != null ? priority : List.of());
    }

    private static WandscapeNpc resolve(UUID npcId) {
        if (npcId == null) return null;
        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(npcId);
        return ecsId != null ? EntityComponentBridge.INSTANCE.getNpc(ecsId) : null;
    }
}
