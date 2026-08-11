package com.wsteam.wandscape.npc.network;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import com.wsteam.wandscape.core.component.EquipmentComponent;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→client packet: opens / updates the NPC info screen.
 *
 * <p>{@code spellCategories} 与 {@code knownSpells} 并行同序（每魔法分类名小写，未知为
 * {@code "unknown"}），让客户端无需跨模块读魔法数据即可按分类分组。
 */
public record NpcDataPacket(
        int entityId,
        String npcName,
        int currentHealth,
        int maxHealth,
        int currentMana,
        int maxMana,
        float moveSpeed,
        float spellPower,
        float workSpeed,
        float spellSpeed,
        float armorValue,
        ItemStack wandStack,
        boolean isDefaultWand,
        String strategyPreset,
        List<String> knownSpells,
        List<String> spellCategories,
        List<String> priority,
        List<ItemStack> armorStacks,
        int skinVariant,
        int hatColor,
        boolean peaceMode,
        boolean followMode
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
        buf.writeFloat(pkt.moveSpeed);
        buf.writeFloat(pkt.spellPower);
        buf.writeFloat(pkt.workSpeed);
        buf.writeFloat(pkt.spellSpeed);
        buf.writeFloat(pkt.armorValue);
        buf.writeBoolean(pkt.isDefaultWand);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, pkt.wandStack);
        buf.writeUtf(pkt.strategyPreset);
        writeStringList(buf, pkt.knownSpells);
        writeStringList(buf, pkt.spellCategories);
        writeStringList(buf, pkt.priority);
        buf.writeVarInt(pkt.armorStacks.size());
        for (ItemStack stack : pkt.armorStacks) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
        }
        buf.writeInt(pkt.skinVariant);
        buf.writeInt(pkt.hatColor);
        buf.writeBoolean(pkt.peaceMode);
        buf.writeBoolean(pkt.followMode);
    }

    private static void writeStringList(RegistryFriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) {
            buf.writeUtf(s);
        }
    }

    static NpcDataPacket read(RegistryFriendlyByteBuf buf) {
        int entityId = buf.readInt();
        String npcName = buf.readUtf();
        int currentHealth = buf.readInt();
        int maxHealth = buf.readInt();
        int currentMana = buf.readInt();
        int maxMana = buf.readInt();
        float moveSpeed = buf.readFloat();
        float spellPower = buf.readFloat();
        float workSpeed = buf.readFloat();
        float spellSpeed = buf.readFloat();
        float armorValue = buf.readFloat();
        boolean isDefaultWand = buf.readBoolean();
        ItemStack wandStack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        String strategyPreset = buf.readUtf();
        List<String> knownSpells = readStringList(buf);
        List<String> spellCategories = readStringList(buf);
        List<String> priority = readStringList(buf);
        int armorCount = buf.readVarInt();
        List<ItemStack> armorStacks = new java.util.ArrayList<>(armorCount);
        for (int i = 0; i < armorCount; i++) {
            armorStacks.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
        int skinVariant = buf.readInt();
        int hatColor = buf.readInt();
        boolean peaceMode = buf.readBoolean();
        boolean followMode = buf.readBoolean();
        return new NpcDataPacket(entityId, npcName, currentHealth, maxHealth,
                currentMana, maxMana, moveSpeed, spellPower, workSpeed, spellSpeed,
                armorValue, wandStack, isDefaultWand, strategyPreset, knownSpells,
                spellCategories, priority, armorStacks, skinVariant, hatColor,
                peaceMode, followMode);
    }

    private static List<String> readStringList(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> out = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(buf.readUtf());
        }
        return out;
    }

    /**
     * Factory: build a NpcDataPacket from the given NPC entity.
     * Caller must be on the server thread.
     */
    public static NpcDataPacket from(WandscapeNpc npc) {
        ItemStack held = npc.getItemInHand(InteractionHand.MAIN_HAND);
        boolean isDefault = npc.hasDefaultWand();

        // Read effective attributes from ECS equipment (base + additive modifiers)
        float moveSpeed = npc.moveSpeed;
        float spellPower = npc.spellPower;
        float workSpeed = npc.workSpeed;
        float spellSpeed = npc.spellSpeed;
        float armorValue = npc.armorValue;
        World world = WandscapeEngine.getWorld();
        if (world != null && npc.ecsEntityId > 0) {
            EquipmentComponent eq = world.get(npc.ecsEntityId, EquipmentComponent.class);
            if (eq != null) {
                moveSpeed = eq.getAttribute(AttributeType.MOVE_SPEED);
                spellPower = eq.getAttribute(AttributeType.SPELL_POWER);
                workSpeed = eq.getAttribute(AttributeType.WORK_SPEED);
                spellSpeed = eq.getAttribute(AttributeType.SPELL_SPEED);
                armorValue = eq.getAttribute(AttributeType.ARMOR_VALUE);
            }
        }

        // P3：施法策略（预设 + 魔法表 + 解析后的优先级），经 SpellcastingApi 取；未初始化回退空
        String strategyPreset = "";
        List<String> knownSpells = List.of();
        List<String> priority = List.of();
        var casting = WandscapeApis.getSpellcastingApiSilently();
        if (casting != null) {
            UUID uuid = npc.getUUID();
            strategyPreset = casting.getStrategyPreset(uuid);
            knownSpells = casting.getKnownSpells(uuid);
            priority = casting.getPriority(uuid);
        }
        // 与 knownSpells 并行：每魔法分类名（小写），未知回退 "unknown"
        List<String> spellCategories = new java.util.ArrayList<>(knownSpells.size());
        for (String id : knownSpells) {
            MagicDef def = SpellbookLoader.getSpec(id);
            spellCategories.add(def != null ? def.category().name().toLowerCase(Locale.ROOT) : "unknown");
        }

        // 盔甲格（顺序：头盔/胸甲/护腿/靴子）— 防御性拷贝，避免引用共享实例
        List<ItemStack> armorStacks = new java.util.ArrayList<>(WandscapeNpc.ARMOR_SLOT_COUNT);
        for (int i = 0; i < WandscapeNpc.ARMOR_SLOT_COUNT; i++) {
            armorStacks.add(npc.getArmorItem(i).copy());
        }

        return new NpcDataPacket(
                npc.getId(),
                npc.getNpcName(),
                (int) npc.getHealth(),
                (int) npc.getMaxHealth(),
                (int) npc.getCurrentMana(),
                (int) npc.getMaxMana(),
                moveSpeed,
                spellPower,
                workSpeed,
                spellSpeed,
                armorValue,
                isDefault ? ItemStack.EMPTY : held,
                isDefault,
                strategyPreset,
                knownSpells,
                spellCategories,
                priority,
                armorStacks,
                npc.getSkinVariant(),
                npc.getHatColor(),
                npc.isPeaceMode(),
                npc.isFollowMode()
        );
    }
}
