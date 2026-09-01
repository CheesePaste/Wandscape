package com.wsteam.wandscape.content.task.network;
import com.wsteam.wandscape.content.task.types.ResourceId;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * DTO representing a missing resource (element or item) for a task awaiting resources.
 */
public record ResourceShortageDto(
        String kind,        // "element" or "item"
        String resourceId,  // e.g. "water", "earth", "minecraft:stone"
        String displayName, // e.g. "水元素", "石头"
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
