package com.wsteam.wandscape.content.npc;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.npc.types.AttributeType;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry and vanilla mapping for Wandscape attributes.
 *
 * <p>Registers 6 custom entity attributes and provides bi-directional mapping
 * between core {@link AttributeType} and vanilla {@link Holder}&lt;{@link Attribute}&gt;.
 */
public final class WandscapeAttributes {

    private WandscapeAttributes() {}

    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(Registries.ATTRIBUTE, Wandscape.MODID);

    public static final Holder<Attribute> SPELL_POWER = ATTRIBUTES.register("spell_power",
            () -> new RangedAttribute("attribute.name.wandscape.spell_power", 1.0, 0.0, 1024.0).setSyncable(true));

    public static final Holder<Attribute> WORK_SPEED = ATTRIBUTES.register("work_speed",
            () -> new RangedAttribute("attribute.name.wandscape.work_speed", 1.0, 0.0, 1024.0).setSyncable(true));

    public static final Holder<Attribute> SPELL_SPEED = ATTRIBUTES.register("spell_speed",
            () -> new RangedAttribute("attribute.name.wandscape.spell_speed", 1.0, 0.0, 1024.0).setSyncable(true));

    public static final Holder<Attribute> MAX_MANA = ATTRIBUTES.register("max_mana",
            () -> new RangedAttribute("attribute.name.wandscape.max_mana", 200.0, 0.0, 1_000_000.0).setSyncable(true));

    public static final Holder<Attribute> HEALTH_REGEN = ATTRIBUTES.register("health_regen",
            () -> new RangedAttribute("attribute.name.wandscape.health_regen", 1.0, 0.0, 1024.0).setSyncable(true));

    public static final Holder<Attribute> MANA_REGEN = ATTRIBUTES.register("mana_regen",
            () -> new RangedAttribute("attribute.name.wandscape.mana_regen", 1.0, 0.0, 1024.0).setSyncable(true));

    /**
     * Maps a core {@link AttributeType} to its corresponding vanilla {@link Holder}&lt;{@link Attribute}&gt;.
     */
    public static Holder<Attribute> toVanilla(AttributeType type) {
        if (type == null) return null;
        return switch (type) {
            case MAX_HP -> Attributes.MAX_HEALTH;
            case MOVE_SPEED -> Attributes.MOVEMENT_SPEED;
            case SPELL_POWER -> SPELL_POWER;
            case WORK_SPEED -> WORK_SPEED;
            case SPELL_SPEED -> SPELL_SPEED;
            case ARMOR_VALUE -> Attributes.ARMOR;
            case MAX_MANA -> MAX_MANA;
            case HEALTH_REGEN -> HEALTH_REGEN;
            case MANA_REGEN -> MANA_REGEN;
        };
    }
}
