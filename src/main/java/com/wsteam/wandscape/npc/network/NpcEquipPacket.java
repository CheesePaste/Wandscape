package com.wsteam.wandscape.npc.network;

import java.util.List;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.component.EquipmentComponent;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.AttributeModifier;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.EquipmentSlot;
import com.wsteam.wandscape.core.types.ModifierOperation;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.wand.item.WandItem;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.network.PacketDistributor;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→server packet: equip/unequip a wand for an NPC.
 *
 * <p>Actions:
 * <ul>
 *   <li>{@code ACTION_EQUIP} + {@code slotIndex=N} — equip wand from player inventory slot N</li>
 *   <li>{@code ACTION_UNEQUIP} — unequip NPC's current wand back to player inventory</li>
 * </ul>
 */
public record NpcEquipPacket(int entityId, int action, int slotIndex)
        implements CustomPacketPayload {

    public static final int ACTION_EQUIP = 0;
    public static final int ACTION_UNEQUIP = 1;

    public static final Type<NpcEquipPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "npc_equip"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcEquipPacket> STREAM_CODEC =
            StreamCodec.of(NpcEquipPacket::write, NpcEquipPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, NpcEquipPacket pkt) {
        buf.writeInt(pkt.entityId);
        buf.writeInt(pkt.action);
        buf.writeInt(pkt.slotIndex);
    }

    static NpcEquipPacket read(RegistryFriendlyByteBuf buf) {
        return new NpcEquipPacket(buf.readInt(), buf.readInt(), buf.readInt());
    }

    // ── Server handler ──

    private static final String TAG = "NpcEquipPacket";

    public static void handleServer(NpcEquipPacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;

        var level = player.serverLevel();
        var entity = level.getEntity(packet.entityId());
        if (!(entity instanceof WandscapeNpc npc)) {
            Log.warn(TAG, "Equip target entity {} is not a WandscapeNpc", packet.entityId());
            return;
        }

        switch (packet.action()) {
            case ACTION_EQUIP -> handleEquip(packet, player, npc);
            case ACTION_UNEQUIP -> handleUnequip(player, npc);
            default -> Log.warn(TAG, "Unknown equip action: {}", packet.action());
        }
    }

    private static void handleEquip(NpcEquipPacket packet, ServerPlayer player, WandscapeNpc npc) {
        int slot = packet.slotIndex();
        if (slot < 0 || slot >= player.getInventory().items.size()) return;

        ItemStack newWandStack = player.getInventory().getItem(slot);
        if (newWandStack.isEmpty()) return;
        if (!(newWandStack.getItem() instanceof WandItem)) return;

        // Resolve additive attribute modifiers from the wand's preset.
        // All equipment grants are addition on top of base attributes.
        String presetId = "";
        List<AttributeModifier> modifiers = List.of(
                new AttributeModifier(AttributeType.SPELL_POWER, 0f, ModifierOperation.ADDITION));
        CustomData cd = newWandStack.get(DataComponents.CUSTOM_DATA);
        if (cd != null) {
            presetId = cd.copyTag().getString("preset_id");
            if (!presetId.isEmpty()) {
                var preset = Wandscape.WAND_PRESET_LOADER.getPreset(presetId);
                if (preset != null && !preset.attributes().isEmpty()) {
                    modifiers = preset.attributes();
                }
            }
        }

        // ── Swap items ──
        ItemStack oldWand = npc.getItemInHand(InteractionHand.MAIN_HAND);
        npc.setItemInHand(InteractionHand.MAIN_HAND, newWandStack.copyWithCount(1));

        if (!oldWand.isEmpty()) {
            player.getInventory().setItem(slot, oldWand);
        } else {
            newWandStack.shrink(1);
            if (newWandStack.isEmpty()) {
                player.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }

        npc.setHasDefaultWand(false);

        // ── Sync ECS EquipmentComponent ──
        World world = WandscapeEngine.getWorld();
        if (world != null && npc.ecsEntityId > 0) {
            EquipmentComponent eq = world.get(npc.ecsEntityId, EquipmentComponent.class);
            if (eq != null) {
                eq.unequip(EquipmentSlot.WAND);
                eq.equip(EquipmentSlot.WAND, presetId.isEmpty() ? "custom_wand" : presetId, modifiers);
                Log.info(TAG, "ECS equip: NPC {} preset={} modifiers={}",
                        npc.getUUID().toString().substring(0, 8), presetId, modifiers.size());
            }
        }

        // ── Send updated screen data ──
        PacketDistributor.sendToPlayer(player, NpcDataPacket.from(npc));
    }

    private static void handleUnequip(ServerPlayer player, WandscapeNpc npc) {
        if (npc.hasDefaultWand()) {
            Log.warn(TAG, "Cannot unequip default wand from NPC {}", npc.getUUID());
            return;
        }

        ItemStack currentWand = npc.getItemInHand(InteractionHand.MAIN_HAND);
        if (!currentWand.isEmpty()) {
            if (!player.getInventory().add(currentWand)) {
                player.drop(currentWand, false);
            }
        }

        // Reset to default wand
        npc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Wandscape.WAND.get()));
        npc.setHasDefaultWand(true);

        // ── Sync ECS: unequip → stats fall back to base (= default wand values) ──
        World world = WandscapeEngine.getWorld();
        if (world != null && npc.ecsEntityId > 0) {
            EquipmentComponent eq = world.get(npc.ecsEntityId, EquipmentComponent.class);
            if (eq != null) {
                eq.unequip(EquipmentSlot.WAND);
                Log.info(TAG, "ECS unequip: NPC {} reset to base stats",
                        npc.getUUID().toString().substring(0, 8));
            }
        }

        // ── Send updated screen data ──
        PacketDistributor.sendToPlayer(player, NpcDataPacket.from(npc));
    }
}
