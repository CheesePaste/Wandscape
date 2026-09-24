package com.wsteam.wandscape.content.task.network;
import com.wsteam.wandscape.content.task.types.ResourceId;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * DTO representing a missing resource (element or item) for a task awaiting resources.
 */
public record ResourceShortageDto(
        String kind,        // "element" or "item"
        String resourceId,  // e.g. "water", "earth", "minecraft:stone"
        String displayName, // 无语言兜底名（"水元素"、"石头"）；元素由客户端按 resourceId 取名
        int requiredAmount, // quantity needed
        int currentAmount   // quantity currently in colony warehouse
) {

    public int getMissingAmount() {
        return Math.max(0, requiredAmount - currentAmount);
    }

    public static void write(RegistryFriendlyByteBuf buf, ResourceShortageDto dto) {
        buf.writeUtf(dto.kind != null ? dto.kind : "");
        buf.writeUtf(dto.resourceId != null ? dto.resourceId : "");
        buf.writeUtf(dto.displayName != null ? dto.displayName : "");
        buf.writeVarInt(dto.requiredAmount);
        buf.writeVarInt(dto.currentAmount);
    }

    public static ResourceShortageDto read(RegistryFriendlyByteBuf buf) {
        return new ResourceShortageDto(
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readVarInt(),
                buf.readVarInt()
        );
    }
}
