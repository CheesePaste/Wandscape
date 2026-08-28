package com.wsteam.wandscape.shared.network.tasks;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * Compact DTO representing a single production item (running head or queued item)
 * for the Production/Supply Chain tab in the Task & Mage Management panel.
 */
public record ProductionItemDto(
        long virtualOrGlobalId,
        int queueIndex,
        String category,
        String blueprintId,
        String itemOrRecipeId,
        String displayName,
        int count,
        String status, // "RUNNING", "QUEUED", "MISSING_ELEMENTS"
        long assignedNpcId,
        String assignedNpcName,
        float progress,
        List<ResourceShortageDto> elementCosts,
        List<String> missingElements,
        String dependencySource,
        boolean activeSupplyingGather
) {

    public static void write(RegistryFriendlyByteBuf buf, ProductionItemDto dto) {
        buf.writeVarLong(dto.virtualOrGlobalId);
        buf.writeVarInt(dto.queueIndex);
        buf.writeUtf(dto.category != null ? dto.category : "other");
        buf.writeUtf(dto.blueprintId != null ? dto.blueprintId : "");
        buf.writeUtf(dto.itemOrRecipeId != null ? dto.itemOrRecipeId : "");
        buf.writeUtf(dto.displayName != null ? dto.displayName : "");
        buf.writeVarInt(dto.count);
        buf.writeUtf(dto.status != null ? dto.status : "QUEUED");
        buf.writeVarLong(dto.assignedNpcId);
        buf.writeUtf(dto.assignedNpcName != null ? dto.assignedNpcName : "");
        buf.writeFloat(dto.progress);

        buf.writeVarInt(dto.elementCosts != null ? dto.elementCosts.size() : 0);
        if (dto.elementCosts != null) {
            for (ResourceShortageDto shortage : dto.elementCosts) {
                ResourceShortageDto.write(buf, shortage);
            }
        }

        buf.writeVarInt(dto.missingElements != null ? dto.missingElements.size() : 0);
        if (dto.missingElements != null) {
            for (String elem : dto.missingElements) {
                buf.writeUtf(elem != null ? elem : "");
            }
        }

        buf.writeUtf(dto.dependencySource != null ? dto.dependencySource : "");
        buf.writeBoolean(dto.activeSupplyingGather);
    }

    public static ProductionItemDto read(RegistryFriendlyByteBuf buf) {
        long virtualOrGlobalId = buf.readVarLong();
        int queueIndex = buf.readVarInt();
        String category = buf.readUtf();
        String blueprintId = buf.readUtf();
        String itemOrRecipeId = buf.readUtf();
        String displayName = buf.readUtf();
        int count = buf.readVarInt();
        String status = buf.readUtf();
        long assignedNpcId = buf.readVarLong();
        String assignedNpcName = buf.readUtf();
        float progress = buf.readFloat();

        int shortageCount = buf.readVarInt();
        List<ResourceShortageDto> elementCosts = new ArrayList<>(shortageCount);
        for (int i = 0; i < shortageCount; i++) {
            elementCosts.add(ResourceShortageDto.read(buf));
        }

        int missingCount = buf.readVarInt();
        List<String> missingElements = new ArrayList<>(missingCount);
        for (int i = 0; i < missingCount; i++) {
            missingElements.add(buf.readUtf());
        }

        String dependencySource = buf.readUtf();
        boolean activeSupplyingGather = buf.readBoolean();

        return new ProductionItemDto(
                virtualOrGlobalId, queueIndex, category, blueprintId,
                itemOrRecipeId, displayName, count, status,
                assignedNpcId, assignedNpcName, progress,
                elementCosts, missingElements, dependencySource, activeSupplyingGather
        );
    }
}
