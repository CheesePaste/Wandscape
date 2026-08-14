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
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.network.PacketDistributor;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→server packet: equip/unequip a wand or armor piece for an NPC.
 *
 * <p>Actions:
 * <ul>
 *   <li>{@code ACTION_EQUIP} + {@code slotIndex=N} — equip wand from player inventory slot N</li>
 *   <li>{@code ACTION_UNEQUIP} — unequip NPC's current wand back to player inventory</li>
 *   <li>{@code ACTION_EQUIP_ARMOR} + {@code slotIndex=N} — equip armor from player inventory slot N
 *       (服务端按物品自身判定盔甲槽，忽略 armorSlot)</li>
 *   <li>{@code ACTION_UNEQUIP_ARMOR} + {@code armorSlot=0..3} — unequip armor slot back to inventory</li>
 * </ul>
 */
public record NpcEquipPacket(int entityId, int action, int slotIndex, int armorSlot)
        implements CustomPacketPayload {

    public static final int ACTION_EQUIP = 0;
    public static final int ACTION_UNEQUIP = 1;
    public static final int ACTION_EQUIP_ARMOR = 2;
    public static final int ACTION_UNEQUIP_ARMOR = 3;

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
        buf.writeInt(pkt.armorSlot);
    }

    static NpcEquipPacket read(RegistryFriendlyByteBuf buf) {
        return new NpcEquipPacket(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
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
            case ACTION_EQUIP_ARMOR -> handleEquipArmor(packet, player, npc);
            case ACTION_UNEQUIP_ARMOR -> handleUnequipArmor(player, npc, packet.armorSlot());
            default -> Log.warn(TAG, "Unknown equip action: {}", packet.action());
        }
    }

    private static void sendFeedback(ServerPlayer player, Component message) {
        if (player != null && !player.isRemoved()) {
            player.sendSystemMessage(message, true);
            player.sendSystemMessage(message, false);
        }
    }

    private static void handleEquip(NpcEquipPacket packet, ServerPlayer player, WandscapeNpc npc) {
        int slot = packet.slotIndex();
        if (slot < 0 || slot >= player.getInventory().items.size()) return;

        ItemStack newWandStack = player.getInventory().getItem(slot);
        if (newWandStack.isEmpty() || !(newWandStack.getItem() instanceof WandItem)) {
            sendFeedback(player, Component.translatable("message.wandscape.npc.not_equippable"));
            return;
        }

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

        // ── Swap / Equip items ──
        boolean wasDefaultWand = npc.hasDefaultWand();
        ItemStack oldWand = wasDefaultWand ? ItemStack.EMPTY : npc.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack equippedWand = newWandStack.copyWithCount(1);

        npc.setItemInHand(InteractionHand.MAIN_HAND, equippedWand);
        npc.setHasDefaultWand(false);

        newWandStack.shrink(1);
        if (newWandStack.isEmpty()) {
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        }

        boolean swapped = false;
        boolean dropped = false;
        if (!oldWand.isEmpty()) {
            swapped = true;
            if (player.getInventory().getItem(slot).isEmpty()) {
                player.getInventory().setItem(slot, oldWand);
            } else {
                if (!player.getInventory().add(oldWand)) {
                    player.drop(oldWand, false);
                    dropped = true;
                }
            }
        }

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

        // ── Sound & Feedback ──
        player.serverLevel().playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1.0f, 1.2f);
        if (swapped) {
            Component msg = Component.translatable("message.wandscape.npc.swap_success",
                    npc.getDisplayName(), oldWand.getHoverName(), equippedWand.getHoverName());
            if (dropped) {
                msg = Component.literal("").append(msg).append(" ")
                        .append(Component.translatable("message.wandscape.npc.inventory_full_drop"));
            }
            sendFeedback(player, msg);
        } else {
            sendFeedback(player, Component.translatable("message.wandscape.npc.equip_success",
                    npc.getDisplayName(), equippedWand.getHoverName()));
        }
    }

    private static void handleUnequip(ServerPlayer player, WandscapeNpc npc) {
        if (npc.hasDefaultWand()) {
            Log.warn(TAG, "Cannot unequip default wand from NPC {}", npc.getUUID());
            sendFeedback(player, Component.translatable("message.wandscape.npc.cannot_unequip_default"));
            player.serverLevel().playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                    SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 1.0f, 1.2f);
            return;
        }

        ItemStack currentWand = npc.getItemInHand(InteractionHand.MAIN_HAND);
        boolean dropped = false;
        if (!currentWand.isEmpty()) {
            if (!player.getInventory().add(currentWand)) {
                player.drop(currentWand, false);
                dropped = true;
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

        // ── Sound & Feedback ──
        player.serverLevel().playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.0f, 1.0f);
        Component msg = Component.translatable("message.wandscape.npc.unequip_success",
                npc.getDisplayName(), currentWand.getHoverName());
        if (dropped) {
            msg = Component.literal("").append(msg).append(" ")
                    .append(Component.translatable("message.wandscape.npc.inventory_full_drop"));
        }
        sendFeedback(player, msg);
    }

    // ── Armor equip/unequip ──

    /** 原版装备槽 → 盔甲格索引（0..3），非盔甲返回 -1。 */
    private static int armorIndexFor(net.minecraft.world.entity.EquipmentSlot slot) {
        for (int i = 0; i < WandscapeNpc.ARMOR_SLOT_COUNT; i++) {
            if (WandscapeNpc.ARMOR_VANILLA_SLOTS[i] == slot) return i;
        }
        return -1;
    }

    private static void handleEquipArmor(NpcEquipPacket packet, ServerPlayer player, WandscapeNpc npc) {
        int slot = packet.slotIndex();
        if (slot < 0 || slot >= player.getInventory().items.size()) return;

        ItemStack newArmor = player.getInventory().getItem(slot);
        if (newArmor.isEmpty()) return;

        // 按物品自身判定盔甲槽（不信任客户端），非盔甲直接拒绝
        int armorIdx = armorIndexFor(npc.getEquipmentSlotForItem(newArmor));
        if (armorIdx < 0) {
            sendFeedback(player, Component.translatable("message.wandscape.npc.not_equippable"));
            player.serverLevel().playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                    SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 1.0f, 1.2f);
            return;
        }

        // ── Swap items ──
        ItemStack oldArmor = npc.getArmorItem(armorIdx);
        ItemStack equippedArmor = newArmor.copyWithCount(1);
        npc.setArmorItem(armorIdx, equippedArmor);

        newArmor.shrink(1);
        if (newArmor.isEmpty()) {
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        }

        boolean swapped = false;
        boolean dropped = false;
        if (!oldArmor.isEmpty()) {
            swapped = true;
            if (player.getInventory().getItem(slot).isEmpty()) {
                player.getInventory().setItem(slot, oldArmor);
            } else {
                if (!player.getInventory().add(oldArmor)) {
                    player.drop(oldArmor, false);
                    dropped = true;
                }
            }
        }

        // ── Sync ECS attributes (armor value) ──
        npc.syncArmorAttributes();

        // ── Send updated screen data ──
        PacketDistributor.sendToPlayer(player, NpcDataPacket.from(npc));

        // ── Sound & Feedback ──
        player.serverLevel().playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.PLAYERS, 1.0f, 1.0f);
        if (swapped) {
            Component msg = Component.translatable("message.wandscape.npc.swap_success",
                    npc.getDisplayName(), oldArmor.getHoverName(), equippedArmor.getHoverName());
            if (dropped) {
                msg = Component.literal("").append(msg).append(" ")
                        .append(Component.translatable("message.wandscape.npc.inventory_full_drop"));
            }
            sendFeedback(player, msg);
        } else {
            sendFeedback(player, Component.translatable("message.wandscape.npc.equip_success",
                    npc.getDisplayName(), equippedArmor.getHoverName()));
        }
    }

    private static void handleUnequipArmor(ServerPlayer player, WandscapeNpc npc, int armorSlot) {
        if (armorSlot < 0 || armorSlot >= WandscapeNpc.ARMOR_SLOT_COUNT) return;

        ItemStack stack = npc.getArmorItem(armorSlot);
        if (stack.isEmpty()) return;

        npc.setArmorItem(armorSlot, ItemStack.EMPTY);
        boolean dropped = false;
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
            dropped = true;
        }

        // ── Sync ECS attributes (armor value) ──
        npc.syncArmorAttributes();

        // ── Send updated screen data ──
        PacketDistributor.sendToPlayer(player, NpcDataPacket.from(npc));

        // ── Sound & Feedback ──
        player.serverLevel().playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.0f, 1.0f);
        Component msg = Component.translatable("message.wandscape.npc.unequip_success",
                npc.getDisplayName(), stack.getHoverName());
        if (dropped) {
            msg = Component.literal("").append(msg).append(" ")
                    .append(Component.translatable("message.wandscape.npc.inventory_full_drop"));
        }
        sendFeedback(player, msg);
    }
}
