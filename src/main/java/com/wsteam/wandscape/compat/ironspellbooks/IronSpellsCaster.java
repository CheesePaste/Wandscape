package com.wsteam.wandscape.compat.ironspellbooks;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;

import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * 铁魔法施法执行器：处理瞬发、长蓄力、持续引导法术的状态与生命周期。
 */
public final class IronSpellsCaster {

    private static final String TAG = "IronSpellsCaster";

    private static final class ActiveCast {
        final ServerLevel level;
        final WandscapeNpc npc;
        @Nullable
        final LivingEntity target;
        final AbstractSpell spell;
        final int spellLevel;
        final CastType castType;
        final MagicData magicData;
        int remainingTicks;

        ActiveCast(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                   AbstractSpell spell, int spellLevel, int remainingTicks,
                   CastType castType, MagicData magicData) {
            this.level = level;
            this.npc = npc;
            this.target = target;
            this.spell = spell;
            this.spellLevel = spellLevel;
            this.remainingTicks = remainingTicks;
            this.castType = castType;
            this.magicData = magicData;
        }
    }

    private static final List<ActiveCast> ACTIVE_CASTS = new ArrayList<>();

    private IronSpellsCaster() {}

    /**
     * 为 NPC 施放铁魔法。
     */
    public static boolean cast(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                               String spellId, int spellLevel) {
        if (!IronSpellsCompat.isLoaded()) return false;
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell == null || spell == SpellRegistry.none() || !spell.isEnabled()) {
            return false;
        }

        int manaCost = Math.max(0, spell.getManaCost(spellLevel));
        int baseCooldown = spell.getSpellCooldown() > 0 ? (int) Math.round(spell.getSpellCooldown() * 20.0) : 40;
        CastType castType = spell.getCastType();

        if (target != null && target.isAlive()) {
            npc.faceTarget(BlockPos.containing(target.getBoundingBox().getCenter()));
        }

        float spellSpeed = Math.max(0.1f, npc.getEffectiveAttribute(AttributeType.SPELL_SPEED));

        if (castType == CastType.INSTANT || castType == CastType.NONE) {
            int lockTicks = Math.max(10, (int) Math.ceil(10.0 / spellSpeed));
            if (!npc.tryCastSpell(spellId, baseCooldown, manaCost, lockTicks)) {
                return false;
            }

            MagicData magicData = MagicData.getPlayerMagicData(npc);
            spell.onCast(level, spellLevel, npc, CastSource.MOB, magicData);
            spell.getCastFinishSound().ifPresent(s -> level.playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                    s, SoundSource.NEUTRAL, 1.0f, 1.0f));

            Log.info(TAG, "NPC {} cast instant iron spell '{}' Lv.{}",
                    npc.getUUID().toString().substring(0, 8), spellId, spellLevel);
            return true;
        } else {
            // LONG / CONTINUOUS 蓄力或引导
            int rawCastTime = spell.getCastTime(spellLevel);
            int lockTicks = Math.max(10, (int) Math.ceil(rawCastTime / spellSpeed));
            if (!npc.tryCastSpell(spellId, baseCooldown, manaCost, lockTicks)) {
                return false;
            }

            MagicData magicData = MagicData.getPlayerMagicData(npc);
            spell.onServerPreCast(level, spellLevel, npc, magicData);
            spell.getCastStartSound().ifPresent(s -> level.playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                    s, SoundSource.NEUTRAL, 1.0f, 1.0f));

            ACTIVE_CASTS.add(new ActiveCast(level, npc, target, spell, spellLevel, lockTicks, castType, magicData));
            Log.info(TAG, "NPC {} began channeling iron spell '{}' Lv.{} (lockTicks={})",
                    npc.getUUID().toString().substring(0, 8), spellId, spellLevel, lockTicks);
            return true;
        }
    }

    /**
     * 每 server tick 调用：推进持续施法与长蓄力法术。
     */
    public static void tickAll() {
        if (!IronSpellsCompat.isLoaded() || ACTIVE_CASTS.isEmpty()) return;

        Iterator<ActiveCast> it = ACTIVE_CASTS.iterator();
        while (it.hasNext()) {
            ActiveCast cast = it.next();
            if (cast.npc.isRemoved() || !cast.npc.isAlive()) {
                it.remove();
                continue;
            }

            if (cast.target != null && cast.target.isAlive() && !cast.target.isRemoved()) {
                cast.npc.faceTarget(BlockPos.containing(cast.target.getBoundingBox().getCenter()));
            }

            if (cast.castType == CastType.CONTINUOUS) {
                cast.spell.onServerCastTick(cast.level, cast.spellLevel, cast.npc, cast.magicData);
            }

            cast.remainingTicks--;
            if (cast.remainingTicks <= 0) {
                if (cast.castType == CastType.LONG) {
                    cast.spell.onServerCastComplete(cast.level, cast.spellLevel, cast.npc, cast.magicData, false);
                }
                cast.spell.getCastFinishSound().ifPresent(s -> cast.level.playSound(null,
                        cast.npc.getX(), cast.npc.getY(), cast.npc.getZ(), s, SoundSource.NEUTRAL, 1.0f, 1.0f));
                it.remove();
            }
        }
    }
}
