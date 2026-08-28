package com.wsteam.wandscape.shared.network.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * DTO representing a production building group (e.g. Workstation, Magic Table, Node)
 * and its associated queue of production items.
 */
public record ProductionGroupDto(
        UUID buildingId,
        String buildingName,
        String category,
        int x,
        int y,
        int z,
        int activeWorkers,
        List<ProductionItemDto> items
) {

    public static void write(RegistryFriendlyByteBuf buf, ProductionGroupDto dto) {
        buf.writeBoolean(dto.buildingId != null);
        if (dto.buildingId != null) buf.writeUUID(dto.buildingId);

        buf.writeUtf(dto.buildingName != null ? dto.buildingName : "");
        buf.writeUtf(dto.category != null ? dto.category : "workstation");
        buf.writeVarInt(dto.x);
        buf.writeVarInt(dto.y);
        buf.writeVarInt(dto.z);
        buf.writeVarInt(dto.activeWorkers);

        buf.writeVarInt(dto.items != null ? dto.items.size() : 0);
        if (dto.items != null) {
            for (ProductionItemDto item : dto.items) {
                ProductionItemDto.write(buf, item);
            }
        }
    }

    public static ProductionGroupDto read(RegistryFriendlyByteBuf buf) {
        UUID buildingId = buf.readBoolean() ? buf.readUUID() : null;
        String buildingName = buf.readUtf();
        String category = buf.readUtf();
        int x = buf.readVarInt();
        int y = buf.readVarInt();
        int z = buf.readVarInt();
        int activeWorkers = buf.readVarInt();

        int itemCount = buf.readVarInt();
        List<ProductionItemDto> items = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            items.add(ProductionItemDto.read(buf));
        }

        return new ProductionGroupDto(buildingId, buildingName, category, x, y, z, activeWorkers, items);
    }
}
