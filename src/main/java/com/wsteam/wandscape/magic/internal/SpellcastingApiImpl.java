package com.wsteam.wandscape.magic.internal;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.wsteam.wandscape.core.component.CastStrategyComponent;
import com.wsteam.wandscape.core.component.EquippedMagicComponent;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.shared.api.SpellcastingApi;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * {@link SpellcastingApi} 实现：UUID → NPC 实体（EntityComponentBridge，未注册 ECS 的
 * 敌对法师回退按 UUID 扫世界）→ 读写 {@code equippedMagic}/{@code castStrategy} 组件，
 * 优先级经 {@link CastBrain} 解析。装备载荷按类别装桶校验（每类 ≤3、去重、ALTAR/SPECIAL 排除）。
 */
public final class SpellcastingApiImpl implements SpellcastingApi {

    @Override
    public List<String> getKnownSpells(UUID npcId) {
        WandscapeNpc npc = resolve(npcId);
        return npc != null ? npc.equippedMagic.flattened() : List.of();
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
                CastBrain.knownSpells(npc.equippedMagic.flattened()))
                .stream().map(MagicDef::id).toList();
    }

    @Override
    public void setEquippedAndStrategy(UUID npcId, String preset, List<String> equipped) {
        WandscapeNpc npc = resolve(npcId);
        if (npc == null) return;
        // 服务端权威：按每个魔法真实分类装桶，未知 / ALTAR / SPECIAL 丢、每类 ≤3、去重
        EquippedMagicComponent validated = EquippedMagicComponent.fromFlat(equipped,
                SpellbookLoader::equippableCategoryOf);
        npc.equippedMagic.replaceWith(validated);
        npc.castStrategy.setPreset(preset);
    }

    private static WandscapeNpc resolve(UUID npcId) {
        if (npcId == null) return null;
        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(npcId);
        if (ecsId != null) return EntityComponentBridge.INSTANCE.getNpc(ecsId);
        // 非 ECS NPC（如敌对测试法师）：按 UUID 查已加载实体。界面编辑/显示是低频路径，
        // 全量已加载实体查找可接受；普通小镇 NPC 仍走上面的桥查询。
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(npcId) instanceof WandscapeNpc npc) {
                return npc;
            }
        }
        return null;
    }
}