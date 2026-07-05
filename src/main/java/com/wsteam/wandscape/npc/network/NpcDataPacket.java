package com.wsteam.wandscape.npc.network;

import java.util.function.Consumer;

import com.wsteam.wandscape.core.component.EquipmentComponent;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→client packet: opens / updates the NPC info screen.
 */
public record NpcDataPacket(
        int entityId,
        String npcName,
        int currentHealth,
        int maxHealth,
        int currentMana,
        int maxMana,
        int manaRegen,
        int spellPower,
        int range,
        float manaCostMultiplier,
        ItemStack wandStack,
        boolean isDefaultWand
) implements CustomPacketPayload {

    public static final Type<NpcDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "npc_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcDataPacket> STREAM_CODEC =
            StreamCodec.of(NpcDataPacket::write, NpcDataPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    private static Consumer<NpcDataPacket> clientHandler;

    public static void setClientHandler(Consumer<NpcDataPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(NpcDataPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, NpcDataPacket pkt) {
        buf.writeInt(pkt.entityId);
        buf.writeUtf(pkt.npcName);
        buf.writeInt(pkt.currentHealth);
        buf.writeInt(pkt.maxHealth);
        buf.writeInt(pkt.currentMana);
        buf.writeInt(pkt.maxMana);
        buf.writeInt(pkt.manaRegen);
        buf.writeInt(pkt.spellPower);
        buf.writeInt(pkt.range);
        buf.writeFloat(pkt.manaCostMultiplier);
        buf.writeBoolean(pkt.isDefaultWand);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, pkt.wandStack);
    }

    static NpcDataPacket read(RegistryFriendlyByteBuf buf) {
        int entityId = buf.readInt();
        String npcName = buf.readUtf();
        int currentHealth = buf.readInt();
        int maxHealth = buf.readInt();
        int currentMana = buf.readInt();
        int maxMana = buf.readInt();
        int manaRegen = buf.readInt();
        int spellPower = buf.readInt();
        int range = buf.readInt();
        float manaCostMultiplier = buf.readFloat();
        boolean isDefaultWand = buf.readBoolean();
        ItemStack wandStack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        return new NpcDataPacket(entityId, npcName, currentHealth, maxHealth,
                currentMana, maxMana, manaRegen, spellPower, range, manaCostMultiplier,
                wandStack, isDefaultWand);
    }

    /**
     * Factory: build a NpcDataPacket from the given NPC entity.
     * Caller must be on the server thread.
     */
    public static NpcDataPacket from(WandscapeNpc npc) {
        ItemStack held = npc.getItemInHand(InteractionHand.MAIN_HAND);
        boolean isDefault = npc.hasDefaultWand();

        // Read effective range and mana cost from ECS equipment
        int range = 1;
        float manaCostMult = 1f;
        World world = WandscapeEngine.getWorld();
        if (world != null && npc.ecsEntityId > 0) {
            EquipmentComponent eq = world.get(npc.ecsEntityId, EquipmentComponent.class);
            if (eq != null) {
                range = Math.round(eq.getAttribute(AttributeType.RANGE));
                manaCostMult = eq.getAttribute(AttributeType.MANA_COST_MULTIPLIER);
            }
        }

        return new NpcDataPacket(
                npc.getId(),
                npc.getNpcName(),
                (int) npc.getHealth(),
                (int) npc.getMaxHealth(),
                npc.currentMana,
                npc.maxMana,
                npc.manaRegenRate,
                npc.spellPower,
                range,
                manaCostMult,
                isDefault ? ItemStack.EMPTY : held,
                isDefault
        );
    }
}
