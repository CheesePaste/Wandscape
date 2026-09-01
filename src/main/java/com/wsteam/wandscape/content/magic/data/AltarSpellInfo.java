package com.wsteam.wandscape.content.magic.data;

/**
 * 祭坛可施魔法条目：供 AltarScreen 列表渲染与 AltarOpenPacket 传输。
 * 纯数据 record；魔法显示名由客户端按 {@code magic.wandscape.<id>} 本地化。
 * {@code locked} = 该祭坛该魔法已有待施放/施法中的 altar_cast 任务（发布即锁定）。
 */
public record AltarSpellInfo(
        String magicId,
        int manaCost,
        int cooldownTicks,
        int durationTicks,
        int cooldownRemaining,
        boolean locked
) {}
