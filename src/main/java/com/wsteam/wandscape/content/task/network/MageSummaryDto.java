package com.wsteam.wandscape.content.task.network;
import com.wsteam.wandscape.content.task.types.EntityId;

import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.UUID;

/**
 * Compact DTO representing a Mage / NPC in the colony for the management panel.
 */
public record MageSummaryDto(
        long ecsId,
        UUID npcUuid,
        int entityId,
        String name,
        String state, // "CASTING", "MOVING", "IDLE", "RESTING", "FOLLOWING"
        float currentHp,
        float maxHp,
        float currentMana,
        float maxMana,
        float spellPower,
        float workSpeed,
        float spellSpeed,
        float armorValue,
        String currentTaskTitle,
        long currentTaskId,
        String equippedWand,
        double posX,
        double posY,
        double posZ,
        boolean followMode,
        boolean peaceMode
) {

    public float getHealthRatio() {
        return maxHp > 0 ? Math.clamp(currentHp / maxHp, 0f, 1f) : 1f;
    }

    public float getManaRatio() {
        return maxMana > 0 ? Math.clamp(currentMana / maxMana, 0f, 1f) : 1f;
    }

    public static void write(RegistryFriendlyByteBuf buf, MageSummaryDto dto) {
        buf.writeVarLong(dto.ecsId);
        buf.writeUUID(dto.npcUuid);
        buf.writeVarInt(dto.entityId);
        buf.writeUtf(dto.name != null ? dto.name : "Mage");
        buf.writeUtf(dto.state != null ? dto.state : "IDLE");
        buf.writeFloat(dto.currentHp);
        buf.writeFloat(dto.maxHp);
        buf.writeFloat(dto.currentMana);
        buf.writeFloat(dto.maxMana);
        buf.writeFloat(dto.spellPower);
        buf.writeFloat(dto.workSpeed);
        buf.writeFloat(dto.spellSpeed);
        buf.writeFloat(dto.armorValue);
        buf.writeUtf(dto.currentTaskTitle != null ? dto.currentTaskTitle : "");
        buf.writeVarLong(dto.currentTaskId);
        buf.writeUtf(dto.equippedWand != null ? dto.equippedWand : "");
        buf.writeDouble(dto.posX);
        buf.writeDouble(dto.posY);
        buf.writeDouble(dto.posZ);
        buf.writeBoolean(dto.followMode);
        buf.writeBoolean(dto.peaceMode);
    }

    public static MageSummaryDto read(RegistryFriendlyByteBuf buf) {
        long ecsId = buf.readVarLong();
        UUID npcUuid = buf.readUUID();
        int entityId = buf.readVarInt();
        String name = buf.readUtf();
        String state = buf.readUtf();
        float currentHp = buf.readFloat();
        float maxHp = buf.readFloat();
        float currentMana = buf.readFloat();
        float maxMana = buf.readFloat();
        float spellPower = buf.readFloat();
        float workSpeed = buf.readFloat();
        float spellSpeed = buf.readFloat();
        float armorValue = buf.readFloat();
        String currentTaskTitle = buf.readUtf();
        long currentTaskId = buf.readVarLong();
        String equippedWand = buf.readUtf();
        double posX = buf.readDouble();
        double posY = buf.readDouble();
        double posZ = buf.readDouble();
        boolean followMode = buf.readBoolean();
        boolean peaceMode = buf.readBoolean();

        return new MageSummaryDto(
                ecsId, npcUuid, entityId, name, state,
                currentHp, maxHp, currentMana, maxMana,
                spellPower, workSpeed, spellSpeed, armorValue,
                currentTaskTitle, currentTaskId, equippedWand,
                posX, posY, posZ, followMode, peaceMode
        );
    }
}
