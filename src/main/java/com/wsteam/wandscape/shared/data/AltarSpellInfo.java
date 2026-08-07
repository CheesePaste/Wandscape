package com.wsteam.wandscape.shared.data;

/**
 * 祭坛可施魔法条目：供 AltarScreen 列表渲染与 AltarOpenPacket 传输。
 * 纯数据 record；魔法显示名由客户端按 {@code magic.wandscape.<id>} 本地化。
 */
public record AltarSpellInfo(
        String magicId,
        int manaCost,
        int cooldownTicks,
        int durationTicks,
        int cooldownRemaining
) {}
